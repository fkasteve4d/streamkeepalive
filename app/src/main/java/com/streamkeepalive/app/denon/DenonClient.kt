package com.streamkeepalive.app.denon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "DenonClient"
private const val PORT = 23
private const val CONNECT_TIMEOUT_MS = 3000
private const val QUERY_READ_TIMEOUT_MS = 2000

/**
 * Sends commands to a Denon/Marantz AVR over its classic plaintext control protocol
 * (raw TCP, port 23) — the same interface home-automation tools use, and more reliable
 * than HEOS's own app protocol. Opens a short-lived connection per command rather than
 * holding one open, since calls here are infrequent (mute toggles, discrete volume steps,
 * occasional power/zone queries).
 */
class DenonClient {
    suspend fun setMute(ip: String, mute: Boolean) =
        send(ip, if (mute) "MUON" else "MUOFF")

    /** Relative volume step, matching a physical rocker press — no absolute level tracked. */
    suspend fun stepVolume(ip: String, up: Boolean) =
        send(ip, if (up) "MVUP" else "MVDOWN")

    suspend fun stepZone2Volume(ip: String, up: Boolean) =
        send(ip, if (up) "Z2UP" else "Z2DOWN")

    /** Sound mode preset, matching the Denon remote's dedicated buttons (e.g. "MOVIE", "MUSIC"). */
    suspend fun sendSoundMode(ip: String, mode: String) = send(ip, "MS$mode")

    /** Turning a zone on also wakes the whole unit from standby first (PWON) — a no-op if
     *  already on. The delay gives the receiver's telnet server time to close the first
     *  connection before a second one arrives; some models drop back-to-back connections. */
    suspend fun setMainZonePower(ip: String, on: Boolean) {
        if (on) { send(ip, "PWON"); delay(300) }
        send(ip, if (on) "ZMON" else "ZMOFF")
    }

    suspend fun setZone2Power(ip: String, on: Boolean) {
        if (on) { send(ip, "PWON"); delay(300) }
        send(ip, if (on) "Z2ON" else "Z2OFF")
    }

    /** Null means the query failed/timed out (unit off, unreachable, etc.), not "off". */
    suspend fun isMainZoneOn(ip: String): Boolean? {
        val response = query(ip, "ZM?") ?: return null
        return when {
            response.contains("ZMON") -> true
            response.contains("ZMOFF") -> false
            else -> null
        }
    }

    suspend fun isZone2On(ip: String): Boolean? {
        val response = query(ip, "Z2?") ?: return null
        return when {
            response.contains("Z2ON") -> true
            response.contains("Z2OFF") -> false
            else -> null
        }
    }

    private suspend fun send(ip: String, command: String) {
        if (ip.isBlank()) {
            Log.w(TAG, "send($command) skipped — no receiver IP configured")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
                    socket.getOutputStream().write("$command\r".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                Log.d(TAG, "sent $command to $ip")
            } catch (e: Exception) {
                Log.w(TAG, "command $command to $ip failed", e)
            }
        }
    }

    private suspend fun query(ip: String, command: String): String? {
        if (ip.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = QUERY_READ_TIMEOUT_MS
                    socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
                    socket.getOutputStream().write("$command\r".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val response = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                    Log.d(TAG, "query $command to $ip -> $response")
                    response
                }
            } catch (e: Exception) {
                Log.w(TAG, "query $command to $ip failed", e)
                null
            }
        }
    }
}
