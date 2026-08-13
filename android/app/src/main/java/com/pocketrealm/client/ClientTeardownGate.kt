package com.pocketrealm.client

/** The display boundary may be released only after both runtime proofs agree. */
internal object ClientTeardownGate {
    fun mayReleaseDisplay(
        controlSucceeded: Boolean,
        runtimeFinished: Boolean,
        processTreeDrained: Boolean,
    ): Boolean = controlSucceeded && runtimeFinished && processTreeDrained
}
