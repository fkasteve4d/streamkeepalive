package com.streamkeepalive.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import com.streamkeepalive.app.MainActivity
import com.streamkeepalive.app.R
import com.streamkeepalive.app.denon.DenonClient
import com.streamkeepalive.app.state.KeepAliveState
import com.streamkeepalive.app.webos.TvPrefs
import com.streamkeepalive.app.webos.WebOsClient
import com.streamkeepalive.app.webos.WebOsConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service owning the single [WebOsClient] connection to the TV. Runs the
 * play/pause keep-alive loop and the mute-then-countdown-then-loop commercial assist,
 * so both keep working with the screen off and the app backgrounded.
 */
class KeepAliveService : Service() {

    private lateinit var prefs: TvPrefs
    private lateinit var webOsClient: WebOsClient
    private val denonClient = DenonClient()
    private val scope = CoroutineScope(SupervisorJob())
    private var loopJob: Job? = null
    private var countdownJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        prefs = TvPrefs(this)
        webOsClient = WebOsClient(
            onStateChanged = { state, error ->
                KeepAliveState.connectionState.value = state
                KeepAliveState.connectionError.value = error
                if (state == WebOsConnectionState.PAIRED) {
                    pendingAction?.invoke()
                    pendingAction = null
                }
                updateNotification()
            },
            onPaired = { key -> prefs.clientKey = key }
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamkeepalive:loop")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        KeepAliveState.serviceRunning.value = true
        wakeLock?.let { if (!it.isHeld) it.acquire(8 * 60 * 60 * 1000L /* 8h safety cap */) }

        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_START_LOOP -> whenPaired { startLoop() }
            ACTION_STOP_LOOP -> stopLoop()
            ACTION_START_COMMERCIAL -> {
                val duration = intent.getIntExtra(EXTRA_DURATION, 60)
                whenPaired { startCommercialAssist(duration) }
            }
            ACTION_CANCEL_COMMERCIAL -> cancelCommercialAssist()
            ACTION_TOGGLE_MAIN_POWER -> toggleMainPower()
            ACTION_TOGGLE_ZONE2_POWER -> toggleZone2Power()
            ACTION_QUERY_ZONES -> queryZones()
            ACTION_DPAD -> intent.getStringExtra(EXTRA_BUTTON)?.let { webOsClient.sendButton(it) }
            ACTION_SOUND_MODE -> intent.getStringExtra(EXTRA_MODE)?.let { mode ->
                KeepAliveState.activeSoundMode.value = mode
                scope.launch { denonClient.sendSoundMode(prefs.receiverIp, mode) }
            }
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        webOsClient.connect(prefs.tvIp, prefs.clientKey)
    }

    private fun whenPaired(action: () -> Unit) {
        if (KeepAliveState.connectionState.value == WebOsConnectionState.PAIRED) {
            action()
        } else {
            pendingAction = action
            connect()
        }
    }

    private fun startLoop() {
        countdownJob?.cancel()
        countdownJob = null
        KeepAliveState.countdownRemaining.value = null
        loopJob?.cancel()
        KeepAliveState.loopRunning.value = true
        updateNotification()
        loopJob = scope.launch {
            webOsClient.sendPause()
            while (isActive) {
                delay(KeepAliveState.loopIntervalSeconds.value * 1000L)
                webOsClient.sendPlay()
                delay(1000)
                webOsClient.sendPause()
            }
        }
    }

