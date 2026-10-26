package com.example.granularsynth

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

private val bg = Color(0xFF080A0C)
private val panel = Color(0xFF111518)
private val panel2 = Color(0xFF171D20)
private val textMain = Color(0xFFDAEAF2)
private val textMuted = Color(0xFF8B9DA6)
private val accents = listOf(Color(0xFFFF542E), Color(0xFFFF542E), Color(0xFFFF542E), Color(0xFFFF542E))

class MainActivity : ComponentActivity() {
    private val engine = GranularEngine()
    private var pendingTrack by mutableIntStateOf(0)
    private val openWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadWav(uri, pendingTrack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start()
        setContent { SynthApp(engine, onLoad = { track -> pendingTrack = track; openWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) }
    }

    private fun loadWav(uri: Uri, track: Int) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                // Minimal PCM WAV reader: mono/stereo 16-bit PCM. Other encodings are rejected safely.
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return
                var offset = 12
                var channels = 1
                var bits = 16
                var format = 1
                var dataOffset = -1
                var dataSize = 0
                while (offset + 8 <= bytes.size) {
                    val id = String(bytes, offset, 4)
                    val size = bb.getInt(offset + 4).coerceAtLeast(0)
                    val body = offset + 8
                    if (id == "fmt " && body + 16 <= bytes.size) {
                        format = bb.getShort(body).toInt()
                        channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                        bits = bb.getShort(body + 14).toInt()
                    }
                    if (id == "data") { dataOffset = body; dataSize = min(size, bytes.size - body); break }
                    offset = body + size + (size % 2)
                }
                if (format != 1 || bits != 16 || dataOffset < 0) return
                val frames = dataSize / (2 * channels)
                if (frames <= 0) return
                val stereo = FloatArray(frames * 2)
                for (i in 0 until frames) {
                    val left = bb.getShort(dataOffset + (i * channels) * 2) / 32768f
                    val right = if (channels >= 2) bb.getShort(dataOffset + (i * channels + 1) * 2) / 32768f else left
                    stereo[i * 2] = left
                    stereo[i * 2 + 1] = right
                }
                engine.tracks[track].sampleStereo = stereo
                engine.tracks[track].frameCount = frames
                engine.tracks[track].name = uri.lastPathSegment?.substringAfterLast('/')?.take(22) ?: "WAV SAMPLE"
            }
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}

@Composable
private fun SynthApp(engine: GranularEngine, onLoad: (Int) -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var showMapping by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    MaterialTheme(colorScheme = darkColorScheme(
        background = bg, surface = panel, primary = accents[0], onBackground = textMain,
        onSurface = textMain
    )) {
        Row(Modifier.fillMaxSize().background(bg).focusRequester(focusRequester).focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { if (page == 0) selected = (selected + 3) % 4 else page = (page + 3) % 4; true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (page == 0) selected = (selected + 1) % 4 else page = (page + 1) % 4; true }
                        KeyEvent.KEYCODE_BUTTON_A -> { if (page == 3) onLoad(selected) else engine.playing = !engine.playing; true }
                        KeyEvent.KEYCODE_BUTTON_X -> { engine.tracks[selected].muted = !engine.tracks[selected].muted; refresh++; true }
                        KeyEvent.KEYCODE_BUTTON_L1 -> { page = (page + 3) % 4; true }
                        KeyEvent.KEYCODE_BUTTON_R1 -> { page = (page + 1) % 4; true }
                        KeyEvent.KEYCODE_BUTTON_L2 -> { engine.tracks[selected].density = (engine.tracks[selected].density - .04f).coerceAtLeast(0f); refresh++; true }
                        KeyEvent.KEYCODE_BUTTON_R2 -> { engine.tracks[selected].density = (engine.tracks[selected].density + .04f).coerceAtMost(1f); refresh++; true }
                        KeyEvent.KEYCODE_BUTTON_Y -> { engine.tracks[selected].solo = !engine.tracks[selected].solo; refresh++; true }
                        KeyEvent.KEYCODE_BUTTON_B -> { page = (page + 3) % 4; true }
                        KeyEvent.KEYCODE_BUTTON_START -> { engine.playing = !engine.playing; true }
                        else -> false
                    }
                } else false
            }) {
            Column(Modifier.width(145.dp).fillMaxHeight().background(Color(0xFF0D1012)).padding(12.dp)) {
                Text("GRNLR", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textMain)
                Text("GRANULAR GROOVEBOX", fontWeight = FontWeight.Medium, fontSize = 10.sp, color = textMuted)
                Spacer(Modifier.height(28.dp))
                listOf("TRACKS", "FX", "MIXER", "SAMPLES").forEachIndexed { i, label ->
                    NavButton(label, page == i) { page = i }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(if (engine.playing) "● AUDIO RUNNING" else "Ⅱ STOPPED", color = if (engine.playing) accents[1] else textMuted, fontSize = 10.sp)
                Text("v0.4 • STEREO", color = textMuted, fontSize = 9.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth().height(42.dp).background(panel, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOf("TRACKS", "EFFECTS", "MIXER", "SAMPLES")[page], fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text("INIT PATCH", color = textMuted, fontSize = 11.sp)
                    Spacer(Modifier.width(20.dp))
                    Button(onClick = { engine.playing = !engine.playing }, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                        Text(if (engine.playing) "Ⅱ  STOP" else "▶  PLAY")
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (page) {
                    0 -> TracksPage(engine, selected, { selected = it }, onLoad, refresh) { refresh++ }
                    1 -> EffectsPage(engine) { refresh++ }
                    2 -> MixerPage(engine, refresh) { refresh++ }
                    else -> SamplesPage(engine, onLoad = onLoad, onMapping = { showMapping = true })
                }
            }
        }
    }
    if (showMapping) {
        AlertDialog(onDismissRequest = { showMapping = false }, confirmButton = { TextButton2("CLOSE") { showMapping = false } },
            title = { Text("PS4 / DualShock mapping") },
            text = { Text("D-pad ← / →  Select track\nCross (X)  Play / Stop\nL2 / R2  Density − / +\nTriangle (△)  Solo selected track\nCircle (○)  Mute selected track\nOptions  Next page\n\nStick axes vary between Android devices. Pair the controller in Android Bluetooth settings before opening the app.") })
    }
}

@Composable private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(42.dp).background(if (active) Color(0xFF303E46) else panel, RoundedCornerShape(6.dp))
        .then(if (active) Modifier.border(1.dp, accents[0], RoundedCornerShape(6.dp)) else Modifier)
        .clickable { onClick() }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = if (active) textMain else textMuted)
    }
}

