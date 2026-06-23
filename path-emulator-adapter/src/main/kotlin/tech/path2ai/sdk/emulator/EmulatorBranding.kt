package tech.path2ai.sdk.emulator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.path2ai.sdk.core.CustomerDisplayContent
import java.util.zip.CRC32

/**
 * Customer-display branding for the Pico emulator — the loopback twin of the
 * Verifone path. The real VP100 accepts HTML + a base64 PNG; the Pico can only
 * blit raw RGB565, so this helper converts the logo to the Pico's native format
 * and drives the chunked `SetIdleBranding` wire upload (begin → data* → end, or
 * clear). Shared by the BLE and Wi-Fi/TCP emulator adapters.
 *
 * Hash-gated: the emulator reports the opaque hash of its current logo in
 * GetDeviceInfo, so an unchanged logo is never re-sent (the slow part on BLE).
 */
internal object EmulatorBranding {

    // Fit the logo within this box. The Pico display is 320x240 and centres the
    // image; leaving a margin keeps a caption visible beneath it.
    private const val MAX_W = 240
    private const val MAX_H = 160

    // The logo is sent run-length-encoded (RLE): the customer logo is mostly
    // flat colour (e.g. a white field), so this collapses ~76 KB of RGB565 to a
    // couple of KB. Each RLE entry is 4 bytes [count_hi,count_lo,px_hi,px_lo];
    // we chunk on whole-entry (multiple-of-4) boundaries so the firmware can
    // decode each chunk independently. 256 RLE bytes/chunk → ~344-char base64 →
    // a wire message under 512 B (keeps the emulator's receive buffer happy).
    private const val CHUNK_RLE_BYTES = 256

    class Prepared(
        val rgb565: ByteArray,
        val width: Int,
        val height: Int,
        val hash: String,
        val caption: String?,
    )

    /**
     * Apply (or clear) the branding over [send], which fires one wire command
     * and returns the emulator's result JSON. [currentHash] is the emulator's
     * current branding hash (from GetDeviceInfo) for hash-gating.
     */
    suspend fun apply(
        content: CustomerDisplayContent?,
        currentHash: String?,
        send: suspend (cmd: String, args: Map<String, Any?>) -> String,
    ) {
        if (content == null) {
            // Only bother clearing if something is actually shown.
            if (!currentHash.isNullOrEmpty()) {
                checkOk(send("SetIdleBranding", mapOf("op" to "clear")))
            }
            return
        }
        val p = prepare(content) ?: return
        if (p.hash == currentHash) return // already showing this exact logo

        val rle = rleEncode(p.rgb565)
        checkOk(
            send(
                "SetIdleBranding",
                mapOf(
                    "op" to "begin", "hash" to p.hash, "w" to p.width, "h" to p.height,
                    "enc" to "rle", "bytes" to rle.size, "raw" to p.rgb565.size,
                    "caption" to (p.caption ?: "")
                )
            )
        )
        var seq = 0
        var off = 0
        while (off < rle.size) {
            val end = minOf(off + CHUNK_RLE_BYTES, rle.size)
            val b64 = Base64.encodeToString(rle.copyOfRange(off, end), Base64.NO_WRAP)
            checkOk(send("SetIdleBranding", mapOf("op" to "data", "seq" to seq, "b64" to b64)))
            seq++
            off = end
        }
        checkOk(send("SetIdleBranding", mapOf("op" to "end")))
    }

    /** Pull `branding_hash` out of a GetDeviceInfo result (null when absent). */
    fun parseBrandingHash(deviceInfoJson: String): String? = try {
        Json.parseToJsonElement(deviceInfoJson).jsonObject["branding_hash"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    /** Decode → scale-to-fit → big-endian RGB565 (matches the Pico's convert_logo.py). */
    fun prepare(content: CustomerDisplayContent): Prepared? {
        val src = BitmapFactory.decodeByteArray(content.imageBytes, 0, content.imageBytes.size) ?: return null
        val fitted = fit(src, MAX_W, MAX_H)
        val w = fitted.width
        val h = fitted.height
        val pixels = IntArray(w * h)
        fitted.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 2)
        var o = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            // The Pico blits opaque RGB565 — composite any transparency over
            // white so a logo on a transparent background doesn't become a black
            // rectangle (a=0 -> white; a=255 -> unchanged).
            if (a != 0xFF) {
                val inv = 255 - a
                r = (r * a + 255 * inv) / 255
                g = (g * a + 255 * inv) / 255
                b = (b * a + 255 * inv) / 255
            }
            val v = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)
            out[o++] = ((v shr 8) and 0xFF).toByte() // high byte first (big-endian)
            out[o++] = (v and 0xFF).toByte()
        }
        if (fitted !== src) fitted.recycle()
        src.recycle()
        val caption = content.caption?.takeIf { it.isNotBlank() }?.let { sanitizeCaption(it) }
        return Prepared(out, w, h, hashOf(out, caption), caption)
    }

    /**
     * Run-length-encode big-endian RGB565 into 4-byte entries
     * [count_hi, count_lo, px_hi, px_lo] (1..65535 repeats of one pixel). Logos
     * are mostly flat colour, so this is a large reduction; the firmware decodes
     * it back to raw RGB565 on receipt.
     */
    private fun rleEncode(rgb565: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(rgb565.size / 8 + 16)
        val n = rgb565.size / 2
        var i = 0
        while (i < n) {
            val hi = rgb565[i * 2]
            val lo = rgb565[i * 2 + 1]
            var run = 1
            while (i + run < n && run < 65535 &&
                rgb565[(i + run) * 2] == hi && rgb565[(i + run) * 2 + 1] == lo
            ) run++
            out.write((run ushr 8) and 0xFF)
            out.write(run and 0xFF)
            out.write(hi.toInt())
            out.write(lo.toInt())
            i += run
        }
        return out.toByteArray()
    }

    /** Downscale-to-fit keeping aspect; never upscales. */
    private fun fit(bmp: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxW && h <= maxH) return bmp
        val ratio = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        return Bitmap.createScaledBitmap(
            bmp, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true
        )
    }

    /** Stable opaque token for hash-gating (the emulator stores it verbatim). */
    private fun hashOf(rgb565: ByteArray, caption: String?): String {
        val crc = CRC32()
        crc.update(rgb565)
        caption?.let { crc.update(it.toByteArray(Charsets.UTF_8)) }
        return "%08x%05x".format(crc.value, rgb565.size)
    }

    /**
     * The caption rides in a JSON string arg that the wire builder does NOT
     * escape, so strip anything that would break framing/JSON and cap length.
     */
    private fun sanitizeCaption(raw: String): String =
        raw.replace("\\", "").replace("\"", "'")
            .replace("\n", " ").replace("\r", " ")
            .filter { it.code >= 0x20 }
            .trim()
            .take(32)

    private fun checkOk(resultJson: String) {
        val obj = try {
            Json.parseToJsonElement(resultJson).jsonObject
        } catch (_: Exception) {
            return // unparseable — let the caller's timeout/connection handling deal with it
        }
        val status = obj["status"]?.jsonPrimitive?.contentOrNull
        if (status == "error") {
            val msg = obj["error"]?.jsonPrimitive?.contentOrNull ?: "SetIdleBranding rejected"
            throw IllegalStateException(msg)
        }
    }
}
