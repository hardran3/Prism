package com.ryans.nostrshare.nip55

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * Information about an installed NIP-55 signer application.
 */
data class SignerInfo(
    val packageName: String,
    val appName: String
)

/**
 * Permission request for a specific operation type.
 */
data class Permission(
    val type: String,
    val kind: Int? = null
) {
    companion object {
        fun signEvent(kind: Int? = null) = Permission("sign_event", kind)
        fun nip04Encrypt() = Permission("nip04_encrypt")
        fun nip04Decrypt() = Permission("nip04_decrypt")
        fun nip44Encrypt() = Permission("nip44_encrypt")
        fun nip44Decrypt() = Permission("nip44_decrypt")
        fun decryptZapEvent() = Permission("decrypt_zap_event")
    }

    internal fun toJson(): String {
        val escapedType = type.escapeJson()
        return if (kind != null) {
            """{"type":"$escapedType","kind":$kind}"""
        } else {
            """{"type":"$escapedType"}"""
        }
    }
}

private fun String.escapeJson(): String {
    return this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

internal fun String.toHex(): String {
    return this.toByteArray(Charsets.UTF_8).joinToString("") {
        "%02x".format(it.toInt() and 0xFF)
    }
}

/**
 * Converts a hex string back to plaintext.
 */
internal fun String.hexToString(): String {
    if (this.length % 2 != 0) return this
    if (!this.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return this

    return try {
        this.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    } catch (e: Exception) {
        this
    }
}

/**
 * Converts an npub (bech32-encoded public key) to hex format.
 */
internal fun String.npubToHex(): String {
    if (!this.startsWith("npub1")) {
        return this
    }

    return try {
        val decoded = Bech32.decode(this)
        decoded.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        this 
    }
}

/**
 * Minimal Bech32 decoder for npub strings.
 */
private object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun decode(bech32: String): ByteArray {
        val lower = bech32.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1) { "Invalid bech32: no separator" }

        val hrp = lower.substring(0, pos)
        val data = lower.substring(pos + 1)
        val values = data.map { CHARSET.indexOf(it) }
        require(values.all { it >= 0 }) { "Invalid bech32: bad character" }

        require(verifyChecksum(hrp, values)) { "Invalid bech32: bad checksum" }

        val dataValues = values.dropLast(6)
        return convertBits(dataValues, 5, 8, false)
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean {
        return polymod(hrpExpand(hrp) + values) == 1
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val result = mutableListOf<Int>()
        for (c in hrp) {
            result.add(c.code shr 5)
        }
        result.add(0)
        for (c in hrp) {
            result.add(c.code and 31)
        }
        return result
    }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk
    }

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxv).toByte())
        }

        return result.toByteArray()
    }
}

internal fun List<Permission>.toJsonArray(): String {
    return "[${joinToString(",") { it.toJson() }}]"
}

data class PublicKeyResult(
    val pubkey: String,
    val packageName: String?
)

data class SignEventResult(
    val signature: String?,
    val signedEventJson: String?,
    val id: String?
)

data class EncryptResult(
    val ciphertext: String,
    val id: String?
)

data class DecryptResult(
    val plaintext: String,
    val id: String?
)

internal object Nip55Protocol {
    const val URI_SCHEME = "nostrsigner"

    const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    const val TYPE_SIGN_EVENT = "sign_event"
    const val TYPE_NIP04_ENCRYPT = "nip04_encrypt"
    const val TYPE_NIP04_DECRYPT = "nip04_decrypt"
    const val TYPE_NIP44_ENCRYPT = "nip44_encrypt"
    const val TYPE_NIP44_DECRYPT = "nip44_decrypt"
    const val TYPE_DECRYPT_ZAP_EVENT = "decrypt_zap_event"

    const val EXTRA_TYPE = "type"
    const val EXTRA_CURRENT_USER = "current_user"
    const val EXTRA_PUBKEY = "pubkey"
    const val EXTRA_ID = "id"
    const val EXTRA_PERMISSIONS = "permissions"

    const val RESULT = "result"
    const val RESULT_PACKAGE = "package"
    const val RESULT_EVENT = "event"
    const val RESULT_ID = "id"

    const val RESULT_SIGNATURE = "signature"

    fun createIntent(uriData: String = ""): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("$URI_SCHEME:$uriData")).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    fun createDiscoveryIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("$URI_SCHEME:"))
    }
}

internal fun ResolveInfo.toSignerInfo(appName: String): SignerInfo {
    return SignerInfo(
        packageName = activityInfo.packageName,
        appName = appName
    )
}
