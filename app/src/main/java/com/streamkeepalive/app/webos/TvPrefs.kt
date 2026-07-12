package com.streamkeepalive.app.webos

import android.content.Context

/** Persists the paired TV's IP and the client-key webOS issues after the on-screen pairing prompt is accepted. */
class TvPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("streamkeepalive", Context.MODE_PRIVATE)

    var tvIp: String
        get() = prefs.getString(KEY_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var clientKey: String?
        get() = prefs.getString(KEY_CLIENT_KEY, null)
        set(value) = prefs.edit().putString(KEY_CLIENT_KEY, value).apply()

    var loopIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 60)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var commercialDurationSeconds: Int
        get() = prefs.getInt(KEY_COMMERCIAL, 60)
        set(value) = prefs.edit().putInt(KEY_COMMERCIAL, value).apply()

    /** Denon/Marantz AVR IP, for muting the receiver directly (audio routed via optical, not the TV). */
    var receiverIp: String
        get() = prefs.getString(KEY_RECEIVER_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RECEIVER_IP, value).apply()

    companion object {
        private const val KEY_IP = "tv_ip"
        private const val KEY_CLIENT_KEY = "client_key"
        private const val KEY_INTERVAL = "loop_interval_seconds"
        private const val KEY_COMMERCIAL = "commercial_duration_seconds"
        private const val KEY_RECEIVER_IP = "receiver_ip"
    }
}
