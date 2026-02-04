package com.ryans.nostrshare.utils

object Bech32 {
    private const val ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(bech32: String): Pair<String, ByteArray> {
        var lower = false
        var upper = false
        for (c in bech32) {
            if (c in 'a'..'z') lower = true
            else if (c in 'A'..'Z') upper = true
            else if (c !in '0'..'9') throw IllegalArgumentException("Invalid character")
        }
        if (lower && upper) throw IllegalArgumentException("Mixed case")
        
        val pos = bech32.lastIndexOf('1')
        if (pos < 1) throw IllegalArgumentException("Missing separator")
        if (pos + 7 > bech32.length) throw IllegalArgumentException("Too short")
        
        val hrp = bech32.substring(0, pos).lowercase()
        val data = bech32.substring(pos + 1).map { 
            val d = ALPHABET.indexOf(it.lowercaseChar())
            if (d == -1) throw IllegalArgumentException("Invalid data char")
            d.toByte()
        }.toByteArray()

        if (!verifyChecksum(hrp, data)) throw IllegalArgumentException("Invalid checksum")
        
        return hrp to convertBits(data.dropLast(6).toByteArray(), 5, 8, false)
    }

    private fun verifyChecksum(hrp: String, data: ByteArray): Boolean {
        return polymod(expandHrp(hrp) + data) == 1
    }

    private fun expandHrp(hrp: String): ByteArray {
        val ret = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            ret[i] = (hrp[i].code shr 5).toByte()
            ret[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
        }
        ret[hrp.length] = 0
        return ret
    }

    private fun polymod(values: ByteArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v.toInt()
            if ((b and 1) != 0) chk = chk xor 0x3b6a57b2
            if ((b and 2) != 0) chk = chk xor 0x26508e6d
            if ((b and 4) != 0) chk = chk xor 0x1ea119fa
            if ((b and 8) != 0) chk = chk xor 0x3d4233dd
            if ((b and 16) != 0) chk = chk xor 0x2a1462b3
        }
        return chk
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val ret = java.io.ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            val v = value.toInt() and 0xff
            if ((v shr fromBits) != 0) throw IllegalArgumentException("Input value exists range")
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.write((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.write((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
           // throw IllegalArgumentException("Invalid padding")
        }
        return ret.toByteArray()
    }
    
    fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