@Composable private fun TracksPage(engine: GranularEngine, selected: Int, onSelect: (Int) -> Unit, onLoad: (Int) -> Unit, refresh: Int, changed: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        engine.tracks.forEachIndexed { i, t ->
            Column(Modifier.weight(1f).fillMaxHeight().background(panel, RoundedCornerShape(8.dp))
                .border(if (selected == i) 1.dp else 0.dp, if (selected == i) accents[i] else panel, RoundedCornerShape(8.dp))
                .clickable { onSelect(i) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TRACK ${i + 1}", color = accents[i], fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (t.muted) "M" else if (t.solo) "S" else "●", color = if (t.muted || t.solo) accents[i] else textMuted, fontSize = 11.sp)
                }
                Waveform(t.frameCount, accents[i])
                Text(t.name, fontSize = 11.sp, color = textMuted, maxLines = 1)
                Button(onClick = { onLoad(i) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("LOAD WAV", fontSize = 10.sp) }
                ParamSlider("START", t.start, accents[i], { t.start = it; changed() })
                ParamSlider("GRAIN SIZE", t.grainSize, accents[i], { t.grainSize = it; changed() })
                ParamSlider("DENSITY", t.density, accents[i], { t.density = it; changed() })
                Spacer(Modifier.weight(1f))
                ParamSlider("VOLUME", t.volume, accents[i], { t.volume = it; changed() })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { t.solo = !t.solo; changed() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(if (t.solo) "SOLO ✓" else "SOLO", fontSize = 10.sp) }
                    OutlinedButton(onClick = { t.muted = !t.muted; changed() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(if (t.muted) "MUTED" else "MUTE", fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable private fun Waveform(size: Int, accent: Color) {
    Row(Modifier.fillMaxWidth().height(54.dp).background(Color(0xFF0B0D0F), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(38) { i ->
            val h = if (size == 0) (3 + ((i * 17) % 9)).dp else (5 + ((i * 13 + size / 100) % 32)).dp
            Box(Modifier.weight(1f).height(h).background(accent.copy(alpha = if (size == 0) .35f else .9f), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable private fun ParamSlider(label: String, value: Float, accent: Color, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", color = textMain, fontSize = 10.sp)
        }
        Slider(value = value.coerceIn(0f,1f), onValueChange = onChange, modifier = Modifier.height(28.dp),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF414B50)))
    }
}

@Composable private fun EffectsPage(engine: GranularEngine, changed: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EffectCard("ECHO", "Stereo delay with feedback") {
            ParamSlider("TIME", engine.echoTime, accents[0], { engine.echoTime = it; changed() })
            ParamSlider("FEEDBACK", engine.echoFeedback, accents[0], { engine.echoFeedback = it * .85f; changed() })
            ParamSlider("MIX", engine.echoMix, accents[0], { engine.echoMix = it; changed() })
        }
        EffectCard("REVERB", "Feedback ambience") {
            ParamSlider("SIZE", engine.reverbSize, accents[1], { engine.reverbSize = it; changed() })
            ParamSlider("MIX", engine.reverbMix, accents[1], { engine.reverbMix = it; changed() })
        }
        EffectCard("SATURATION", "Soft clipping / harmonic drive") {
            ParamSlider("DRIVE", engine.saturation, accents[2], { engine.saturation = it; changed() })
        }
        Text("Effects are applied to the master output in this starter version.", color = textMuted, fontSize = 11.sp)
    }
}
@Composable private fun EffectCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = textMain, fontSize = 14.sp)
        Text(subtitle, color = textMuted, fontSize = 10.sp)
        content()
    }
}

@Composable private fun MixerPage(engine: GranularEngine, refresh: Int, changed: () -> Unit) {
    Column(Modifier.fillMaxSize().background(panel, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            engine.tracks.forEachIndexed { i, t ->
                Column(Modifier.weight(1f).fillMaxHeight().background(panel2, RoundedCornerShape(6.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TRACK ${i + 1}", color = accents[i], fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("${(t.volume * 100).toInt()}%", fontSize = 11.sp)
                    Slider(value = t.volume, onValueChange = { t.volume = it; changed() }, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = accents[i], activeTrackColor = accents[i]))
                    Text("PAN", color = textMuted, fontSize = 10.sp)
                    Slider(value = (t.pan + 1f) / 2f, onValueChange = { t.pan = it * 2f - 1f; changed() }, colors = SliderDefaults.colors(thumbColor = accents[i], activeTrackColor = accents[i]))
                    Row {
                        TextButton2(if (t.solo) "S ✓" else "S") { t.solo = !t.solo; changed() }
                        TextButton2(if (t.muted) "M ✓" else "M") { t.muted = !t.muted; changed() }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF171D20), RoundedCornerShape(6.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MASTER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("${(engine.masterVolume * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = engine.masterVolume, onValueChange = { engine.masterVolume = it }, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = textMain, activeTrackColor = textMain))
                Text("OUTPUT", color = textMuted, fontSize = 10.sp)
            }
        }
        Text("MASTER BUS  •  ECHO  →  REVERB  →  SATURATION", color = textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}


@Composable private fun SamplesPage(engine: GranularEngine, onLoad: (Int) -> Unit, onMapping: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1.2f).fillMaxHeight().background(panel, RoundedCornerShape(4.dp)).padding(14.dp)) {
            Text("DEVICE / SAMPLES", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("Choose a track with the D-pad, then press X to load a WAV file.", color = textMuted, fontSize = 11.sp)
            engine.tracks.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().clickable { onLoad(i) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("T${i + 1}", color = accents[i], fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                    Text(t.name, color = textMain, modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                    Text(if (t.frameCount <= 0) "EMPTY" else String.format("%.1f s", t.frameCount / 44100f), color = textMuted, fontSize = 10.sp)
                }
                HorizontalDivider(color = Color(0xFF4A555A))
            }
            Spacer(Modifier.height(8.dp))
            Text("LOAD SAMPLE", color = accents[0], fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Android system file picker opens after LOAD is selected. Stereo PCM 16-bit WAV is preserved.", color = textMuted, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(panel, RoundedCornerShape(4.dp)).padding(14.dp)) {
            Text("GAMEPAD", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("D-pad: navigate / select", color = textMuted, fontSize = 11.sp)
            Text("X: confirm / play-stop", color = textMuted, fontSize = 11.sp)
            Text("O: back", color = textMuted, fontSize = 11.sp)
            Text("L1 / R1: previous / next page", color = textMuted, fontSize = 11.sp)
            Text("L2 / R2: density − / +", color = textMuted, fontSize = 11.sp)
            Text("△: solo selected track", color = textMuted, fontSize = 11.sp)
            Text("□: mute selected track", color = textMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Button(onClick = onMapping) { Text("FULL MAPPING") }
        }
    }
}

@Composable private fun SettingsPage(engine: GranularEngine, onMapping: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().background(panel, RoundedCornerShape(8.dp)).padding(16.dp)) {
            Text("AUDIO ENGINE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Text("Output: Android AudioTrack", color = textMuted, fontSize = 12.sp)
            Text("Sample rate: 44.1 kHz", color = textMuted, fontSize = 12.sp)
            Text("Tracks: 4 mono sample buffers", color = textMuted, fontSize = 12.sp)
            Text("Input: PCM 16-bit WAV", color = textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Text("MASTER VOLUME", color = textMuted, fontSize = 10.sp)
            ParamSlider("LEVEL", engine.masterVolume, accents[0], { engine.masterVolume = it })
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(panel, RoundedCornerShape(8.dp)).padding(16.dp)) {
            Text("CONTROLLER", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("PS4 / DualShock 4", color = accents[1], fontSize = 12.sp)
            Text("Pair with Android Bluetooth settings before opening the app.", color = textMuted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text("D-pad: select track    X: play/stop", color = textMuted, fontSize = 11.sp)
            Text("L2 / R2: density       △: solo", color = textMuted, fontSize = 11.sp)
            Text("○: mute                Options: next page", color = textMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Button(onClick = onMapping) { Text("VIEW FULL MAPPING") }
        }
    }
}

@Composable private fun TextButton2(label: String, onClick: () -> Unit) {
    Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp).clickable { onClick() }, fontSize = 11.sp, color = textMain)
}
