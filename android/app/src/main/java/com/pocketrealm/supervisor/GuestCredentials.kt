package com.pocketrealm.supervisor

import java.security.SecureRandom

/**
 * D5a (design-lan §4.2): the guest-account password generator, shared by
 * both platforms ("the generator lives next to the provision verb").
 *
 * Shape, pinned by test: 16 characters — the engine cap
 * (`MAX_ACCOUNT_STR = 16`, native/cmangos/src/game/Accounts/AccountMgr.h:34;
 * the relay enforces 1..16 ASCII-alnum on both fields,
 * world_runtime.cpp:271-282) — drawn uniform-random over an unambiguous
 * 32-symbol alphabet (no `0/O/1/l/I`), which is exactly 5 bits per symbol:
 * 16 × 5 = 80 bits of entropy. The alphabet is ASCII-alnum only, so the
 * relay's account-token rule accepts it verbatim.
 */
object GuestCredentials {

    /** The password length: the engine's account-token cap. */
    const val LENGTH = 16

    /** 24 letters (a–z minus the confusable `l` and `o`) + 8 digits (2–9). */
    const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    private val RANDOM = SecureRandom()

    /** One generated password: [LENGTH] uniform symbols from [ALPHABET]. */
    fun generatePassword(random: () -> Int = { RANDOM.nextInt(ALPHABET.length) }): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random()]) } }
}
