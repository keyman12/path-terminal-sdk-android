package tech.path2ai.sdk.diagnostics

import tech.path2ai.sdk.core.SupportBundleSnapshotV1

object PathDiagnostics {
    const val DIAGNOSTICS_VERSION = "0.1.0"

    /** Pretty-printed JSON for email or clipboard. */
    fun formatSupportBundle(snapshot: SupportBundleSnapshotV1): String =
        SupportBundleSnapshotV1.encodePrettyString(snapshot)
}
