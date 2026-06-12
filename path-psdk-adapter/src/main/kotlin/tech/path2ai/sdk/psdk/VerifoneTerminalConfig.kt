package tech.path2ai.sdk.psdk

/**
 * Configuration for a Verifone terminal reached over TCP/IP via the Verifone
 * Payment SDK (PSDK).
 *
 * @param host           Terminal IP/hostname (the PSDK defaults its own ports: 9600/9601).
 * @param username       Login credential. The test profile accepts user/password123/shift123.
 * @param password       Login credential.
 * @param shift          Login shift identifier.
 * @param refundPassword Refunds make the terminal ask the POS for a manager/refund
 *                       PASSWORD mid-transaction; the adapter auto-answers with this.
 *                       The test terminal accepts an empty string — production
 *                       estates may enforce a real one.
 * @param tipPresets     Percentages offered (in order) when a sale request has
 *                       promptForTip set. The terminal renders the menu; the adapter
 *                       appends "No tip" and computes the gratuity from the choice.
 *                       Matches the Path emulator's presets by default. Empty list =
 *                       tip prompting unsupported (promptForTip is ignored with a log).
 * @param currency       ISO 4217 currency for Decimal scaling (exponent 2 assumed).
 */
data class VerifoneTerminalConfig(
    val host: String,
    val username: String = "user",
    val password: String = "password123",
    val shift: String = "shift123",
    val refundPassword: String = "",
    val tipPresets: List<Int> = listOf(10, 15, 20),
    val currency: String = "GBP"
)
