package com.streamkeepalive.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamkeepalive.app.denon.DenonClient
import com.streamkeepalive.app.service.KeepAliveService
import com.streamkeepalive.app.state.KeepAliveState
import com.streamkeepalive.app.voice.VoiceDurationRecognizer
import com.streamkeepalive.app.webos.TvPrefs
import com.streamkeepalive.app.webos.WebOsConnectionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: TvPrefs
    private val denonClient = DenonClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TvPrefs(this)
        KeepAliveState.loopIntervalSeconds.value = prefs.loopIntervalSeconds
        KeepAliveState.commercialDurationSeconds.value = prefs.commercialDurationSeconds

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppScaffold(prefs = prefs)
            }
        }
    }

    // While a receiver IP is configured, the hardware volume rocker controls the Denon
    // instead of the phone's own media volume — only takes effect while this app is in
    // the foreground, since Android doesn't allow intercepting these keys globally.
    // dispatchKeyEvent (not onKeyDown/onKeyUp) is used because it runs before Compose's
    // own focus-based key handling can swallow the event first.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (prefs.receiverIp.isNotBlank() && isVolumeKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                // Zone 2 ("Lanai") takes over the rocker while it's on; otherwise Main.
                if (KeepAliveState.zone2On.value == true) {
                    lifecycleScope.launch { denonClient.stepZone2Volume(prefs.receiverIp, up = up) }
                } else {
                    lifecycleScope.launch { denonClient.stepVolume(prefs.receiverIp, up = up) }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(prefs: TvPrefs) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("StreamKeepAlive") },
                actions = {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp).size(28.dp),
                        tint = Color.Unspecified
                    )
                }
            )
        }
    ) { innerPadding ->
        HomeScreen(modifier = Modifier.padding(innerPadding), prefs = prefs)
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, prefs: TvPrefs) {
    val context = LocalContext.current
    val connectionState by KeepAliveState.connectionState.collectAsStateWithLifecycle()
    val connectionError by KeepAliveState.connectionError.collectAsStateWithLifecycle()
    val loopRunning by KeepAliveState.loopRunning.collectAsStateWithLifecycle()
    val countdown by KeepAliveState.countdownRemaining.collectAsStateWithLifecycle()
    val serviceRunning by KeepAliveState.serviceRunning.collectAsStateWithLifecycle()
    val intervalSeconds by KeepAliveState.loopIntervalSeconds.collectAsStateWithLifecycle()
    val commercialSeconds by KeepAliveState.commercialDurationSeconds.collectAsStateWithLifecycle()

    val mainZoneOn by KeepAliveState.mainZoneOn.collectAsStateWithLifecycle()
    val zone2On by KeepAliveState.zone2On.collectAsStateWithLifecycle()
    val activeSoundMode by KeepAliveState.activeSoundMode.collectAsStateWithLifecycle()

    var ipField by remember { mutableStateOf(prefs.tvIp) }
    var receiverIpField by remember { mutableStateOf(prefs.receiverIp) }
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var connectExpanded by remember { mutableStateOf(true) }
    var remoteExpanded by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }

    val voiceRecognizer = remember { VoiceDurationRecognizer(context) }
    DisposableEffect(Unit) { onDispose { voiceRecognizer.destroy() } }

    // Refresh Denon zone power state whenever the remote panel is opened or the TV pairs,
    // rather than continuously polling in the background.
    LaunchedEffect(connectionState, remoteExpanded) {
        if (remoteExpanded && connectionState == WebOsConnectionState.PAIRED) {
            KeepAliveService.queryZones(context)
        }
    }

    // Auto-collapse the connect panel once paired, to free up space for the loop/
    // commercial-assist controls. Chevron button lets the user reopen it (e.g. to Stop).
    LaunchedEffect(connectionState) {
        if (connectionState == WebOsConnectionState.PAIRED) connectExpanded = false
    }

    // Android 13+ requires POST_NOTIFICATIONS before a foreground service can post its
    // ongoing notification; request it once, then run whichever action was requested.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }
    fun runWithNotifPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingAfterPermission = action
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun startVoiceListening() {
        isListening = true
        voiceRecognizer.start(
            onRecognized = { seconds ->
                isListening = false
                KeepAliveState.commercialDurationSeconds.value = seconds
                prefs.commercialDurationSeconds = seconds
                runWithNotifPermission { KeepAliveService.startCommercialAssist(context, seconds) }
            },
            onDone = { isListening = false }
        )
    }

    // RECORD_AUDIO is dangerous-level; request it once, then start listening.
    val recordAudioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceListening() }

    fun runWithRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            recordAudioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startVoiceListening()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = statusColor(connectionState, loopRunning, countdown))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        statusText(connectionState, loopRunning, countdown, connectionError),
                        style = typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { connectExpanded = !connectExpanded }) {
                        Icon(
                            if (connectExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (connectExpanded) "Collapse" else "Expand"
                        )
                    }
                }
                if (connectExpanded) {
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = ipField,
                        onValueChange = { ipField = it },
                        label = { Text("TV IP address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = receiverIpField,
                        onValueChange = { receiverIpField = it },
                        label = { Text("Receiver IP address (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            prefs.tvIp = ipField
                            prefs.receiverIp = receiverIpField
                            runWithNotifPermission { KeepAliveService.connect(context) }
                        }) {
                            Text("Pair / Connect")
                        }
                        if (serviceRunning) {
                            Button(onClick = { KeepAliveService.stop(context) }) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remote controller", style = typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { remoteExpanded = !remoteExpanded }) {
                        Icon(
                            if (remoteExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (remoteExpanded) "Collapse" else "Expand"
                        )
                    }
                }
                if (remoteExpanded) {
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PowerButton(
                            label = "Main",
                            on = mainZoneOn == true,
                            modifier = Modifier.weight(1f)
                        ) { KeepAliveService.toggleMainPower(context) }
                        PowerButton(
                            label = "Lanai",
                            on = zone2On == true,
                            modifier = Modifier.weight(1f)
                        ) { KeepAliveService.toggleZone2Power(context) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        NavButton(Icons.Filled.KeyboardArrowUp, "Up") { KeepAliveService.sendDpad(context, "UP") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavButton(Icons.Filled.KeyboardArrowLeft, "Left") { KeepAliveService.sendDpad(context, "LEFT") }
                            Button(onClick = { KeepAliveService.sendDpad(context, "ENTER") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("OK")
                            }
                            NavButton(Icons.Filled.KeyboardArrowRight, "Right") { KeepAliveService.sendDpad(context, "RIGHT") }
                        }
                        NavButton(Icons.Filled.KeyboardArrowDown, "Down") { KeepAliveService.sendDpad(context, "DOWN") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundModeButton(
                            label = "Movie",
                            active = activeSoundMode == "MOVIE",
                            modifier = Modifier.weight(1f)
                        ) { KeepAliveService.sendSoundMode(context, "MOVIE") }
                        SoundModeButton(
                            label = "Music",
                            active = activeSoundMode == "MUSIC",
                            modifier = Modifier.weight(1f)
                        ) { KeepAliveService.sendSoundMode(context, "MUSIC") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep-alive loop", style = typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (loopRunning) {
                                KeepAliveService.stopLoop(context)
                            } else {
                                runWithNotifPermission { KeepAliveService.startLoop(context) }
                            }
                        }
                    ) {
                        Text(if (loopRunning) "Stop" else "Start")
                    }
                }
                Spacer(Modifier.height(12.dp))
                IntSlider(
                    label = "Interval",
                    valueLabel = "${intervalSeconds / 60} min",
                    value = intervalSeconds,
                    range = 60..600,
                    step = 60,
                    onChange = { KeepAliveState.loopIntervalSeconds.value = it },
                    onCommit = { prefs.loopIntervalSeconds = intervalSeconds }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Commercial-break", style = typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            when {
                                countdown != null -> KeepAliveService.cancelCommercialAssist(context)
                                isListening -> { voiceRecognizer.cancel(); isListening = false }
                                else -> runWithRecordAudioPermission()
                            }
                        }
                    ) {
                        Text(if (countdown != null) "Stop" else if (isListening) "Listening…" else "Start")
                    }
                }
                Spacer(Modifier.height(12.dp))
                IntSlider(
                    label = "Break length",
                    valueLabel = if (countdown != null) "${countdown}s left" else "${commercialSeconds}s",
                    value = commercialSeconds,
                    range = 15..240,
                    step = 15,
                    onChange = {
                        if (isListening) { voiceRecognizer.cancel(); isListening = false }
                        KeepAliveState.commercialDurationSeconds.value = it
                    },
                    onCommit = { prefs.commercialDurationSeconds = commercialSeconds }
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(14.dp)) {}
}

