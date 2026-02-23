package com.ryans.nostrshare.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.ryans.nostrshare.UserProfile
import com.ryans.nostrshare.NostrUtils

object MarkdownUtils {

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
    ): AnnotatedString {
        val builder = AnnotatedString.Builder()
        val lines = text.split("\n")

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

        val numberedListRegex = Regex("""^\d+\. """)

        lines.forEachIndexed { i, line ->
            when {
                isFenceLine[i] -> {
                    if (!stripDelimiters) {
                        builder.withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = codeColor.copy(alpha = 0.5f)
                        )) { builder.append(line) }
                    }
                }
                inCodeBlock[i] -> {
                    builder.withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeColor.copy(alpha = 0.1f),
                        color = codeColor
                    )) { builder.append(line) }
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val prefixLen = if (line.length > level && line[level] == ' ') level + 1 else level
                    val style = when(level) {
                        1 -> h1Style
                        2 -> h2Style
                        else -> h3Style
                    }
                    val spanStyle = SpanStyle(
                        fontSize = style?.fontSize ?: TextUnit.Unspecified,
                        fontWeight = FontWeight.Bold,
                        color = highlightColor
                    )
                    
                    if (stripDelimiters) {
                        builder.withStyle(spanStyle) {
                            builder.append(line.substring(prefixLen))
                        }
                    } else {
                        builder.withStyle(spanStyle) {
                            builder.append(line)
                        }
                    }
                }
                line.startsWith("> ") -> {
                    val spanStyle = SpanStyle(
                        color = highlightColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic,
                        background = highlightColor.copy(alpha = 0.05f)
                    )
                    if (stripDelimiters) {
                        builder.withStyle(spanStyle) {
                            builder.append(line.substring(2))
                        }
                    } else {
                        builder.withStyle(spanStyle) {
                            builder.append(line)
                        }
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    builder.withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                        if (stripDelimiters) {
                            builder.append("• ")
                        } else {
                            builder.append(line.takeWhile { it != ' ' } + " ")
                        }
                    }
                    builder.append(line.substringAfter(" "))
                }
                numberedListRegex.containsMatchIn(line) -> {
                    val matchResult = numberedListRegex.find(line)
                    if (matchResult != null) {
                        val prefix = matchResult.value
                        builder.withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                            builder.append(prefix)
                        }
                        builder.append(line.substring(prefix.length))
                    } else {
                        builder.append(line)
                    }
                }
                else -> {
                    var lastIdx = 0
                    val pattern = Regex("""(https?://[^\s]+|nostr:(?:nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+|\*\*.*?\*\*|__.*?__|\*.*?\*|_.*?_|`.*?`|#\w+)""", RegexOption.IGNORE_CASE)

                    pattern.findAll(line).forEach { match ->
                        builder.append(line.substring(lastIdx, match.range.first))
                        val m = match.value
                        when {
                            m.startsWith("http") -> {
                                builder.withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { builder.append(m) }
                            }
                            m.startsWith("nostr:") -> {
                                val entity = m.removePrefix("nostr:")
                                val pk = try { NostrUtils.findNostrEntity(entity)?.id } catch(_: Exception) { null }
                                val name = pk?.let { usernameCache[it]?.name }
                                if (stripDelimiters && name != null) {
                                    builder.withStyle(SpanStyle(color = nostrColor, fontWeight = FontWeight.Bold)) {
                                        builder.append("@$name")
                                    }
                                } else {
                                    builder.withStyle(SpanStyle(color = nostrColor, fontWeight = FontWeight.Bold)) {
                                        builder.append(m)
                                    }
                                }
                            }
                            m.length >= 4 && ((m.startsWith("**") && m.endsWith("**")) || (m.startsWith("__") && m.endsWith("__"))) -> {
                                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    if (stripDelimiters) {
                                        builder.append(m.substring(2, m.length - 2))
                                    } else {
                                        builder.append(m)
                                    }
                                }
                            }
                            m.length >= 2 && ((m.startsWith("*") && m.endsWith("*")) || (m.startsWith("_") && m.endsWith("_"))) -> {
                                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                    if (stripDelimiters) {
                                        builder.append(m.substring(1, m.length - 1))
                                    } else {
                                        builder.append(m)
                                    }
                                }
                            }
                            m.length >= 2 && m.startsWith("`") && m.endsWith("`") -> {
                                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.1f), color = codeColor)) {
                                    if (stripDelimiters) {
                                        builder.append(m.substring(1, m.length - 1))
                                    } else {
                                        builder.append(m)
                                    }
                                }
                            }
                            m.startsWith("#") -> {
                                builder.withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) { builder.append(m) }
                            }
                            else -> builder.append(m)
                        }
                        lastIdx = match.range.last + 1
                    }
                    builder.append(line.substring(lastIdx))
                }
            }
            if (i < lines.size - 1) {
                // If we are stripping delimiters and the entire line was a fence line, don't append newline if it would be empty
                if (!stripDelimiters || !isFenceLine[i]) {
                    builder.append("\n")
                }
            }
        }
        return builder.toAnnotatedString()
    }
}
