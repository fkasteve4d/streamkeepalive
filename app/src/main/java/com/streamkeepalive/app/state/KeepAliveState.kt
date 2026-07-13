package com.streamkeepalive.app.state

import com.streamkeepalive.app.webos.WebOsConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process shared state bridging the UI and [com.streamkeepalive.app.service.KeepAliveService]
 * (they run in the same process). The UI writes settings and reads status; the service drives
 * [connectionState], [loopRunning], and [countdownRemaining] as it acts.
 *
 *  - [serviceRunning]            true while the foreground service is alive at all.
 *  - [loopRunning]               true while the play/pause keep-alive cycle is active.
 *  - [countdownRemaining]        seconds left in a commercial-assist countdown, null when idle.
 *  - [loopIntervalSeconds]       keep-alive pulse interval, 60..600 (1-10 min).
 *  - [commercialDurationSeconds] commercial-assist mute duration, 15..240 (15s steps).
 *  - [mainZoneOn] / [zone2On]    Denon zone power state; null = unknown (not yet queried).
 *      Refreshed on demand (panel expand, after a toggle) rather than polled continuously.
 *  - [activeSoundMode]           "MOVIE" or "MUSIC" (or null), tracked optimistically from
 *      the last button tapped — not queried from the receiver.
 */
object KeepAliveState {
    val connectionState = MutableStateFlow(WebOsConnectionState.DISCONNECTED)
    val connectionError = MutableStateFlow<String?>(null)
    val serviceRunning = MutableStateFlow(false)
    val loopRunning = MutableStateFlow(false)
    val countdownRemaining = MutableStateFlow<Int?>(null)
    val loopIntervalSeconds = MutableStateFlow(60)
    val commercialDurationSeconds = MutableStateFlow(60)
    val mainZoneOn = MutableStateFlow<Boolean?>(null)
    val zone2On = MutableStateFlow<Boolean?>(null)
    val activeSoundMode = MutableStateFlow<String?>(null)
}