@Composable
private fun PowerButton(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) colorScheme.primary else colorScheme.surfaceVariant,
            contentColor = if (on) colorScheme.onPrimary else colorScheme.onSurfaceVariant
        )
    ) {
        Text(label)
    }
}

@Composable
private fun NavButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
private fun SoundModeButton(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF8A5FE0) else colorScheme.surfaceVariant,
            contentColor = if (active) Color.Black else colorScheme.onSurfaceVariant
        )
    ) {
        Text(label)
    }
}

private fun statusColor(state: WebOsConnectionState, loopRunning: Boolean, countdown: Int?): Color = when {
    countdown != null -> Color(0xFFE8C547)
    loopRunning -> Color(0xFF4CAF50)
    state == WebOsConnectionState.PAIRED -> Color(0xFF4CAF50)
    state == WebOsConnectionState.AWAITING_TV_PROMPT -> Color(0xFFE8C547)
    state == WebOsConnectionState.CONNECTING -> Color(0xFF7FB2F0)
    state == WebOsConnectionState.ERROR -> Color(0xFFE05252)
    else -> Color(0xFF666666)
}

private fun statusText(
    state: WebOsConnectionState,
    loopRunning: Boolean,
    countdown: Int?,
    error: String?
): String = when {
    countdown != null -> "Muted — ${countdown}s left"
    loopRunning -> "Keep-alive running"
    state == WebOsConnectionState.PAIRED -> "Connected"
    state == WebOsConnectionState.AWAITING_TV_PROMPT -> "Check your TV screen to allow pairing"
    state == WebOsConnectionState.CONNECTING -> "Connecting…"
    state == WebOsConnectionState.ERROR -> "Error: ${error ?: "connection failed"}"
    else -> "Not connected"
}

@Composable
private fun IntSlider(
    label: String,
    valueLabel: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: $valueLabel", style = typography.bodyLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = (Math.round(it / step) * step).coerceIn(range.first, range.last)
                onChange(snapped)
            },
            onValueChangeFinished = onCommit,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1
        )
    }
}
