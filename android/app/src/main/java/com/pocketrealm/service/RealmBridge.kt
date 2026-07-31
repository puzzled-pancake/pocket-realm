package com.pocketrealm.service

import com.pocketrealm.realm.RealmState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between the (background) [RealmService] and the (UI) app.
 *
 * The service is the single writer: it publishes its supervisor state here. The
 * UI observes [state] without needing to bind the service. This is an app-shell
 * convenience only — the foreground service remains the durability/process
 * boundary, not this object.
 *
 * Two flows are exposed because they serve different needs:
 *  - [state]: a [StateFlow] holding the CURRENT state. Use this for `collectAsState`
 *    in Compose, which requires a concrete current value and is fine with
 *    conflation (only the latest matters for rendering).
 *  - [events]: a non-conflating [SharedFlow] that emits EVERY transition. Use
 *    this when no transition may be missed (e.g. a fast Saving -> Stopping flash
 *    on a slow dispatcher, or lifecycle tests). [MutableStateFlow] alone would
 *    conflate a state held only briefly out of existence before a collector reads it.
 */
object RealmBridge {
    private val _state = MutableStateFlow<RealmState>(RealmState.Idle)
    val state: StateFlow<RealmState> = _state.asStateFlow()

    // replay = 1 so a late subscriber still sees the most recent transition; the
    // buffer (extraBufferCapacity) lets the writer never suspend on a slow reader.
    private val _events = MutableSharedFlow<RealmState>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<RealmState> = _events.asSharedFlow()

    internal fun publish(state: RealmState) {
        _state.value = state
        _events.tryEmit(state)
    }
}
