package com.ryans.nostrshare.utils

import java.lang.StringBuilder

object UnicodeStylizer {

    enum class Style(val preview: String) {
        SERIF_BOLD("𝐁𝐨𝐥𝐝"),
        SERIF_ITALIC("𝘐𝘵𝘢𝘭𝘪𝘤"),
        SERIF_BOLD_ITALIC("𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘"),
        SANS_BOLD("𝗕𝗼𝗹𝗱"),
        SANS_ITALIC("𝘐𝘵𝘢𝘭𝘪𝘤"),
        SANS_BOLD_ITALIC("𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘"),
        SCRIPT("𝒮𝒸𝓇𝒾𝓅𝓉"),
        SCRIPT_BOLD("𝓢𝓬𝓻𝓲𝓹𝓽"),
        FRAKTUR("𝔉𝔯𝔞𝔨𝔱𝔲𝔯"),
        FRAKTUR_BOLD("𝕱𝖗𝖆𝖐𝖙𝖚𝖗"),
        MONOSPACE("𝙼𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎"),
        DOUBLE_STRUCK("𝔻𝕠𝕦𝕓𝕝𝕖"),
        CIRCLED("Ⓒⓘⓡⓒⓛⓔ"),
        SMALL_CAPS("Sᴍᴀʟʟ Cᴀᴘs"),
        STRIKETHROUGH("S̶t̶r̶i̶k̶e̶"),
        UNDERLINE("U͟n͟d͟e͟r͟l͟i͟n͟e͟");
    }

    fun stylize(text: String, style: Style): String {
        if (text.isEmpty()) return ""

        return when (style) {
            Style.SERIF_BOLD -> transform(text, SERIF_BOLD_MAP)
            Style.SERIF_ITALIC -> transform(text, SERIF_ITALIC_MAP)
            Style.SERIF_BOLD_ITALIC -> transform(text, SERIF_BOLD_ITALIC_MAP)
            Style.SANS_BOLD -> transform(text, SANS_BOLD_MAP)
            Style.SANS_ITALIC -> transform(text, SANS_ITALIC_MAP)
            Style.SANS_BOLD_ITALIC -> transform(text, SANS_BOLD_ITALIC_MAP)
            Style.SCRIPT -> transform(text, SCRIPT_MAP)
            Style.SCRIPT_BOLD -> transform(text, SCRIPT_BOLD_MAP)
            Style.FRAKTUR -> transform(text, FRAKTUR_MAP)
            Style.FRAKTUR_BOLD -> transform(text, FRAKTUR_BOLD_MAP)
            Style.MONOSPACE -> transform(text, MONOSPACE_MAP)
            Style.DOUBLE_STRUCK -> transform(text, DOUBLE_STRUCK_MAP)
            Style.CIRCLED -> transform(text, CIRCLED_MAP)
            Style.SMALL_CAPS -> transform(text, SMALL_CAPS_MAP)
            Style.STRIKETHROUGH -> applyCombining(text, 0x0336)
            Style.UNDERLINE -> applyCombining(text, 0x0332)
        }
    }

