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
        val decoded = com.ryans.nostrshare.NostrUtils.Bech32.decode(this)
        decoded.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        this 
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

enum class PostKind(val kind: Int, val label: String) {
    NOTE(1, "Note"), 
    QUOTE(1, "Quote"), // Custom internal type for Kind 1 with q tags
    HIGHLIGHT(9802, "Highlight"),
    REPOST(6, "Repost"),
    MEDIA(0, "Media"),
    FILE_METADATA(1063, "File Meta"),
    ARTICLE(30023, "Article")
}

internal object Nip55Protocol {
    const val URI_SCHEME = "nostrsigner"

    const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    const val TYPE_SIGN_EVENT = "sign_event"
    const val TYPE_SIGN_EVENTS = "sign_events"
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
    const val RESULT_SIGNATURES = "signatures"

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
