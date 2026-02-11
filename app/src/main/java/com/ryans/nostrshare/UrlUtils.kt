package com.ryans.nostrshare

import androidx.core.net.toUri

object UrlUtils {
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "gclsrc", "dclid", "gra", "grb", "grn", // Google/FB
        "si", // YouTube share
        "igsh", // Instagram share
        "s", // Twitter/X share (sometimes)
        "t", // Twitter/X share (sometimes)
        "ref", "ref_src", "ref_url"
    )
    fun cleanUrl(url: String): String {
        try {
            val uri = url.toUri()
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return url

            val builder = uri.buildUpon().clearQuery()

            val originalParams = uri.queryParameterNames
            
            for (param in originalParams) {
                if (!TRACKING_PARAMS.contains(param.lowercase())) {
                    for (value in uri.getQueryParameters(param)) {
                        builder.appendQueryParameter(param, value)
                    }
                }
            }

            return builder.build().toString()
        } catch (e: Exception) {
            return url
        }
    }

    /**
     * Finds all URLs in a text block and cleans them.
     */
    fun cleanText(text: String): String {
        val urlRegex = "https?://[^\\s]+".toRegex()
        return urlRegex.replace(text) { match ->
            cleanUrl(match.value)
        }
    }
}
