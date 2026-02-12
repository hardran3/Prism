package com.ryans.nostrshare

import java.util.regex.Pattern

object NostrUtils {
    data class NostrEntity(
        val type: String,
        val id: String,
        val bech32: String,
        val author: String? = null,
        val relays: List<String> = emptyList(),
        val kind: Int? = null
    )

    private val ENTITY_PATTERN = Pattern.compile("(nevent1|note1|npub1|nprofile1|naddr1)([a-z0-9]+)", Pattern.CASE_INSENSITIVE)

    fun findNostrEntity(input: String): NostrEntity? {
        val matcher = ENTITY_PATTERN.matcher(input)
        if (matcher.find()) {
            val prefix = matcher.group(1)?.lowercase() ?: return null
            val data = matcher.group(2) ?: return null
            val bech32 = prefix + data
            
            try {
                val decoded = Bech32.decode(bech32)
                return when (prefix) {
                    "note1" -> NostrEntity("note", decoded.toHex(), bech32)
                    "npub1" -> NostrEntity("npub", decoded.toHex(), bech32)
                    "nevent1" -> parseNevent(decoded, bech32)
                    "nprofile1" -> parseNprofile(decoded, bech32)
                    "naddr1" -> parseNaddr(decoded, bech32)
                    else -> null
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    fun pubkeyToNpub(pubkey: String): String {
        return try {
            Bech32.encode("npub", pubkey.hexToBytes())
        } catch (e: Exception) {
            pubkey
        }
    }

    fun eventIdToNote(eventId: String): String {
        return try {
            Bech32.encode("note", eventId.hexToBytes())
        } catch (e: Exception) {
            eventId
        }
    }

    fun pubkeyToNprofile(pubkey: String): String {
        return try {
            val bytes = pubkey.hexToBytes()
            // TLV: type 0 (pubkey), length 32
            val tlv = mutableListOf<Byte>()
            tlv.add(0.toByte())
            tlv.add(32.toByte())
            bytes.forEach { tlv.add(it) }
            
            // Add indexer relay as a hint (optional but good)
            val relay = "wss://indexer.coracle.social".toByteArray(Charsets.UTF_8)
            tlv.add(1.toByte())
            tlv.add(relay.size.toByte())
            relay.forEach { tlv.add(it) }
            
            Bech32.encode("nprofile", tlv.toByteArray())
        } catch (e: Exception) {
            pubkey
        }
    }

    fun getKindLabel(kind: Int, content: String = ""): String {
        return when (kind) {
            1 -> "Text Note"
            9802 -> "Highlight"
            6 -> if (content.isBlank()) "Repost" else "Quote Post"
            0, 20, 22, 1063 -> "Media Note"
            else -> "Kind $kind"
        }
    }

    private fun parseNprofile(bytes: ByteArray, bech32: String): NostrEntity? {
        var pubkey = ""
        val relays = mutableListOf<String>()
        
        var i = 0
        while (i < bytes.size) {
            val type = bytes[i].toInt()
            if (i + 1 >= bytes.size) break
            val length = bytes[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > bytes.size) break
            
            val value = bytes.sliceArray(i until i + length)
            when (type) {
                0 -> pubkey = value.toHex()
                1 -> relays.add(String(value, Charsets.UTF_8))
            }
            i += length
        }
        
        return if (pubkey.isNotEmpty()) {
            NostrEntity("nprofile", pubkey, bech32, pubkey, relays)
        } else null
    }

    private fun parseNevent(bytes: ByteArray, bech32: String): NostrEntity? {
        var eventId = ""
        val relays = mutableListOf<String>()
        var author: String? = null
        var kind: Int? = null
        
        var i = 0
        while (i < bytes.size) {
            val type = bytes[i].toInt()
            if (i + 1 >= bytes.size) break
            val length = bytes[i + 1].toInt()
            i += 2
            if (i + length > bytes.size) break
            
            val value = bytes.sliceArray(i until i + length)
            when (type) {
                0 -> eventId = value.toHex()
                1 -> relays.add(String(value, Charsets.UTF_8))
                2 -> author = value.toHex()
                3 -> {
                    if (length == 4) {
                        kind = ((value[0].toInt() and 0xFF) shl 24) or
                               ((value[1].toInt() and 0xFF) shl 16) or
                               ((value[2].toInt() and 0xFF) shl 8) or
                               (value[3].toInt() and 0xFF)
                    }
                }
            }
            i += length
        }
        
        return if (eventId.isNotEmpty()) {
            NostrEntity("nevent", eventId, bech32, author, relays, kind)
        } else null
    }

    private fun parseNaddr(bytes: ByteArray, bech32: String): NostrEntity? {
        var identifier = ""
        val relays = mutableListOf<String>()
        var author: String? = null
        var kind: Int? = null
        
        var i = 0
        while (i < bytes.size) {
            val type = bytes[i].toInt()
            if (i + 1 >= bytes.size) break
            val length = bytes[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > bytes.size) break
            
            val value = bytes.sliceArray(i until i + length)
            when (type) {
                0 -> identifier = String(value, Charsets.UTF_8)
                1 -> relays.add(String(value, Charsets.UTF_8))
                2 -> author = value.toHex()
                3 -> {
                    if (length == 4) {
                        kind = ((value[0].toInt() and 0xFF) shl 24) or
                               ((value[1].toInt() and 0xFF) shl 16) or
                               ((value[2].toInt() and 0xFF) shl 8) or
                               (value[3].toInt() and 0xFF)
                    }
                }
            }
            i += length
        }
        
        return if (identifier.isNotEmpty() && author != null && kind != null) {
            NostrEntity("naddr", identifier, bech32, author, relays, kind)
        } else null
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // Bech32 implementation (Moved from Models.kt and refined)
    object Bech32 {
        private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        fun decode(bech32: String): ByteArray {
            val lower = bech32.lowercase()
            val pos = lower.lastIndexOf('1')
            if (pos < 1) throw IllegalArgumentException("Invalid bech32: no separator")

            val hrp = lower.substring(0, pos)
            val data = lower.substring(pos + 1)
            val values = data.map { CHARSET.indexOf(it) }
            if (values.any { it < 0 }) throw IllegalArgumentException("Invalid bech32: bad character")

            if (!verifyChecksum(hrp, values)) throw IllegalArgumentException("Invalid bech32: bad checksum")

            val dataValues = values.dropLast(6)
            return convertBits(dataValues, 5, 8, false)
        }

        fun encode(hrp: String, data: ByteArray): String {
            val values = convertBits(data.toList().map { it.toInt() and 0xFF }, 8, 5, true).map { it.toInt() }
            val checksum = createChecksum(hrp, values)
            val combined = values + checksum
            return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
        }

        private fun createChecksum(hrp: String, values: List<Int>): List<Int> {
            val expand = hrpExpand(hrp)
            val mod = polymod(expand + values + listOf(0, 0, 0, 0, 0, 0)) xor 1
            return (0..5).map { (mod shr (5 * (5 - it))) and 31 }
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
}
