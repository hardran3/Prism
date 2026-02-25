package com.ryans.nostrshare.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ryans.nostrshare.UserProfile
import com.ryans.nostrshare.NostrUtils

object MarkdownUtils {

    data class MarkdownResult(
        val annotatedString: AnnotatedString,
        val originalToTransformed: IntArray,
        val transformedToOriginal: IntArray
    )

    fun renderMarkdown(
        text: String,
        usernameCache: Map<String, UserProfile>,
        highlightColor: Color,
        codeColor: Color,
        linkColor: Color = Color(0xFF2196F3),
        nostrColor: Color = Color(0xFF9C27B0),
        h1Style: TextStyle? = null,
        h2Style: TextStyle? = null,
        h3Style: TextStyle? = null,
        stripDelimiters: Boolean = false
    ): MarkdownResult {
        val builder = AnnotatedString.Builder()
        val originalToTransformed = IntArray(text.length + 1)
        val transformedToOriginalList = mutableListOf<Int>()
        
        fun appendTransformed(content: String, originalIndex: Int) {
            val start = builder.length
            builder.append(content)
            for (i in 0 until content.length) {
                transformedToOriginalList.add(originalIndex)
            }
        }

        fun emit(content: String, origStart: Int, origLength: Int) {
            val startInTrans = builder.length
            builder.append(content)
            for (k in 0 until content.length) {
                transformedToOriginalList.add(origStart + (k.coerceAtMost(origLength - 1)))
            }
            for (k in 0 until origLength) {
                originalToTransformed[origStart + k] = startInTrans + (k.coerceAtMost(content.length - 1))
            }
        }

        fun skip(origStart: Int, origLength: Int) {
            val currentTrans = builder.length
            for (k in 0 until origLength) {
                originalToTransformed[origStart + k] = currentTrans
            }
        }

        val lines = text.split("\n")
        var currentOffset = 0

        // Pre-scan for fenced code blocks
        val inCodeBlock = BooleanArray(lines.size)
        val isFenceLine = BooleanArray(lines.size)
        var fenceOpen = false
        for (idx in lines.indices) {
            if (lines[idx].trimStart().startsWith("```")) {
                isFenceLine[idx] = true
                fenceOpen = !fenceOpen
            } else if (fenceOpen) {
                inCodeBlock[idx] = true
            }
        }

        lines.forEachIndexed { i, line ->
            val lineStart = currentOffset
            
            when {
                isFenceLine[i] -> {
                    if (!stripDelimiters) emit(line, lineStart, line.length)
                    else skip(lineStart, line.length)
                }
                inCodeBlock[i] -> {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.1f), color = codeColor))
                    emit(line, lineStart, line.length)
                    builder.pop()
                }
                line.startsWith("#") && line.getOrNull(line.takeWhile { it == '#' }.length) == ' ' -> {
                    val level = line.takeWhile { it == '#' }.length
                    val prefixLen = level + 1
                    val style = when(level) {
                        1 -> h1Style
                        2 -> h2Style
                        else -> h3Style
                    }
                    builder.pushStyle(SpanStyle(fontSize = style?.fontSize ?: TextUnit.Unspecified, fontWeight = FontWeight.Bold, color = highlightColor))
                    if (!stripDelimiters) {
                        renderInline(line, lineStart, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                    } else {
                        skip(lineStart, prefixLen)
                        renderInline(line.substring(prefixLen), lineStart + prefixLen, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                    }
                    builder.pop()
                }
                line.startsWith("> ") -> {
                    builder.pushStyle(SpanStyle(color = highlightColor.copy(alpha = 0.7f), fontStyle = FontStyle.Italic, background = highlightColor.copy(alpha = 0.05f)))
                    if (!stripDelimiters) {
                        renderInline(line, lineStart, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                    } else {
                        skip(lineStart, 2)
                        renderInline(line.substring(2), lineStart + 2, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                    }
                    builder.pop()
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    builder.pushStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold))
                    if (stripDelimiters) {
                        appendTransformed("• ", lineStart)
                        skip(lineStart, 2)
                    } else {
                        val prefix = line.takeWhile { it != ' ' } + " "
                        emit(prefix, lineStart, prefix.length)
                    }
                    builder.pop()
                    renderInline(line.substringAfter(" "), lineStart + 2, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                }
                else -> {
                    renderInline(line, lineStart, builder, usernameCache, linkColor, nostrColor, stripDelimiters, ::emit, ::skip, ::appendTransformed)
                }
            }

            currentOffset += line.length
            if (i < lines.size - 1) {
                val nlStart = currentOffset
                if (!stripDelimiters || !isFenceLine[i]) {
                    appendTransformed("\n", nlStart)
                    originalToTransformed[nlStart] = builder.length - 1
                } else {
                    originalToTransformed[nlStart] = builder.length
                }
                currentOffset += 1 // for the \n
            }
        }
        
        originalToTransformed[text.length] = builder.length
        transformedToOriginalList.add(text.length)

        return MarkdownResult(
            annotatedString = builder.toAnnotatedString(),
            originalToTransformed = originalToTransformed,
            transformedToOriginal = transformedToOriginalList.toIntArray()
        )
    }

