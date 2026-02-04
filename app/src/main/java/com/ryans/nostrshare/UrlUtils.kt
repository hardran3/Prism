package com.ryans.nostrshare

import android.net.Uri

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
            val uri = Uri.parse(url)
            val builder = uri.buildUpon().clearQuery()

            val originalParams = uri.queryParameterNames
            var hasParams = false
            
            for (param in originalParams) {
                if (!TRACKING_PARAMS.contains(param.lowercase())) {
                    for (value in uri.getQueryParameters(param)) {
                        builder.appendQueryParameter(param, value)
                        hasParams = true
                    }
                }
            }

            return builder.build().toString()
        } catch (e: Exception) {
            return url // Return original if parsing fails
        }
    }
}
