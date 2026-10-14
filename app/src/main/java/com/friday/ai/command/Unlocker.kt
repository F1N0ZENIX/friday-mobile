package com.friday.ai.command

/**
 * Asks Android to unlock the phone — its own screen: fingerprint, face or
 * PIN. Friday never sees the PIN; it only learns whether the phone is now
 * unlocked.
 */
fun interface Unlocker {

    enum class Result { UNLOCKED, STAYED_LOCKED, CANNOT_ASK }

    suspend fun unlock(): Result
}
