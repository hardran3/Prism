package com.ryans.nostrshare.nip55

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

private inline fun <T> parseNip55Result(
    resultCode: Int,
    intent: Intent?,
    missingResultMessage: String,
    transform: (result: String, intent: Intent) -> T
): Nip55Result<T> {
    if (resultCode != Activity.RESULT_OK) {
        return Nip55Result.Error(Nip55Error.UserRejected)
    }

    if (intent == null) {
        return Nip55Result.Error(Nip55Error.InvalidResponse("No response from signer"))
    }

    // Check for result (NIP-55 spec) first, then signature (Amber compatibility)
    val result = intent.getStringExtra(Nip55Protocol.RESULT)
        ?: intent.getStringExtra(Nip55Protocol.RESULT_SIGNATURE)
    if (result.isNullOrBlank()) {
        return Nip55Result.Error(Nip55Error.InvalidResponse(missingResultMessage))
    }

    return Nip55Result.Success(transform(result, intent))
}

class GetPublicKeyContract : ActivityResultContract<GetPublicKeyContract.Input, Nip55Result<PublicKeyResult>>() {

    data class Input(
        val permissions: List<Permission>? = null
    )

    override fun createIntent(context: Context, input: Input): Intent {
        return Nip55Protocol.createIntent().apply {
            putExtra(Nip55Protocol.EXTRA_TYPE, Nip55Protocol.TYPE_GET_PUBLIC_KEY)
            input.permissions?.let { permissions ->
                putExtra(Nip55Protocol.EXTRA_PERMISSIONS, permissions.toJsonArray())
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Nip55Result<PublicKeyResult> {
        return parseNip55Result(resultCode, intent, "No public key in response") { result, i ->
            PublicKeyResult(
                pubkey = result.npubToHex(),
                packageName = i.getStringExtra(Nip55Protocol.RESULT_PACKAGE)
            )
        }
    }
}

class SignEventContract : ActivityResultContract<SignEventContract.Input, Nip55Result<SignEventResult>>() {

    data class Input(
        val eventJson: String,
        val currentUser: String,
        val id: String? = null
    )

    override fun createIntent(context: Context, input: Input): Intent {
        return Nip55Protocol.createIntent(input.eventJson).apply {
            putExtra(Nip55Protocol.EXTRA_TYPE, Nip55Protocol.TYPE_SIGN_EVENT)
            putExtra(Nip55Protocol.EXTRA_CURRENT_USER, input.currentUser)
            input.id?.let { putExtra(Nip55Protocol.EXTRA_ID, it) }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Nip55Result<SignEventResult> {
        if (resultCode != Activity.RESULT_OK) {
            return Nip55Result.Error(Nip55Error.UserRejected)
        }

        if (intent == null) {
            return Nip55Result.Error(Nip55Error.InvalidResponse("No response from signer"))
        }

        val result = intent.getStringExtra(Nip55Protocol.RESULT)
            ?: intent.getStringExtra(Nip55Protocol.RESULT_SIGNATURE)
        val event = intent.getStringExtra(Nip55Protocol.RESULT_EVENT)

        if (result.isNullOrBlank() && event.isNullOrBlank()) {
            return Nip55Result.Error(Nip55Error.InvalidResponse("No signature or event in response"))
        }

        return Nip55Result.Success(
            SignEventResult(
                signature = result,
                signedEventJson = event,
                id = intent.getStringExtra(Nip55Protocol.RESULT_ID)
            )
        )
    }
}

class SignEventsContract : ActivityResultContract<SignEventsContract.Input, Nip55Result<SignEventsResult>>() {

    data class Input(
        val eventsJson: List<String>,
        val currentUser: String
    )

    override fun createIntent(context: Context, input: Input): Intent {
        return Nip55Protocol.createIntent().apply {
            putExtra(Nip55Protocol.EXTRA_TYPE, Nip55Protocol.TYPE_SIGN_EVENTS)
            putExtra(Nip55Protocol.EXTRA_CURRENT_USER, input.currentUser)
            
            // Try both StringArrayList and JSON string extra for maximum compatibility
            val array = org.json.JSONArray()
            input.eventsJson.forEach { array.put(it) }
            val json = array.toString()
            
            putExtra("events", json) // Standard NIP-55 JSON string
            putStringArrayListExtra("events_list", ArrayList(input.eventsJson)) // Fallback list
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Nip55Result<SignEventsResult> {
        if (resultCode != Activity.RESULT_OK) {
            return Nip55Result.Error(Nip55Error.UserRejected)
        }

        if (intent == null) {
            return Nip55Result.Error(Nip55Error.InvalidResponse("No response from signer"))
        }

        val sigsRaw = intent.getStringExtra(Nip55Protocol.RESULT_SIGNATURES)
        val eventsRaw = intent.getStringExtra("results")
        
        // Amber/Signer might return StringArrayList instead of JSON string
        val sigsList = intent.getStringArrayListExtra(Nip55Protocol.RESULT_SIGNATURES)
        val eventsList = intent.getStringArrayListExtra("results") ?: intent.getStringArrayListExtra("signatures")

        if (sigsRaw.isNullOrBlank() && eventsRaw.isNullOrBlank() && sigsList == null && eventsList == null) {
            return Nip55Result.Error(Nip55Error.InvalidResponse("No signatures or events in response"))
        }

        val signedEvents = mutableListOf<String>()
        
        // 1. Try to parse events from 'results' (as JSON array string or StringList)
        if (eventsList != null) {
            signedEvents.addAll(eventsList)
        } else if (!eventsRaw.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(eventsRaw)
                for (i in 0 until array.length()) {
                    signedEvents.add(array.getString(i))
                }
            } catch (_: Exception) {}
        }

        // 2. If no full events, try to parse signatures and merge (though NIP-55 prefers full events)
        if (signedEvents.isEmpty()) {
            // ... (Signature merging logic if needed, but for now we expect results)
            if (sigsList != null) {
                 // In some cases we might just get signatures, but we need the IDs to match them back
            }
        }

        return Nip55Result.Success(
            SignEventsResult(
                signedEventsJson = signedEvents
            )
        )
    }
}

data class SignEventsResult(
    val signedEventsJson: List<String>
)
