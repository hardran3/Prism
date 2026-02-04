package com.ryans.nostrshare.nip55

/**
 * Result of a NIP-55 operation.
 */
sealed class Nip55Result<out T> {
    data class Success<out T>(val value: T) : Nip55Result<T>()
    data class Error(val error: Nip55Error) : Nip55Result<Nothing>()
}

/**
 * Possible errors during NIP-55 operations.
 */
sealed class Nip55Error : Exception() {
    object UserRejected : Nip55Error() {
        override val message: String = "User rejected the operation"
    }

    object NotLoggedIn : Nip55Error() {
        override val message: String = "User is not logged in to the signer"
    }

    data class InvalidResponse(override val message: String) : Nip55Error()
    
    data class Unknown(override val message: String) : Nip55Error()
}

/**
 * Extension to handle results easily.
 */
inline fun <T> Nip55Result<T>.onSuccess(action: (T) -> Unit): Nip55Result<T> {
    if (this is Nip55Result.Success) {
        action(value)
    }
    return this
}

inline fun <T> Nip55Result<T>.onError(action: (Nip55Error) -> Unit): Nip55Result<T> {
    if (this is Nip55Result.Error) {
        action(error)
    }
    return this
}