    /** Stops the loop and resumes playback immediately, but leaves the TV connection open. */
    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        KeepAliveState.loopRunning.value = false
        webOsClient.sendPlay()
        updateNotification()
    }

    private fun startCommercialAssist(durationSeconds: Int) {
        loopJob?.cancel()
        KeepAliveState.loopRunning.value = false
        countdownJob?.cancel()
        updateNotification()
        countdownJob = scope.launch {
            webOsClient.setMute(true)
            denonClient.setMute(prefs.receiverIp, true)
            for (remaining in durationSeconds downTo 1) {
                KeepAliveState.countdownRemaining.value = remaining
                delay(1000)
            }
            KeepAliveState.countdownRemaining.value = null
            webOsClient.setMute(false)
            denonClient.setMute(prefs.receiverIp, false)
            updateNotification()
            startLoop() // countdown finished naturally — hand off into the keep-alive loop
        }
    }

    /** Cancels an in-progress commercial-assist countdown and unmutes; unrelated to the loop. */
    private fun cancelCommercialAssist() {
        countdownJob?.cancel()
        countdownJob = null
        KeepAliveState.countdownRemaining.value = null
        scope.launch {
            webOsClient.setMute(false)
            denonClient.setMute(prefs.receiverIp, false)
            updateNotification()
        }
    }

    // Optimistic flip for instant UI feedback, then re-query the real state shortly after —
    // if the command actually failed, this self-corrects instead of leaving a stale/wrong
    // indicator (which previously misrouted the volume rocker to a zone that wasn't really on).
    private fun toggleMainPower() {
        val newState = !(KeepAliveState.mainZoneOn.value ?: false)
        KeepAliveState.mainZoneOn.value = newState
        scope.launch {
            denonClient.setMainZonePower(prefs.receiverIp, newState)
            delay(500)
            KeepAliveState.mainZoneOn.value = denonClient.isMainZoneOn(prefs.receiverIp) ?: newState
        }
    }

    private fun toggleZone2Power() {
        val newState = !(KeepAliveState.zone2On.value ?: false)
        KeepAliveState.zone2On.value = newState
        scope.launch {
            denonClient.setZone2Power(prefs.receiverIp, newState)
            delay(500)
            KeepAliveState.zone2On.value = denonClient.isZone2On(prefs.receiverIp) ?: newState
        }
    }

    private fun queryZones() {
        scope.launch {
            KeepAliveState.mainZoneOn.value = denonClient.isMainZoneOn(prefs.receiverIp)
            KeepAliveState.zone2On.value = denonClient.isZone2On(prefs.receiverIp)
        }
    }

    private fun stopAll() {
        loopJob?.cancel(); loopJob = null
        countdownJob?.cancel(); countdownJob = null
        if (KeepAliveState.countdownRemaining.value != null) {
            // safety: don't leave the TV/receiver muted if stopped mid-countdown
            scope.launch {
                webOsClient.setMute(false)
                denonClient.setMute(prefs.receiverIp, false)
            }
        }
        pendingAction = null
        KeepAliveState.countdownRemaining.value = null
        KeepAliveState.loopRunning.value = false
        webOsClient.disconnect()
        KeepAliveState.connectionState.value = WebOsConnectionState.DISCONNECTED
        KeepAliveState.serviceRunning.value = false
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        countdownJob?.cancel()
        webOsClient.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        KeepAliveState.serviceRunning.value = false
        KeepAliveState.loopRunning.value = false
        KeepAliveState.countdownRemaining.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val statusText = when {
            KeepAliveState.countdownRemaining.value != null ->
                "Muted — ${KeepAliveState.countdownRemaining.value}s left"
            KeepAliveState.loopRunning.value -> "Keep-alive running"
            KeepAliveState.connectionState.value == WebOsConnectionState.AWAITING_TV_PROMPT ->
                "Check your TV screen to allow pairing"
            KeepAliveState.connectionState.value == WebOsConnectionState.PAIRED -> "Connected"
            else -> "Connecting…"
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamKeepAlive")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val mgr = ContextCompat.getSystemService(this, NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Keep-alive", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while StreamKeepAlive is connected to your TV" }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1
        private const val ACTION_CONNECT = "com.streamkeepalive.app.CONNECT"
        private const val ACTION_START_LOOP = "com.streamkeepalive.app.START_LOOP"
        private const val ACTION_STOP_LOOP = "com.streamkeepalive.app.STOP_LOOP"
        private const val ACTION_START_COMMERCIAL = "com.streamkeepalive.app.START_COMMERCIAL"
        private const val ACTION_CANCEL_COMMERCIAL = "com.streamkeepalive.app.CANCEL_COMMERCIAL"
        private const val ACTION_TOGGLE_MAIN_POWER = "com.streamkeepalive.app.TOGGLE_MAIN_POWER"
        private const val ACTION_TOGGLE_ZONE2_POWER = "com.streamkeepalive.app.TOGGLE_ZONE2_POWER"
        private const val ACTION_QUERY_ZONES = "com.streamkeepalive.app.QUERY_ZONES"
        private const val ACTION_DPAD = "com.streamkeepalive.app.DPAD"
        private const val ACTION_SOUND_MODE = "com.streamkeepalive.app.SOUND_MODE"
        private const val ACTION_STOP = "com.streamkeepalive.app.STOP"
        private const val EXTRA_DURATION = "duration_seconds"
        private const val EXTRA_BUTTON = "button"
        private const val EXTRA_MODE = "mode"

        fun connect(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java).setAction(ACTION_CONNECT))
        }

        fun startLoop(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java).setAction(ACTION_START_LOOP))
        }

        fun stopLoop(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP_LOOP))
        }

        fun startCommercialAssist(context: Context, durationSeconds: Int) {
            context.startForegroundService(
                Intent(context, KeepAliveService::class.java)
                    .setAction(ACTION_START_COMMERCIAL)
                    .putExtra(EXTRA_DURATION, durationSeconds)
            )
        }

        fun cancelCommercialAssist(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_CANCEL_COMMERCIAL))
        }

        fun toggleMainPower(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_TOGGLE_MAIN_POWER))
        }

        fun toggleZone2Power(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_TOGGLE_ZONE2_POWER))
        }

        fun queryZones(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_QUERY_ZONES))
        }

        fun sendDpad(context: Context, button: String) {
            context.startService(
                Intent(context, KeepAliveService::class.java).setAction(ACTION_DPAD).putExtra(EXTRA_BUTTON, button)
            )
        }

        fun sendSoundMode(context: Context, mode: String) {
            context.startService(
                Intent(context, KeepAliveService::class.java).setAction(ACTION_SOUND_MODE).putExtra(EXTRA_MODE, mode)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP))
        }
    }
}
