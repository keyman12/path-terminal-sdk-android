package tech.path2ai.sdk

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    override fun toString(): String = when (this) {
        is Idle -> "idle"
        is Scanning -> "scanning"
        is Connecting -> "connecting"
        is Connected -> "connected"
        is Disconnected -> "disconnected"
        is Error -> "error($message)"
    }
}
