package tech.path2ai.sdk.core

/**
 * Branding to show on a terminal's customer-facing display while it is idle —
 * typically a merchant logo shown on connect and re-shown between transactions
 * ("attract mode").
 *
 * Device-agnostic: the adapter renders [imageBytes] (a PNG or JPEG, already
 * sized for the customer screen) however its hardware allows, with an optional
 * [caption] beneath it. Backends without a customer display (the Pico emulator,
 * the mock) treat setting this as a no-op.
 *
 * Proven live on the Verifone VP100 (Path-PSDK-TestHarnesses gotcha 11: an HTML
 * page with an embedded base64 image renders on the customer screen). Keep the
 * image small — the VP100 customer screen is low-resolution and the whole HTML
 * payload (image included) should stay well under ~64 KB.
 */
class CustomerDisplayContent(
    /** Raw PNG/JPEG bytes of the logo, pre-sized to fit the customer screen. */
    val imageBytes: ByteArray,
    /** Optional line of text shown beneath the image (e.g. the store name). */
    val caption: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomerDisplayContent) return false
        return imageBytes.contentEquals(other.imageBytes) && caption == other.caption
    }

    override fun hashCode(): Int = 31 * imageBytes.contentHashCode() + (caption?.hashCode() ?: 0)
}
