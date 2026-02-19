package com.ryans.nostrshare

data class UserProfile(
    val name: String?,
    val pictureUrl: String?,
    val lud16: String? = null
)

data class Account(
    val pubkey: String,
    val npub: String?,
    val signerPackage: String?,
    val name: String?,
    val pictureUrl: String?
)
