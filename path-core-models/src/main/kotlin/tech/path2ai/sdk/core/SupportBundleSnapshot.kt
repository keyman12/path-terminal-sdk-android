package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SupportBundleSnapshotV1(
    @SerialName("bundle_version") val bundleVersion: String = "1",
    @SerialName("generated_at_utc") val generatedAtUtc: String,
    val integration: String,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    @SerialName("protocol_version") val protocolVersion: String? = null,
    @SerialName("connection_state") val connectionState: String,
    @SerialName("is_ready") val isReady: Boolean,
    @SerialName("is_bluetooth_powered_on") val isBluetoothPoweredOn: Boolean,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("log_line_count") val logLineCount: Int,
    @SerialName("recent_log_lines") val recentLogLines: List<String>,
    @SerialName("transaction_log_count") val transactionLogCount: Int
) {
    companion object {
        private val jsonCompact = Json { encodeDefaults = true }
        private val jsonPretty = Json { encodeDefaults = true; prettyPrint = true }

        fun encodeJson(snapshot: SupportBundleSnapshotV1): String =
            jsonCompact.encodeToString(serializer(), snapshot)

        fun encodePrettyString(snapshot: SupportBundleSnapshotV1): String =
            jsonPretty.encodeToString(serializer(), snapshot)
    }
}
