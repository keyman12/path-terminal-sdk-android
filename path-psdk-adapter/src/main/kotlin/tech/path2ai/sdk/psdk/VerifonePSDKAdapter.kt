package tech.path2ai.sdk.psdk

import android.content.Context
import android.util.Log
import com.verifone.payment_sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.path2ai.sdk.core.*
import tech.path2ai.sdk.core.PathError
// The PSDK also declares TransactionResult/ReceiptData — explicit imports
// make the Path core models win over the two star imports.
import tech.path2ai.sdk.core.TransactionResult
import tech.path2ai.sdk.core.ReceiptData
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifone backend for the Path Terminal SDK.
 *
 * Implements [PathTerminalAdapter] over the Verifone Payment SDK (PSDK,
 * TCP/IP to a terminal such as the VP100), so an EPOS app written against
 * the Path SDK can switch from the Pico emulator to a real Verifone device
 * by swapping the adapter it hands to PathTerminal — nothing else changes.
 *
 * The PSDK's quirks are absorbed here (the numbered gotchas reference
 * Path-PSDK-TestHarnesses/CLAUDE.md, where each was proven live):
 *  - login once per connection; a session per transaction, always ended (g2, g4)
 *  - async accepted-vs-outcome split; results arrive via listener events (g4)
 *  - declines are normal results, never errors (g5)
 *  - refund/void link via the opaque appSpecificData token, persisted across
 *    restarts in [TransactionLinkStore]; refunds must re-send amounts (g6)
 *  - refunds raise a PASSWORD prompt the POS must answer — auto-answered
 *    with [VerifoneTerminalConfig.refundPassword] (g9)
 *  - receipts arrive once with completion and can't be re-fetched — cached
 *    in [ReceiptCache] (the facade's auto-fetch is served from it)
 *  - display-wedge auto-recovery: a sale cancelled at "Awaiting Payment
 *    Type Selection" triggers a background teardown→reconnect cycle (g13)
 *  - tip prompting (promptForTip) implements flow B1: a presentUserOptions
 *    menu rendered by the terminal before the card; the adapter computes
 *    the gratuity (scratchpad §7 — gated on live proof in the harness)
 *
 * All PSDK calls run on one dedicated thread; one transaction in flight at
 * a time (concurrent calls fail fast with TERMINAL_BUSY).
 */
class VerifonePSDKAdapter(
    context: Context,
    private val config: VerifoneTerminalConfig,
    private val onLog: ((String) -> Unit)? = null
) : PathTerminalAdapter {

    companion object {
        private const val TAG = "VerifonePSDK"
        private const val INIT_TIMEOUT_S = 20
        private const val LOGIN_TIMEOUT_MS = 15_000
        private const val SESSION_TIMEOUT_MS = 15_000
        private const val PAYMENT_TIMEOUT_S = 180L
        private const val TIP_MENU_TIMEOUT_S = 60L
        private const val WEDGE_SIGNATURE = "Awaiting Payment Type Selection"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "path-psdk") }
    private val psdkDispatcher = executor.asCoroutineDispatcher()
    private val txnMutex = Mutex()

    private val linkStore = TransactionLinkStore(SharedPrefsKeyValueStore(appContext, TransactionLinkStore.PREFS_NAME))
    private val receiptCache = ReceiptCache(SharedPrefsKeyValueStore(appContext, ReceiptCache.PREFS_NAME))

    private var sdk: PaymentSdk? = null
    private var tm: TransactionManager? = null
    private var listener: Bridge? = null

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    override var onHardwareDisconnect: (() -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke("[PSDK] $msg")
    }

    // ── Listener bridge (PSDK events arrive on SDK-internal threads) ─────────

    private inner class Bridge : CommerceListener2() {
        val initDone = CompletableDeferred<Int>()

        @Volatile var paymentDeferred: CompletableDeferred<Pair<Int, Payment?>>? = null
        @Volatile var tipDeferred: CompletableDeferred<Int>? = null
        @Volatile var linkedOpInFlight = false
        @Volatile var lastTransactionMessage: String? = null

        private fun firstPayment(t: Transaction?): Payment? = t?.payments?.firstOrNull()

        // First completion wins — a follow-up endSession also emits
        // TRANSACTION_ENDED and must not clobber the result (gotcha 6).
        private fun complete(status: Int, p: Payment?) {
            paymentDeferred?.complete(status to p)
        }

        override fun handleStatus(s: Status) {
            log("« Status » ${s.status} ${s.type ?: ""} ${s.message ?: ""}")
            if (!initDone.isCompleted) initDone.complete(s.status)
            // -5 = DEVICE_CONNECTION_LOST (also fired by the PCI 24h reboot)
            if (s.status == -5) handleConnectionLost("connection lost (status -5)")
        }

        override fun handleCommerceEvent(e: CommerceEvent) {
            log("« CommerceEvent » ${e.status} ${e.type ?: ""} ${e.message ?: ""}")
        }

        override fun handleTransactionEvent(e: TransactionEvent) {
            log("« TransactionEvent » ${e.status} ${e.type ?: ""} ${e.message ?: ""}")
            e.message?.takeIf { it.isNotEmpty() }?.let { lastTransactionMessage = it }
            // Refund/void normally complete via handlePaymentCompletedEvent
            // (type TRANSACTION_ENDED). Belt-and-suspenders fallback (gotcha 6).
            if (linkedOpInFlight && (e.type == TransactionEvent.TRANSACTION_ENDED ||
                        e.type == TransactionEvent.TRANSACTION_PAYMENT_COMPLETED)
            ) {
                firstPayment(e.transaction)?.let { complete(e.status, it) }
            }
        }

        override fun handlePaymentCompletedEvent(e: PaymentCompletedEvent) {
            log("« PaymentCompletedEvent » ${e.status} ${e.type ?: ""} ${e.message ?: ""}")
            // Refund/void carry the payment on the transaction, not getPayment()
            complete(e.status, e.payment ?: firstPayment(e.transaction))
        }

        override fun handleHostAuthorizationEvent(e: HostAuthorizationEvent) {
            val t = tm ?: return
            val emv = hashMapOf("8a" to "abc123", "91" to "cba312", "92" to "bac213")
            t.respondToHostAuthorization("123456", HostDecisionType.HOST_AUTHORIZED, emv, currentTotal)
            log("responded HOST_AUTHORIZED to HostAuthorization")
        }

        override fun handleHostFinalizeTransactionEvent(e: HostFinalizeTransactionEvent) {
            val t = tm ?: return
            val emv = hashMapOf("8a" to "abc123", "91" to "cba312", "92" to "bac213")
            t.respondToHostFinalizeTransaction("123456", HostDecisionType.HOST_AUTHORIZED, emv, currentTotal)
            log("responded HOST_AUTHORIZED to HostFinalize")
        }

        override fun handleUserInputEvent(e: UserInputEvent) {
            log("« UserInputEvent » ${e.status} ${e.type ?: ""} ${e.message ?: ""}")
            // Tip menu (flow B1): the customer's choice for our presentUserOptions
            // menu comes back carrying selectedIndices on a non-REQUEST event.
            if (e.type != UserInputEvent.REQUEST_TYPE) {
                val idx = e.values?.selectedIndices?.firstOrNull()
                val td = tipDeferred
                if (idx != null && td != null) {
                    log("tip menu answered: index $idx")
                    td.complete(idx)
                    return
                }
                return
            }
            // REQUEST_TYPE: the terminal is BLOCKED until the POS answers
            // (refund asks for a PASSWORD — gotcha 9).
            val t = tm ?: return
            val resp = e.generateUserInputEventResponse() ?: return
            val vals = Values.create()
            when (e.inputType) {
                InputType.PASSWORD -> {
                    vals.setValue(config.refundPassword)
                    log("answered PASSWORD prompt (refundPassword from config${if (config.refundPassword.isEmpty()) ", empty" else ""})")
                }
                InputType.EMAIL, InputType.TEXT -> vals.setValue("")
                InputType.NUMBER, InputType.DECIMAL, InputType.CURRENCY -> vals.setNumericValue(Decimal(2, 0))
                InputType.MENU_OPTIONS -> vals.setSelectedIndices(arrayListOf(0))
                InputType.SIGNATURE -> {
                    resp.setCancelled()
                    t.sendInputResponse(resp)
                    log("cannot supply SIGNATURE — responded CANCELLED")
                    return
                }
                else -> vals.setConfirmed(true)
            }
            resp.setValues(vals)
            t.sendInputResponse(resp)
        }

        override fun handleDeviceVitalsInformationEvent(e: DeviceVitalsInformationEvent) {
            log("« DeviceVitalsInformationEvent » ${e.status}")
        }

        override fun handleNotificationEvent(e: NotificationEvent) { log("« NotificationEvent » ${e.status} ${e.type ?: ""}") }
        override fun handleDeviceManagementEvent(e: DeviceManagementEvent) { log("« DeviceManagementEvent » ${e.status} ${e.type ?: ""}") }
        override fun handleBasketEvent(e: BasketEvent) {}
        override fun handleAmountAdjustedEvent(e: AmountAdjustedEvent) {}
        override fun handleBasketAdjustedEvent(e: BasketAdjustedEvent) {}
        override fun handleCardInformationReceivedEvent(e: CardInformationReceivedEvent) {}
        override fun handleLoyaltyReceivedEvent(e: LoyaltyReceivedEvent) {}
        override fun handleReceiptDeliveryMethodEvent(e: ReceiptDeliveryMethodEvent) {}
        override fun handleStoredValueCardEvent(e: StoredValueCardEvent) {}
        override fun handleReconciliationEvent(e: ReconciliationEvent) {}
        override fun handleReconciliationsListEvent(e: ReconciliationsListEvent) {}
        override fun handleTransactionQueryEvent(e: TransactionQueryEvent) {}
        override fun handlePrintEvent(e: PrintEvent) {}
        override fun handleScannerDataEvent(e: ScannerDataEvent) {}
        override fun handleScannerStateEvent(e: ScannerStateEvent) {}
        override fun handlePinEvent(e: PinEvent) {}
        override fun handleTerminalConfigRequestEvent(e: ConfigurationRequestEvent) {}
    }

    @Volatile
    private var currentTotal: Decimal = Decimal(2, 0)

    // ── Discovery / connection ───────────────────────────────────────────────

    override suspend fun discoverDevices(): List<DiscoveredDevice> {
        // No scan on TCP — one synthetic device for the configured terminal.
        return listOf(
            DiscoveredDevice(
                id = config.host,
                name = "Verifone terminal (${config.host})",
                rssi = 0
            )
        )
    }

    override suspend fun connect(device: DiscoveredDevice): Unit = withContext(psdkDispatcher) {
        if (sdk != null) {
            log("connect: tearing down a previous instance first")
            teardownInternal()
        }
        var lastStatus = initialize()
        if (lastStatus == -2 || lastStatus == -8) {
            // Lingering session on the terminal (gotcha 2) — one teardown+retry.
            log("init returned $lastStatus (lingering session?) — teardown + one retry")
            teardownInternal()
            delay(1500)
            lastStatus = initialize()
        }
        if (lastStatus != StatusCode.SUCCESS) {
            teardownInternal()
            throw PathError(
                code = PathErrorCode.CONNECTIVITY,
                message = "Verifone init failed (PSDK status $lastStatus). Is the terminal at " +
                    "${config.host} idle and on this network? Only ONE client may be connected.",
                adapterErrorCode = lastStatus.toString(),
                recoverable = true
            )
        }
        tm = sdk?.transactionManager
        login()
        _isConnected = true
        log("CONNECTED + logged in (${config.host})")
    }

    /** Runs initializeFromValues and waits for the init status. */
    private suspend fun initialize(): Int {
        val bridge = Bridge().also { listener = it }
        val s = PaymentSdk.create(appContext).also { sdk = it }
        val cfg = hashMapOf(
            PsdkDeviceInformation.DEVICE_ADDRESS_KEY to config.host,
            PsdkDeviceInformation.DEVICE_CONNECTION_TYPE_KEY to "tcpip",
            PsdkInitializationConstants.NETWORK_CONFIGURATION_KEY to
                PsdkInitializationConstants.NETWORK_CONFIGURATION_STATIC_VALUE,
        )
        log("initialising against ${config.host} ...")
        s.initializeFromValues(bridge, cfg)
        return try {
            withTimeout(INIT_TIMEOUT_S * 1000L) { bridge.initDone.await() }
        } catch (_: TimeoutCancellationException) {
            log("no init response within ${INIT_TIMEOUT_S}s")
            -2
        }
    }

    private suspend fun login() {
        val t = tm ?: throw PathError(PathErrorCode.ADAPTER_FAULT, "no TransactionManager after init")
        val creds = LoginCredentials.createWith2(config.username, config.password, config.shift, null)
        t.loginWithCredentials(creds)
        if (!waitForState(LOGIN_TIMEOUT_MS) { t.state == TransactionManagerState.LOGGED_IN }) {
            teardownInternal()
            throw PathError(
                code = PathErrorCode.CONNECTIVITY,
                message = "Verifone login did not reach LOGGED_IN within ${LOGIN_TIMEOUT_MS / 1000}s",
                recoverable = true
            )
        }
        log("logged in")
    }

    override suspend fun disconnect(): Unit = withContext(psdkDispatcher) {
        log("disconnecting (endSession + logout + tearDown)")
        teardownInternal()
        log("disconnected")
    }

    /** Idempotent full teardown — always safe to call (gotcha 2). */
    private fun teardownInternal() {
        try {
            tm?.let { t ->
                if (!sessionClosed()) {
                    t.endSession()
                    waitForStateBlocking(8000) { sessionClosed() }
                }
                if (t.state == TransactionManagerState.LOGGED_IN) {
                    t.logout()
                    waitForStateBlocking(8000) { t.state == TransactionManagerState.NOT_LOGGED_IN }
                }
            }
        } catch (e: Exception) {
            log("teardown: ${e.message}")
        }
        try { sdk?.tearDown() } catch (e: Exception) { log("tearDown: ${e.message}") }
        sdk = null; tm = null; listener = null
        _isConnected = false
    }

    private fun handleConnectionLost(reason: String) {
        if (!_isConnected) return
        log("hardware disconnect: $reason")
        _isConnected = false
        listener?.paymentDeferred?.completeExceptionally(
            PathError(PathErrorCode.CONNECTIVITY, "Terminal connection lost", recoverable = true)
        )
        onHardwareDisconnect?.invoke()
    }

    // ── Session helpers ──────────────────────────────────────────────────────

    private fun sessionClosed(): Boolean {
        val s = tm?.state ?: return true
        return s == TransactionManagerState.LOGGED_IN || s == TransactionManagerState.SESSION_CLOSED ||
            s == TransactionManagerState.NOT_LOGGED_IN
    }

    private suspend fun waitForState(ms: Int, p: () -> Boolean): Boolean {
        var waited = 0
        while (waited < ms) {
            if (p()) return true
            delay(100); waited += 100
        }
        return p()
    }

    private fun waitForStateBlocking(ms: Int, p: () -> Boolean): Boolean {
        var waited = 0
        while (waited < ms) {
            if (p()) return true
            Thread.sleep(100); waited += 100
        }
        return p()
    }

    /** Pre-flight: device-level connection truth, not tm.state (gotcha 12). */
    private fun preflight(op: String) {
        val d = sdk?.deviceInformation
        if (d == null || d.state != PaymentDeviceState.CONNECTED) {
            throw PathError(
                code = PathErrorCode.CONNECTIVITY,
                message = "$op pre-flight failed: terminal not CONNECTED (${d?.state?.name ?: "no device info"})",
                recoverable = true
            )
        }
    }

    private suspend fun ensureSession() {
        val t = tm ?: throw notConnected()
        when (t.state) {
            TransactionManagerState.SESSION_OPEN -> return
            TransactionManagerState.NOT_LOGGED_IN -> throw notConnected()
            else -> {}
        }
        t.startSession2(Transaction.create())
        // While SESSION_OPENING every API except abort/getState is blocked —
        // poll until SESSION_OPEN before issuing anything (PSDK ≥3.65).
        if (!waitForState(SESSION_TIMEOUT_MS) { t.state == TransactionManagerState.SESSION_OPEN }) {
            throw PathError(
                code = PathErrorCode.TERMINAL_FAULT,
                message = "session did not open within ${SESSION_TIMEOUT_MS / 1000}s (state ${t.state?.name})",
                recoverable = true
            )
        }
    }

    private fun endSessionQuietly() {
        try {
            tm?.endSession()
            waitForStateBlocking(10_000) { sessionClosed() }
        } catch (e: Exception) {
            log("endSession: ${e.message}")
        }
    }

    private fun notConnected() = PathError(
        code = PathErrorCode.CONNECTIVITY,
        message = "Not connected to the Verifone terminal (call connect first)",
        recoverable = true
    )

    // ── Transactions ─────────────────────────────────────────────────────────

    override suspend fun sale(request: TransactionRequest): TransactionResult =
        runExclusive("SALE") { runSale(request) }

    private suspend fun runSale(request: TransactionRequest): TransactionResult {
        val t = tm ?: throw notConnected()
        preflight("SALE")
        ensureSession()
        val bridge = listener ?: throw notConnected()
        val baseMinor = request.amountMinor.toLong()
        var tipMinor = (request.tipMinor ?: 0).toLong()
        var tipPercentX10: Int? = null

        // Tip flow B1: terminal-rendered menu before the card (scratchpad §7).
        if (request.promptForTip) {
            if (config.tipPresets.isEmpty()) {
                log("promptForTip requested but tipPresets is empty — proceeding without a tip prompt")
            } else {
                val options = ArrayList(config.tipPresets.map { "$it%" } + "No tip")
                val tipDeferred = CompletableDeferred<Int>()
                bridge.tipDeferred = tipDeferred
                log("presenting tip menu ${options} on the terminal ...")
                t.presentUserOptions("Add a tip?", options)
                val idx = try {
                    withTimeout(TimeUnit.SECONDS.toMillis(TIP_MENU_TIMEOUT_S)) { tipDeferred.await() }
                } catch (_: TimeoutCancellationException) {
                    bridge.tipDeferred = null
                    endSessionQuietly()
                    return PsdkMapping.mapCompletion(
                        requestId = request.envelope.requestId,
                        eventStatus = -11,
                        payment = null,
                        baseMinor = baseMinor, tipMinor = 0,
                        currency = request.currency, tipPercentX10 = null,
                        receiptsCached = false
                    ).copy(
                        state = TransactionState.CUSTOMER_TIMEOUT,
                        error = PathError(
                            code = PathErrorCode.CUSTOMER_TIMEOUT,
                            message = "Customer did not respond to tip prompt",
                            recoverable = true
                        )
                    )
                } finally {
                    bridge.tipDeferred = null
                }
                val pct = config.tipPresets.getOrNull(idx) ?: 0
                tipMinor = PsdkMapping.tipForPercent(baseMinor, pct)
                tipPercentX10 = if (pct > 0) pct * 10 else null
                log("tip choice: index $idx → ${pct}% → ${tipMinor}p")
            }
        }

        val totalMinor = baseMinor + tipMinor
        val totals = AmountTotals.create(false).apply {
            setSubtotal(PsdkMapping.minorToDecimal(baseMinor))
            if (tipMinor > 0) setGratuity(PsdkMapping.minorToDecimal(tipMinor))
            setTotal(PsdkMapping.minorToDecimal(totalMinor))
        }
        currentTotal = PsdkMapping.minorToDecimal(totalMinor)
        val item = Merchandise.create().apply {
            setName(request.envelope.merchantReference ?: "Path sale")
            setAmount(PsdkMapping.minorToDecimal(baseMinor))
        }
        t.basketManager?.addMerchandise(arrayListOf(item), totals)

        val p = Payment.create().apply {
            setRequestedAmounts(totals)
            setRequestedPaymentType(PaymentType.CREDIT)
            setRequestedCardPresentationMethods(presentationMethods())
        }
        bridge.lastTransactionMessage = null
        val deferred = CompletableDeferred<Pair<Int, Payment?>>()
        bridge.paymentDeferred = deferred
        log("SALE ${totalMinor}p started — awaiting card/host ...")
        t.startPayment(p)
        val result = awaitCompletion(deferred, bridge, request, baseMinor, tipMinor, tipPercentX10, linked = false)
        endSessionQuietly()
        maybeRecoverWedge(result, bridge)
        return result
    }

    override suspend fun refund(request: TransactionRequest): TransactionResult =
        runExclusive("REFUND") { runLinkedOp(request, isVoid = false) }

    override suspend fun voidTransaction(request: TransactionRequest): TransactionResult =
        runExclusive("VOID") { runLinkedOp(request, isVoid = true) }

    private suspend fun runLinkedOp(request: TransactionRequest, isVoid: Boolean): TransactionResult {
        val op = if (isVoid) "VOID" else "REFUND"
        val t = tm ?: throw notConnected()
        val originalId = request.originalTransactionId ?: throw PathError(
            code = PathErrorCode.VALIDATION,
            message = "$op requires originalTransactionId",
            recoverable = false
        )
        val link = linkStore.linkFor(originalId) ?: throw PathError(
            code = PathErrorCode.VALIDATION,
            message = "$op: no link token stored for transaction $originalId — " +
                "only sales approved through this backend can be ${if (isVoid) "voided" else "refunded"}",
            recoverable = false
        )
        preflight(op)
        ensureSession()
        val bridge = listener ?: throw notConnected()
        val baseMinor = if (isVoid) 0L else request.amountMinor.toLong()

        val p = Payment.create().apply {
            setAppSpecificData(link)
            if (!isVoid) {
                // Amounts are mandatory on linked refunds (and enable partials);
                // a void is a full reversal and must NOT carry an amount (g6).
                val totals = AmountTotals.create(false).apply {
                    setTotal(PsdkMapping.minorToDecimal(baseMinor))
                }
                currentTotal = PsdkMapping.minorToDecimal(baseMinor)
                setRequestedAmounts(totals)
                setRequestedPaymentType(PaymentType.CREDIT)
                setRequestedCardPresentationMethods(presentationMethods())
            }
        }
        bridge.lastTransactionMessage = null
        val deferred = CompletableDeferred<Pair<Int, Payment?>>()
        bridge.paymentDeferred = deferred
        bridge.linkedOpInFlight = true
        log("$op started (linked to $originalId) ...")
        if (isVoid) {
            // A void is a host-side reversal with no card tap, so the terminal shows nothing on its
            // own. Push a status message BEFORE processVoid — the only reliable window a POS message
            // appears ahead of the receipt (PSDK-harness gotcha 10). Mirrors the Windows adapter.
            showOnTerminal("VOID IN PROGRESS\nPlease wait")
            delay(2000)
            t.processVoid(p)
        } else {
            t.processRefund(p)
        }
        val result = try {
            awaitCompletion(deferred, bridge, request, baseMinor, 0, null, linked = true)
        } finally {
            bridge.linkedOpInFlight = false
        }
        endSessionQuietly()
        return result
    }

    /** Push a short status message to the terminal's customer display (best-effort). */
    private fun showOnTerminal(text: String, dt: DisplayType = DisplayType.DISPSTATUS) {
        try { tm?.presentCustomerContent2(text, ContentType.TEXT, 0, dt) }
        catch (e: Exception) { log("present on terminal: ${e.message}") }
    }

    private fun presentationMethods() = arrayListOf(
        PresentationMethod.CHIP, PresentationMethod.CTLS_CARD,
        PresentationMethod.MAG_STRIPE, PresentationMethod.KEYED
    )

    private suspend fun awaitCompletion(
        deferred: CompletableDeferred<Pair<Int, Payment?>>,
        bridge: Bridge,
        request: TransactionRequest,
        baseMinor: Long,
        tipMinor: Long,
        tipPercentX10: Int?,
        linked: Boolean
    ): TransactionResult {
        val (status, payment) = try {
            withTimeout(TimeUnit.SECONDS.toMillis(PAYMENT_TIMEOUT_S)) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            log("no completion within ${PAYMENT_TIMEOUT_S}s — aborting")
            try { tm?.abort() } catch (_: Exception) {}
            endSessionQuietly()
            throw PathError(
                code = PathErrorCode.TIMEOUT,
                message = "Terminal did not complete the transaction within ${PAYMENT_TIMEOUT_S}s",
                recoverable = true
            )
        } finally {
            bridge.paymentDeferred = null
        }

        // Cache receipts FIRST (they cannot be re-fetched), then build the result.
        var receiptsCached = false
        val txnId = payment?.transactionId
        if (payment != null && txnId != null) {
            PsdkMapping.mapReceipts(
                transactionId = txnId,
                requestId = request.envelope.requestId,
                payment = payment,
                totalMinor = baseMinor + tipMinor,
                tipMinor = tipMinor,
                currency = request.currency
            )?.let {
                receiptCache.put(it)
                receiptsCached = true
            }
        }

        val result = PsdkMapping.mapCompletion(
            requestId = request.envelope.requestId,
            eventStatus = status,
            payment = payment,
            baseMinor = baseMinor,
            tipMinor = tipMinor,
            currency = request.currency,
            tipPercentX10 = tipPercentX10,
            receiptsCached = receiptsCached
        )

        // Approved sales: persist the link token for later refund/void.
        if (!linked && result.state == TransactionState.APPROVED && txnId != null) {
            payment?.appSpecificData?.takeIf { it.isNotEmpty() }?.let {
                linkStore.saveLink(txnId, it)
                log("link token stored for $txnId")
            }
        }
        log("${if (linked) "linked op" else "sale"} result: ${result.state} txn=${txnId ?: "-"}")
        return result
    }

    /**
     * Gotcha 13: the VP100's display can wedge (dark, unwakeable) while the
     * payment engine reports healthy — the only signature is a cancel (-11)
     * at "Awaiting Payment Type Selection" with no card interaction. That is
     * indistinguishable from a customer simply ignoring the terminal, so the
     * mapped result is still returned to the caller; recovery (teardown →
     * re-init → login) runs in the background so the NEXT sale works either
     * way. Cheap, idempotent, never retries a payment.
     */
    private fun maybeRecoverWedge(result: TransactionResult, bridge: Bridge) {
        if (result.state != TransactionState.CANCELLED) return
        if (bridge.lastTransactionMessage?.contains(WEDGE_SIGNATURE, ignoreCase = true) != true) return
        log("possible display wedge (cancelled at '$WEDGE_SIGNATURE') — background reconnect cycle")
        CoroutineScope(psdkDispatcher).launch {
            try {
                teardownInternal()
                delay(1000)
                var st = initialize()
                if (st == -2 || st == -8) { teardownInternal(); delay(1500); st = initialize() }
                if (st == StatusCode.SUCCESS) {
                    tm = sdk?.transactionManager
                    login()
                    _isConnected = true
                    log("wedge recovery complete — terminal ready")
                } else {
                    log("wedge recovery failed (init $st) — manual reconnect needed")
                    onHardwareDisconnect?.invoke()
                }
            } catch (e: Exception) {
                log("wedge recovery failed: ${e.message}")
                onHardwareDisconnect?.invoke()
            }
        }
    }

    private suspend fun <T> runExclusive(op: String, block: suspend () -> T): T {
        if (!txnMutex.tryLock()) throw PathError(
            code = PathErrorCode.TERMINAL_BUSY,
            message = "$op rejected: another transaction is in flight",
            recoverable = true
        )
        try {
            return withContext(psdkDispatcher) { block() }
        } finally {
            txnMutex.unlock()
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    override suspend fun cancelActiveTransaction(): Unit = withContext(psdkDispatcher) {
        log("abort requested")
        tm?.abort()
        Unit
    }

    override suspend fun getReceiptData(transactionId: String): ReceiptData {
        // Receipts arrive once, with completion — served from the cache.
        return receiptCache.get(transactionId) ?: throw PathError(
            code = PathErrorCode.UNSUPPORTED_OPERATION,
            message = "No cached receipt for $transactionId — Verifone receipts are only " +
                "available at completion time (the adapter caches the last 20)",
            recoverable = false
        )
    }

    override suspend fun getCapabilities(): DeviceCapabilities = DeviceCapabilities(
        commands = listOf("Sale", "Refund", "Void", "Cancel", "GetReceipt"),
        nfc = true,
        display = true,
        receiptPrint = true
    )

    override suspend fun getDeviceInfo(): DeviceInfo = withContext(psdkDispatcher) {
        val d = sdk?.deviceInformation ?: throw notConnected()
        DeviceInfo(
            model = d.model ?: "Verifone",
            firmware = d.paymentAppVersion ?: "unknown",
            serial = d.serialNumber,
            protocolVersion = d.paymentProtocol ?: "NEXO"
        )
    }

    override suspend fun getTransactionStatus(requestId: String): TransactionResult {
        throw PathError(
            code = PathErrorCode.UNSUPPORTED_OPERATION,
            message = "GetTransactionStatus is not supported on the Verifone backend (v1)",
            recoverable = false
        )
    }
}
