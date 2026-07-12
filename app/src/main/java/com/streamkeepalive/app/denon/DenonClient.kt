package com.streamkeepalive.app.denon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "DenonClient"
private const val PORT = 23
private const val CONNECT_TIMEOUT_MS = 3000

/**
 * Sends commands to a Denon/Marantz AVR over its classic plaintext control protocol
 * (raw TCP, port 23) — the same interface home-automation tools use, and more reliable
 * than HEOS's own app protocol. Opens a short-lived connection per command rather than
 * holding one open, since calls here are infrequent (mute toggles, discrete volume steps).
 */
class DenonClient {
    suspend fun setMute(ip: String, mute: Boolean) =
        send(ip, if (mute) "MUON" else "MUOFF")

    /** Relative volume step, matching a physical rocker press — no absolute level tracked. */
    suspend fun stepVolume(ip: String, up: Boolean) =
        send(ip, if (up) "MVUP" else "MVDOWN")

    private suspend fun send(ip: String, command: String) {
        if (ip.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
                    socket.getOutputStream().write("$command\r".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "command $command to $ip failed", e)
            }
        }
    }
}
