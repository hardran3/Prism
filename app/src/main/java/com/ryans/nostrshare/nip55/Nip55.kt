package com.ryans.nostrshare.nip55

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object Nip55 {

    fun isSignerAvailable(context: Context): Boolean {
        return getAvailableSigners(context).isNotEmpty()
    }

    fun getAvailableSigners(context: Context): List<SignerInfo> {
        val intent = Nip55Protocol.createDiscoveryIntent()
        val packageManager = context.packageManager

        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )

        return resolveInfos.map { info ->
            info.toSignerInfo(
                appName = info.loadLabel(packageManager).toString()
            )
        }
    }

    fun createIntentForSigner(packageName: String): Intent {
        return Nip55Protocol.createIntent().apply {
            setPackage(packageName)
        }
    }

    fun signEventBackground(
        context: Context,
        signerPackage: String,
        eventJson: String,
        loggedInPubkey: String
    ): String? {
        val uri = android.net.Uri.parse("content://$signerPackage.SIGN_EVENT")
        val projection = arrayOf(eventJson, "", loggedInPubkey)
        
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val eventIndex = cursor.getColumnIndex("event")
                    val resultIndex = cursor.getColumnIndex("result")
                    val sigIndex = cursor.getColumnIndex("signature")
                    
                    val result = when {
                        eventIndex >= 0 -> cursor.getString(eventIndex)
                        resultIndex >= 0 -> cursor.getString(resultIndex)
                        sigIndex >= 0 -> cursor.getString(sigIndex)
                        else -> null
                    }
                    
                    if (result != null && result.trim().startsWith("{")) {
                        result
                    } else {
                        null
                    }
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("Nip55", "Background signing failed", e)
            null
        }
    }

    fun signEventsBackground(
        context: Context,
        signerPackage: String,
        eventsJson: List<String>,
        loggedInPubkey: String
    ): List<String>? {
        val uri = android.net.Uri.parse("content://$signerPackage.SIGN_EVENTS")
        val array = org.json.JSONArray()
        eventsJson.forEach { array.put(it) }
        val projection = arrayOf(array.toString(), "", loggedInPubkey)
        
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val resultsIndex = cursor.getColumnIndex("results")
                    val results = if (resultsIndex >= 0) cursor.getString(resultsIndex) else null
                    
                    if (results != null) {
                        try {
                            val resultArray = org.json.JSONArray(results)
                            val signedEvents = mutableListOf<String>()
                            for (i in 0 until resultArray.length()) {
                                signedEvents.add(resultArray.getString(i))
                            }
                            signedEvents
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("Nip55", "Background batch signing failed", e)
            null
        }
    }
}
