package com.pocketrealm.service

import com.pocketrealm.realm.RealmState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between the (background) [RealmService] and the (UI) app.
 *
 * The service is the single writer: it publishes its supervisor state here. The
 * UI observes this flow without needing to bind the service. This is an app-shell
 * convenience only — the foreground service remains the durability/process
 * boundary, not this object.
 */
object RealmBridge {
    private val _state = MutableStateFlow<RealmState>(RealmState.Idle)
    val state: StateFlow<RealmState> = _state.asStateFlow()

    internal fun publish(state: RealmState) {
        _state.value = state
    }
}
