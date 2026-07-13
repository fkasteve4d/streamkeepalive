package com.streamkeepalive.app.webos

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class WebOsConnectionState { DISCONNECTED, CONNECTING, AWAITING_TV_PROMPT, PAIRED, ERROR }

private const val TAG = "WebOsClient"
private const val REGISTER_ID = "register_0"

/**
 * Talks to an LG webOS TV over its local SSAP WebSocket control channel (the same
 * channel the TV's own remote-control apps use). Pairs once (TV shows an on-screen
 * prompt), then sends play/pause/mute commands.
 */
class WebOsClient(
    private val onStateChanged: (WebOsConnectionState, String?) -> Unit,
    private val onPaired: (clientKey: String) -> Unit
) {
    private val httpClient = buildTrustingHttpClient()
    private var webSocket: WebSocket? = null
    private var requestCounter = 0
    private var paired = false

    // D-pad navigation goes over a *second* WebSocket, whose URL webOS hands back from a
    // request on the main socket — requested lazily on first button press, then reused.
    private var pointerSocket: WebSocket? = null
    private var pointerRequestId: String? = null
    private val pendingButtons = mutableListOf<String>()

    fun connect(ip: String, existingClientKey: String?) {
        paired = false
        onStateChanged(WebOsConnectionState.CONNECTING, null)
        tryConnect(ip, existingClientKey, useTls = true)
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        paired = false
        pointerSocket?.close(1000, "bye")
        pointerSocket = null
        pointerRequestId = null
        pendingButtons.clear()
    }

    fun sendPlay() = sendCommand("ssap://media.controls/play")
    fun sendPause() = sendCommand("ssap://media.controls/pause")
    fun turnOff() = sendCommand("ssap://system/turnOff")
    fun setMute(mute: Boolean) =
        sendCommand("ssap://audio/setMute", JSONObject().put("mute", mute))

    /** Sends a D-pad button press: "UP", "DOWN", "LEFT", "RIGHT", or "ENTER". */
    fun sendButton(name: String) {
        val ps = pointerSocket
        if (ps != null) {
            ps.send("type:button\nname:$name\n\n")
            return
        }
        pendingButtons.add(name)
        if (pointerRequestId == null) {
            val id = "pointer_${requestCounter++}"
            pointerRequestId = id
            val message = JSONObject()
                .put("type", "request")
                .put("id", id)
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                .put("payload", JSONObject())
            webSocket?.send(message.toString())
        }
    }

    private fun connectPointerSocket(url: String) {
        val request = Request.Builder().url(url).build()
        pointerSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                pendingButtons.forEach { ws.send("type:button\nname:$it\n\n") }
                pendingButtons.clear()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "pointer socket failed", t)
                pointerSocket = null
                pointerRequestId = null
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                pointerSocket = null
                pointerRequestId = null
            }
        })
    }

    private fun tryConnect(ip: String, existingClientKey: String?, useTls: Boolean) {
        // webOS exposes an unencrypted port (3000, older/legacy) and a TLS port with a
        // self-signed cert (3001, current). Try TLS first, fall back to plain on failure.
        val url = if (useTls) "wss://$ip:3001" else "ws://$ip:3000"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(registerPayload(existingClientKey))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "connect failed on ${if (useTls) "wss:3001" else "ws:3000"}", t)
                if (useTls) {
                    tryConnect(ip, existingClientKey, useTls = false)
                } else {
                    onStateChanged(WebOsConnectionState.ERROR, t.message ?: "Connection failed")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                paired = false
                onStateChanged(WebOsConnectionState.DISCONNECTED, null)
            }
        })
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) {
            Log.w(TAG, "unparseable message: $text"); return
        }
        when (json.optString("type")) {
            "response" -> {
                val id = json.optString("id")
                if (id == REGISTER_ID) {
                    val pairingType = json.optJSONObject("payload")?.optString("pairingType")
                    if (pairingType == "PROMPT") {
                        onStateChanged(WebOsConnectionState.AWAITING_TV_PROMPT, null)
                    }
                } else if (id == pointerRequestId) {
                    val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                    if (!socketPath.isNullOrEmpty()) connectPointerSocket(socketPath)
                }
            }
            "registered" -> {
                val key = json.optJSONObject("payload")?.optString("client-key")
                if (!key.isNullOrEmpty()) {
                    paired = true
                    onPaired(key)
                    onStateChanged(WebOsConnectionState.PAIRED, null)
                }
            }
            "error" -> {
                onStateChanged(WebOsConnectionState.ERROR, json.optString("error", "Unknown TV error"))
            }
        }
    }

    private fun sendCommand(uri: String, payload: JSONObject = JSONObject()) {
        val ws = webSocket
        if (ws == null || !paired) {
            Log.w(TAG, "sendCommand($uri) skipped — not paired/connected")
            return
        }
        val message = JSONObject()
            .put("type", "request")
            .put("id", "cmd_${requestCounter++}")
            .put("uri", uri)
            .put("payload", payload)
        ws.send(message.toString())
    }

    private fun registerPayload(existingClientKey: String?): String {
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.1")
            .put(
                "permissions",
                JSONArray(
                    listOf(
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                        "CONTROL_AUDIO", "CONTROL_DISPLAY",
                        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                        "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
                        "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
                        "READ_RUNNING_APPS", "READ_NETWORK_STATE",
                        "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE"
                    )
                )
            )
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        if (!existingClientKey.isNullOrEmpty()) {
            payload.put("client-key", existingClientKey)
        }
        return JSONObject()
            .put("type", "register")
            .put("id", REGISTER_ID)
            .put("payload", payload)
            .toString()
    }

    private fun buildTrustingHttpClient(): OkHttpClient {
        // webOS's local control socket uses a self-signed TLS cert with no public CA chain.
        // This trust-all context is scoped to this one client instance, which only ever
        // connects to the IP the user entered for their own TV on the local network.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