    private fun renderInline(
        text: String,
        baseOffset: Int,
        builder: AnnotatedString.Builder,
        usernameCache: Map<String, UserProfile>,
        linkColor: Color,
        nostrColor: Color,
        stripDelimiters: Boolean,
        emit: (String, Int, Int) -> Unit,
        skip: (Int, Int) -> Unit,
        appendTransformed: (String, Int) -> Unit
    ) {
        // Hierarchical regex: Markdown Links/Images MUST come first to protect them from naked URL matching
        // URL Regex improved to handle one level of balanced parentheses
        val pattern = Regex("""((!?)\[([^\]]*?)\]\((https?://[^\s)]+(?:\([^\s)]*\)[^\s)]*)*)\)|https?://[^\s]+|nostr:(?:nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+|\*\*\*(.+?)\*\*\*|___(.+?)___|\*\*_(.+?)_\*\*|__\*(.+?)\*__|\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|#\w+)""", RegexOption.IGNORE_CASE)
        var lastIdx = 0

        pattern.findAll(text).forEach { match ->
            val plainText = text.substring(lastIdx, match.range.first)
            emit(plainText, baseOffset + lastIdx, plainText.length)
            
            val m = match.value
            val mStart = baseOffset + match.range.first
            
            when {
                // Markdown Link or Image detection (Independent of group index)
                m.startsWith("[") || m.startsWith("![") -> {
                    val isImage = m.startsWith("!")
                    val inner = if (isImage) m.substring(2) else m.substring(1)
                    val display = inner.substringBefore("]")
                    val url = inner.substringAfter("(").substringBefore(")")
                    val icon = if (isImage) "🖼️" else "🔗"
                    
                    if (stripDelimiters) {
                        val tag = if (isImage) "IMAGE_URL" else "URL"
                        builder.pushStringAnnotation(tag, url)
                        builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        
                        val displayText = display.ifEmpty { if (isImage) "Image" else "Link" }
                        emit(displayText, mStart, m.length)
                        
                        builder.pushStyle(SpanStyle(fontSize = 8.sp))
                        appendTransformed(" $icon", mStart + m.length - 1)
                        builder.pop()
                        
                        builder.pop()
                        builder.pop()
                    } else {
                        builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        emit(m, mStart, m.length)
                        builder.pop()
                    }
                }
                m.startsWith("http") -> {
                    builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    emit(m, mStart, m.length)
                    builder.pop()
                }
                m.startsWith("nostr:") -> {
                    val entity = m.removePrefix("nostr:")
                    val pk = try { NostrUtils.findNostrEntity(entity)?.id } catch(_: Exception) { null }
                    val name = pk?.let { usernameCache[it]?.name }
                    builder.pushStyle(SpanStyle(color = nostrColor, fontWeight = FontWeight.Bold))
                    if (stripDelimiters && name != null) {
                        emit("@$name", mStart, m.length)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                // Triple (Bold + Italic)
                (m.startsWith("***") && m.endsWith("***")) || (m.startsWith("___") && m.endsWith("___")) ||
                (m.startsWith("**_") && m.endsWith("_**")) || (m.startsWith("__*") && m.endsWith("*__")) -> {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    if (stripDelimiters) {
                        skip(mStart, 3)
                        emit(m.substring(3, m.length - 3), mStart + 3, m.length - 6)
                        skip(mStart + m.length - 3, 3)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                // Double (Bold)
                m.length >= 4 && ((m.startsWith("**") && m.endsWith("**")) || (m.startsWith("__") && m.endsWith("__"))) -> {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    if (stripDelimiters) {
                        skip(mStart, 2)
                        emit(m.substring(2, m.length - 2), mStart + 2, m.length - 4)
                        skip(mStart + m.length - 2, 2)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                // Single (Italic)
                m.length >= 2 && ((m.startsWith("*") && m.endsWith("*")) || (m.startsWith("_") && m.endsWith("_"))) -> {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    if (stripDelimiters) {
                        skip(mStart, 1)
                        emit(m.substring(1, m.length - 1), mStart + 1, m.length - 2)
                        skip(mStart + m.length - 1, 1)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                // Code
                m.startsWith("`") && m.endsWith("`") -> {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(alpha = 0.2f)))
                    if (stripDelimiters) {
                        skip(mStart, 1)
                        emit(m.substring(1, m.length - 1), mStart + 1, m.length - 2)
                        skip(mStart + m.length - 1, 1)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                m.startsWith("#") -> {
                    builder.pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold))
                    emit(m, mStart, m.length)
                    builder.pop()
                }
                else -> emit(m, mStart, m.length)
            }
            lastIdx = match.range.last + 1
        }
        val remaining = text.substring(lastIdx)
        emit(remaining, baseOffset + lastIdx, remaining.length)
    }
}