    fun normalize(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val remaining = text.substring(i)
            // Prioritize longer matches (surrogate pairs)
            val match = REVERSE_MAP.keys.find { remaining.startsWith(it) }

            if (match != null) {
                sb.append(REVERSE_MAP[match])
                i += match.length
            } else {
                val char = text[i]
                // Filter out combining characters
                if (char.code != 0x0336 && char.code != 0x0332) {
                    sb.append(char)
                }
                i++
            }
        }
        return sb.toString()
    }


    private fun applyCombining(text: String, combiningChar: Int): String {
        val sb = StringBuilder()
        text.codePoints().forEach { codePoint ->
            sb.append(Character.toChars(codePoint))
            if (Character.isLetterOrDigit(codePoint)) {
                sb.append(Character.toChars(combiningChar))
            }
        }
        return sb.toString()
    }

    private fun transform(text: String, map: Map<Int, String>): String {
        val sb = StringBuilder()
        text.codePoints().forEach { codePoint ->
            sb.append(map[codePoint] ?: String(Character.toChars(codePoint)))
        }
        return sb.toString()
    }

    private val SERIF_BOLD_MAP = mapOf(
        'A' to "𝐀", 'B' to "𝐁", 'C' to "𝐂", 'D' to "𝐃", 'E' to "𝐄", 'F' to "𝐅", 'G' to "𝐆", 'H' to "𝐇", 'I' to "𝐈", 'J' to "𝐉", 'K' to "𝐊", 'L' to "𝐋", 'M' to "𝐌", 'N' to "𝐍", 'O' to "𝐎", 'P' to "𝐏", 'Q' to "𝐐", 'R' to "𝐑", 'S' to "𝐒", 'T' to "𝐓", 'U' to "𝐔", 'V' to "𝐕", 'W' to "𝐖", 'X' to "𝐗", 'Y' to "𝐘", 'Z' to "𝐙",
        'a' to "𝐚", 'b' to "𝐛", 'c' to "𝐜", 'd' to "𝐝", 'e' to "𝐞", 'f' to "𝐟", 'g' to "𝐠", 'h' to "𝐡", 'i' to "𝐢", 'j' to "𝐣", 'k' to "𝐤", 'l' to "𝐥", 'm' to "𝐦", 'n' to "𝐧", 'o' to "𝐨", 'p' to "𝐩", 'q' to "𝐪", 'r' to "𝐫", 's' to "𝐬", 't' to "𝐭", 'u' to "𝐮", 'v' to "𝐯", 'w' to "𝐰", 'x' to "𝐱", 'y' to "𝐲", 'z' to "𝐳",
        '0' to "𝟎", '1' to "𝟏", '2' to "𝟐", '3' to "𝟑", '4' to "𝟒", '5' to "𝟓", '6' to "𝟔", '7' to "𝟕", '8' to "𝟖", '9' to "𝟗"
    ).mapKeys { it.key.code }

    private val SERIF_ITALIC_MAP = mapOf(
        'A' to "𝐴", 'B' to "𝐵", 'C' to "𝐶", 'D' to "𝐷", 'E' to "𝐸", 'F' to "𝐹", 'G' to "𝐺", 'H' to "𝐻", 'I' to "𝐼", 'J' to "𝐽", 'K' to "𝐾", 'L' to "𝐿", 'M' to "𝑀", 'N' to "𝑁", 'O' to "𝑂", 'P' to "𝑃", 'Q' to "𝑄", 'R' to "𝑅", 'S' to "𝑆", 'T' to "𝑇", 'U' to "𝑈", 'V' to "𝑉", 'W' to "𝑊", 'X' to "𝑋", 'Y' to "𝑌", 'Z' to "𝑍",
        'a' to "𝑎", 'b' to "𝑏", 'c' to "𝑐", 'd' to "𝑑", 'e' to "𝑒", 'f' to "𝑓", 'g' to "𝑔", 'h' to "ℎ", 'i' to "𝑖", 'j' to "𝑗", 'k' to "𝑘", 'l' to "𝑙", 'm' to "𝑚", 'n' to "𝑛", 'o' to "𝑜", 'p' to "𝑝", 'q' to "𝑞", 'r' to "𝑟", 's' to "𝑠", 't' to "𝑡", 'u' to "𝑢", 'v' to "𝑣", 'w' to "𝑤", 'x' to "𝑥", 'y' to "𝑦", 'z' to "𝑧"
    ).mapKeys { it.key.code }

    private val SERIF_BOLD_ITALIC_MAP = mapOf(
        'A' to "𝑨", 'B' to "𝑩", 'C' to "𝑪", 'D' to "𝑫", 'E' to "𝑬", 'F' to "𝑭", 'G' to "𝑮", 'H' to "𝑯", 'I' to "𝑰", 'J' to "𝑱", 'K' to "𝑲", 'L' to "𝑳", 'M' to "𝑴", 'N' to "𝑵", 'O' to "𝑶", 'P' to "𝑷", 'Q' to "𝑸", 'R' to "𝑹", 'S' to "𝑺", 'T' to "𝑻", 'U' to "𝑼", 'V' to "𝑽", 'W' to "𝑾", 'X' to "𝑿", 'Y' to "𝒀", 'Z' to "𝒁",
        'a' to "𝒂", 'b' to "𝒃", 'c' to "𝒄", 'd' to "𝒅", 'e' to "𝒆", 'f' to "𝒇", 'g' to "𝒈", 'h' to "𝒉", 'i' to "𝒊", 'j' to "𝒋", 'k' to "𝒌", 'l' to "𝒍", 'm' to "𝒎", 'n' to "𝒏", 'o' to "𝒐", 'p' to "𝒑", 'q' to "𝒒", 'r' to "𝒓", 's' to "𝒔", 't' to "𝒕", 'u' to "𝒖", 'v' to "𝒗", 'w' to "𝒘", 'x' to "𝒙", 'y' to "𝒚", 'z' to "𝒛"
    ).mapKeys { it.key.code }

    private val SANS_BOLD_MAP = mapOf(
        'A' to "𝗔", 'B' to "𝗕", 'C' to "𝗖", 'D' to "𝗗", 'E' to "𝗘", 'F' to "𝗙", 'G' to "𝗚", 'H' to "𝗛", 'I' to "𝗜", 'J' to "𝗝", 'K' to "𝗞", 'L' to "𝗟", 'M' to "𝗠", 'N' to "𝗡", 'O' to "𝗢", 'P' to "𝗣", 'Q' to "𝗤", 'R' to "𝗥", 'S' to "𝗦", 'T' to "𝗧", 'U' to "𝗨", 'V' to "𝗩", 'W' to "𝗪", 'X' to "𝗫", 'Y' to "𝗬", 'Z' to "𝗭",
        'a' to "𝗮", 'b' to "𝗯", 'c' to "𝗰", 'd' to "𝗱", 'e' to "𝗲", 'f' to "𝗳", 'g' to "𝗴", 'h' to "𝗵", 'i' to "𝗶", 'j' to "𝗷", 'k' to "𝗸", 'l' to "𝗹", 'm' to "𝗺", 'n' to "𝗻", 'o' to "𝗼", 'p' to "𝗽", 'q' to "𝗾", 'r' to "𝗿", 's' to "𝘀", 't' to "𝘁", 'u' to "𝘂", 'v' to "𝘃", 'w' to "𝘄", 'x' to "𝘅", 'y' to "𝘆", 'z' to "𝘇",
        '0' to "𝟬", '1' to "𝟭", '2' to "𝟮", '3' to "𝟯", '4' to "𝟰", '5' to "𝟱", '6' to "𝟲", '7' to "𝟳", '8' to "𝟴", '9' to "𝟵"
    ).mapKeys { it.key.code }

    private val SANS_ITALIC_MAP = mapOf(
        'A' to "𝘈", 'B' to "𝘉", 'C' to "𝘊", 'D' to "𝘋", 'E' to "𝘌", 'F' to "𝘍", 'G' to "𝘎", 'H' to "𝘏", 'I' to "𝘐", 'J' to "𝘑", 'K' to "𝘒", 'L' to "𝘓", 'M' to "𝘔", 'N' to "𝘕", 'O' to "𝘖", 'P' to "𝘗", 'Q' to "𝘘", 'R' to "𝘙", 'S' to "𝘚", 'T' to "𝘛", 'U' to "𝘜", 'V' to "𝘝", 'W' to "𝘞", 'X' to "𝘟", 'Y' to "𝘠", 'Z' to "𝘡",
        'a' to "𝘢", 'b' to "𝘣", 'c' to "𝘤", 'd' to "𝘥", 'e' to "𝘦", 'f' to "𝘧", 'g' to "𝘨", 'h' to "𝘩", 'i' to "𝘪", 'j' to "𝘫", 'k' to "𝘬", 'l' to "𝘭", 'm' to "𝘮", 'n' to "𝘯", 'o' to "𝘰", 'p' to "𝘱", 'q' to "𝘲", 'r' to "𝘳", 's' to "𝘴", 't' to "𝘵", 'u' to "𝘶", 'v' to "𝘷", 'w' to "𝘸", 'x' to "𝘹", 'y' to "𝘺", 'z' to "𝘻"
    ).mapKeys { it.key.code }

    private val SANS_BOLD_ITALIC_MAP = mapOf(
        'A' to "𝘼", 'B' to "𝘽", 'C' to "𝘾", 'D' to "𝘿", 'E' to "𝙀", 'F' to "𝙁", 'G' to "𝙂", 'H' to "𝙃", 'I' to "𝙄", 'J' to "𝙅", 'K' to "𝙆", 'L' to "𝙇", 'M' to "𝙈", 'N' to "𝙉", 'O' to "𝙊", 'P' to "𝙋", 'Q' to "𝙌", 'R' to "𝙍", 'S' to "𝙎", 'T' to "𝙏", 'U' to "𝙐", 'V' to "𝙑", 'W' to "𝙒", 'X' to "𝙓", 'Y' to "𝙔", 'Z' to "𝙕",
        'a' to "𝙖", 'b' to "𝙗", 'c' to "𝙘", 'd' to "𝙙", 'e' to "𝙚", 'f' to "𝙛", 'g' to "𝙜", 'h' to "𝙝", 'i' to "𝙞", 'j' to "𝙟", 'k' to "𝙠", 'l' to "𝙡", 'm' to "𝙢", 'n' to "𝙣", 'o' to "𝙤", 'p' to "𝙥", 'q' to "𝙦", 'r' to "𝙧", 's' to "𝙨", 't' to "𝙩", 'u' to "𝙪", 'v' to "𝙫", 'w' to "𝙬", 'x' to "𝙭", 'y' to "𝙮", 'z' to "𝙯"
    ).mapKeys { it.key.code }

    private val SCRIPT_MAP = mapOf(
        'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ", 'F' to "ℱ", 'G' to "𝒢", 'H' to "ℋ", 'I' to "ℐ", 'J' to "𝒥", 'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩", 'O' to "𝒪", 'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯", 'U' to "𝒰", 'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳", 'Y' to "𝒴", 'Z' to "𝒵",
        'a' to "𝒶", 'b' to "𝒷", 'c' to "𝒸", 'd' to "𝒹", 'e' to "ℯ", 'f' to "𝒻", 'g' to "ℊ", 'h' to "𝒽", 'i' to "𝒾", 'j' to "𝒿", 'k' to "𝓀", 'l' to "𝓁", 'm' to "𝓂", 'n' to "𝓃", 'o' to "ℴ", 'p' to "𝓅", 'q' to "𝓆", 'r' to "𝓇", 's' to "𝓈", 't' to "𝓉", 'u' to "𝓊", 'v' to "𝓋", 'w' to "𝓌", 'x' to "𝓍", 'y' to "𝓎", 'z' to "𝓏"
    ).mapKeys { it.key.code }

    private val SCRIPT_BOLD_MAP = mapOf(
        'A' to "𝓐", 'B' to "𝓑", 'C' to "𝓒", 'D' to "𝓓", 'E' to "𝓔", 'F' to "𝓕", 'G' to "𝓖", 'H' to "𝓗", 'I' to "𝓘", 'J' to "𝓙", 'K' to "𝓚", 'L' to "𝓛", 'M' to "𝓜", 'N' to "𝓝", 'O' to "𝓞", 'P' to "𝓟", 'Q' to "𝓠", 'R' to "𝓡", 'S' to "𝓢", 'T' to "𝓣", 'U' to "𝓤", 'V' to "𝓥", 'W' to "𝓦", 'X' to "𝓧", 'Y' to "𝓨", 'Z' to "𝓩",
        'a' to "𝓪", 'b' to "𝓫", 'c' to "𝓬", 'd' to "𝓭", 'e' to "𝓮", 'f' to "𝓯", 'g' to "𝓰", 'h' to "𝓱", 'i' to "𝓲", 'j' to "𝓳", 'k' to "𝓴", 'l' to "𝓵", 'm' to "𝓶", 'n' to "𝓷", 'o' to "𝓸", 'p' to "𝓹", 'q' to "𝓺", 'r' to "𝓻", 's' to "𝓼", 't' to "𝓽", 'u' to "𝓾", 'v' to "𝓿", 'w' to "𝔀", 'x' to "𝔁", 'y' to "𝔂", 'z' to "𝔃"
    ).mapKeys { it.key.code }

    private val FRAKTUR_MAP = mapOf(
        'A' to "𝔄", 'B' to "𝔅", 'C' to "ℭ", 'D' to "𝔇", 'E' to "𝔈", 'F' to "𝔉", 'G' to "𝔊", 'H' to "ℌ", 'I' to "ℑ", 'J' to "𝔍", 'K' to "𝔎", 'L' to "𝔏", 'M' to "𝔐", 'N' to "𝔑", 'O' to "𝔒", 'P' to "𝔓", 'Q' to "𝔔", 'R' to "ℜ", 'S' to "𝔖", 'T' to "𝔗", 'U' to "𝔘", 'V' to "𝔙", 'W' to "𝔚", 'X' to "𝔛", 'Y' to "𝔜", 'Z' to "ℨ",
        'a' to "𝔞", 'b' to "𝔟", 'c' to "𝔠", 'd' to "𝔡", 'e' to "𝔢", 'f' to "𝔣", 'g' to "𝔤", 'h' to "𝔥", 'i' to "𝔦", 'j' to "𝔧", 'k' to "𝔨", 'l' to "𝔩", 'm' to "𝔪", 'n' to "𝔫", 'o' to "𝔬", 'p' to "𝔭", 'q' to "𝔮", 'r' to "𝔯", 's' to "𝔰", 't' to "𝔱", 'u' to "𝔲", 'v' to "𝔳", 'w' to "𝔴", 'x' to "𝔵", 'y' to "𝔶", 'z' to "𝔷"
    ).mapKeys { it.key.code }

    private val FRAKTUR_BOLD_MAP = mapOf(
        'A' to "𝕬", 'B' to "𝕭", 'C' to "𝕮", 'D' to "𝕯", 'E' to "𝕰", 'F' to "𝕱", 'G' to "𝕲", 'H' to "𝕳", 'I' to "𝕴", 'J' to "𝕵", 'K' to "𝕶", 'L' to "𝕷", 'M' to "𝕸", 'N' to "𝕹", 'O' to "𝕺", 'P' to "𝕻", 'Q' to "𝕼", 'R' to "𝕽", 'S' to "𝕾", 'T' to "𝕿", 'U' to "𝖀", 'V' to "𝖁", 'W' to "𝖂", 'X' to "𝖃", 'Y' to "𝖄", 'Z' to "𝖅",
        'a' to "𝖆", 'b' to "𝖇", 'c' to "𝖈", 'd' to "𝖉", 'e' to "𝖊", 'f' to "𝖋", 'g' to "𝖌", 'h' to "𝖍", 'i' to "𝖎", 'j' to "𝖏", 'k' to "𝖐", 'l' to "𝖑", 'm' to "𝖒", 'n' to "𝖓", 'o' to "𝖔", 'p' to "𝖕", 'q' to "𝖖", 'r' to "𝖗", 's' to "𝖘", 't' to "𝖙", 'u' to "𝖚", 'v' to "𝖛", 'w' to "𝖜", 'x' to "𝖝", 'y' to "𝖞", 'z' to "𝖟"
    ).mapKeys { it.key.code }

    private val MONOSPACE_MAP = mapOf(
        'A' to "𝙰", 'B' to "𝙱", 'C' to "𝙲", 'D' to "𝙳", 'E' to "𝙴", 'F' to "𝙵", 'G' to "𝙶", 'H' to "𝙷", 'I' to "𝙸", 'J' to "𝙹", 'K' to "𝙺", 'L' to "𝙻", 'M' to "𝙼", 'N' to "𝙽", 'O' to "𝙾", 'P' to "𝙿", 'Q' to "𝚀", 'R' to "𝚁", 'S' to "𝚂", 'T' to "𝚃", 'U' to "𝚄", 'V' to "𝚅", 'W' to "𝚆", 'X' to "𝚇", 'Y' to "𝚈", 'Z' to "𝚉",
        'a' to "𝚊", 'b' to "𝚋", 'c' to "𝚌", 'd' to "𝚍", 'e' to "𝚎", 'f' to "𝚏", 'g' to "𝚐", 'h' to "𝚑", 'i' to "𝚒", 'j' to "𝚓", 'k' to "𝚔", 'l' to "𝚕", 'm' to "𝚖", 'n' to "𝚗", 'o' to "𝚘", 'p' to "𝚙", 'q' to "𝚚", 'r' to "𝚛", 's' to "𝚜", 't' to "𝚝", 'u' to "𝚞", 'v' to "𝚟", 'w' to "𝚠", 'x' to "𝚡", 'y' to "𝚢", 'z' to "𝚣",
        '0' to "𝟶", '1' to "𝟷", '2' to "𝟸", '3' to "𝟹", '4' to "𝟺", '5' to "𝟻", '6' to "𝟼", '7' to "𝟽", '8' to "𝟾", '9' to "𝟿"
    ).mapKeys { it.key.code }

    private val DOUBLE_STRUCK_MAP = mapOf(
        'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽", 'G' to "𝔾", 'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ", 'O' to "𝕆", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌", 'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ",
        'a' to "𝕒", 'b' to "𝕓", 'c' to "𝕔", 'd' to "𝕕", 'e' to "𝕖", 'f' to "𝕗", 'g' to "𝕘", 'h' to "𝕙", 'i' to "𝕚", 'j' to "𝕛", 'k' to "𝕜", 'l' to "𝕝", 'm' to "𝕞", 'n' to "𝕟", 'o' to "𝕠", 'p' to "𝕡", 'q' to "𝕢", 'r' to "𝕣", 's' to "𝕤", 't' to "𝕥", 'u' to "𝕦", 'v' to "𝕧", 'w' to "𝕨", 'x' to "𝕩", 'y' to "𝕪", 'z' to "𝕫",
        '0' to "𝟘", '1' to "𝟙", '2' to "𝟚", '3' to "𝟛", '4' to "𝟜", '5' to "𝟝", '6' to "𝟞", '7' to "𝟟", '8' to "𝟠", '9' to "𝟡"
    ).mapKeys { it.key.code }

    private val CIRCLED_MAP = mapOf(
        'A' to "Ⓐ", 'B' to "Ⓑ", 'C' to "Ⓒ", 'D' to "Ⓓ", 'E' to "Ⓔ", 'F' to "Ⓕ", 'G' to "Ⓖ", 'H' to "Ⓗ", 'I' to "Ⓘ", 'J' to "Ⓙ", 'K' to "Ⓚ", 'L' to "Ⓛ", 'M' to "Ⓜ", 'N' to "Ⓝ", 'O' to "Ⓞ", 'P' to "Ⓟ", 'Q' to "Ⓠ", 'R' to "Ⓡ", 'S' to "Ⓢ", 'T' to "Ⓣ", 'U' to "Ⓤ", 'V' to "Ⓥ", 'W' to "Ⓦ", 'X' to "Ⓧ", 'Y' to "Ⓨ", 'Z' to "Ⓩ",
        'a' to "ⓐ", 'b' to "ⓑ", 'c' to "ⓒ", 'd' to "ⓓ", 'e' to "ⓔ", 'f' to "ⓕ", 'g' to "ⓖ", 'h' to "ⓗ", 'i' to "ⓘ", 'j' to "ⓙ", 'k' to "ⓚ", 'l' to "ⓛ", 'm' to "ⓜ", 'n' to "ⓝ", 'o' to "ⓞ", 'p' to "ⓟ", 'q' to "ⓠ", 'r' to "ⓡ", 's' to "ⓢ", 't' to "ⓣ", 'u' to "ⓤ", 'v' to "ⓥ", 'w' to "ⓦ", 'x' to "ⓧ", 'y' to "ⓨ", 'z' to "ⓩ",
        '0' to "⓪", '1' to "①", '2' to "②", '3' to "③", '4' to "④", '5' to "⑤", '6' to "⑥", '7' to "⑦", '8' to "⑧", '9' to "⑨"
    ).mapKeys { it.key.code }

    private val SMALL_CAPS_MAP = mapOf(
        'A' to "ᴀ", 'B' to "ʙ", 'C' to "ᴄ", 'D' to "ᴅ", 'E' to "ᴇ", 'F' to "ꜰ", 'G' to "ɢ", 'H' to "ʜ", 'I' to "ɪ", 'J' to "ᴊ", 'K' to "ᴋ", 'L' to "ʟ", 'M' to "ᴍ", 'N' to "ɴ", 'O' to "ᴏ", 'P' to "ᴘ", 'Q' to "Q", 'R' to "ʀ", 'S' to "s", 'T' to "ᴛ", 'U' to "ᴜ", 'V' to "ᴠ", 'W' to "ᴡ", 'X' to "x", 'Y' to "ʏ", 'Z' to "ᴢ",
        'a' to "ᴀ", 'b' to "ʙ", 'c' to "ᴄ", 'd' to "ᴅ", 'e' to "ᴇ", 'f' to "ꜰ", 'g' to "ɢ", 'h' to "ʜ", 'i' to "ɪ", 'j' to "ᴊ", 'k' to "ᴋ", 'l' to "ʟ", 'm' to "ᴍ", 'n' to "ɴ", 'o' to "ᴏ", 'p' to "ᴘ", 'q' to "q", 'r' to "ʀ", 's' to "s", 't' to "ᴛ", 'u' to "ᴜ", 'v' to "ᴠ", 'w' to "ᴡ", 'x' to "x", 'y' to "ʏ", 'z' to "ᴢ"
    ).mapKeys { it.key.code }

    private val ALL_MAPS = listOf(
        SERIF_BOLD_MAP, SERIF_ITALIC_MAP, SERIF_BOLD_ITALIC_MAP,
        SANS_BOLD_MAP, SANS_ITALIC_MAP, SANS_BOLD_ITALIC_MAP,
        SCRIPT_MAP, SCRIPT_BOLD_MAP, FRAKTUR_MAP, FRAKTUR_BOLD_MAP,
        MONOSPACE_MAP, DOUBLE_STRUCK_MAP, CIRCLED_MAP, SMALL_CAPS_MAP
    )

    private val REVERSE_MAP: Map<String, Char> by lazy {
        val combined = mutableMapOf<String, Char>()
        ALL_MAPS.forEach { map ->
            map.forEach { (code, styled) ->
                val originalChar = code.toChar()
                if (!combined.containsKey(styled)) {
                    combined[styled] = originalChar
                }
            }
        }
        combined
    }
}
