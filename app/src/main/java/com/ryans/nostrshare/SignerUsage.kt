package com.ryans.nostrshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

object SignerUsage {
    fun getPublicKey(context: Context): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.putExtra("type", "get_public_key")
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return intent
    }

    fun signEvent(context: Context, eventJson: String, loggedInUserNpub: String? = null, packageName: String? = null): Intent {
        // Use encoding only for URI... BUT Amber might prefer clean URI + Extra.
        // Let's try CLEAN URI + Extra.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        
        intent.putExtra("type", "sign_event")
        intent.putExtra("event", eventJson) // Primary payload
        intent.putExtra("id", System.currentTimeMillis().toString()) 
        
        if (loggedInUserNpub != null) {
            intent.putExtra("current_user", loggedInUserNpub)
        }
        
        if (packageName != null) {
            intent.`package` = packageName
        }
        // Removing CLEAR_TOP as it might reset the signer's state if it's already running
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return intent
    }

    fun signEventViaContentResolver(context: Context, eventJson: String, packageName: String): String? {
        // List of URIs to try. Amber uses .provider suffix. Others might use package name directly.
        val uris = listOf(
            Uri.parse("content://$packageName.provider/SIGN_EVENT"), // Amber specific FIRST
            Uri.parse("content://$packageName/SIGN_EVENT"), // Standard Slash variant
            Uri.parse("content://$packageName.SIGN_EVENT"), // Dot variant
        )
        
        var lastException: Exception? = null
        
        for (uri in uris) {
            try {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(eventJson, "", "1"), 
                    "1", 
                    null, 
                    null
                )
                
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex("signature")
                        if (index >= 0) {
                            return it.getString(index) 
                        }
                        val eventIndex = it.getColumnIndex("event")
                        if (eventIndex >= 0) {
                            return it.getString(eventIndex)
                        }
                         return it.getString(0)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                // Continue to try other URIs
            }
        }
        // If we exhausted all URIs and found nothing, throw the last exception if it exists 
        // so the UI can show "Permission Denied" etc.
        if (lastException != null) throw lastException
        
        return null
    }
}
