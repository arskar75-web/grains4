package com.example.granularsynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Stereo granular playback engine.
 * Each sample is stored as interleaved stereo frames: L, R, L, R...
 * Mono WAV files are duplicated to both channels when imported.
 */
data class TrackState(
    @Volatile var sampleStereo: FloatArray = FloatArray(0),
    @Volatile var frameCount: Int = 0,
    @Volatile var start: Float = 0.12f,
    @Volatile var grainSize: Float = 0.25f,
    @Volatile var density: Float = 0.45f,
    @Volatile var volume: Float = 0.75f,
    @Volatile var pan: Float = 0f,
    @Volatile var muted: Boolean = false,
    @Volatile var solo: Boolean = false,
    @Volatile var name: String = "EMPTY"
)

class GranularEngine {
    val tracks = List(4) { TrackState() }
    @Volatile var playing = true
    @Volatile var masterVolume = 0.8f
    @Volatile var echoMix = 0.18f
    @Volatile var echoFeedback = 0.32f
    @Volatile var echoTime = 0.28f
    @Volatile var reverbMix = 0.16f
    @Volatile var reverbSize = 0.55f
    @Volatile var saturation = 0.12f

    private val running = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null
    private val sr = 44100
    private val blockFrames = 256
    private val delayL = FloatArray(sr * 2)
    private val delayR = FloatArray(sr * 2)
    private var delayIndex = 0
    private var reverbAL = 0f
    private var reverbAR = 0f
    private var reverbBL = 0f
    private var reverbBR = 0f
    private val positions = FloatArray(4) { 0f }
    private val phases = FloatArray(4) { 0f }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(blockFrames * 4)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack = at
        at.play()
        thread = Thread {
            val out = ShortArray(blockFrames * 2)
            while (running.get()) {
                for (i in 0 until blockFrames) {
                    var dryL = 0f
                    var dryR = 0f
                    if (playing) {
                        val anySolo = tracks.any { it.solo }
                        tracks.forEachIndexed { idx, t ->
                            val s = t.sampleStereo
                            val frames = t.frameCount
                            if (frames > 32 && s.size >= frames * 2 && !t.muted && (!anySolo || t.solo)) {
                                val grainLength = max(128, (frames * t.grainSize.coerceIn(0.02f, 0.9f)).toInt()).coerceAtMost(frames)
                                val hop = max(64f, sr * (0.015f + (1f - t.density.coerceIn(0f, 1f)) * 0.16f))
                                if (positions[idx] <= 0f || positions[idx] >= grainLength.toFloat()) {
                                    positions[idx] = 1f
                                    phases[idx] = (t.start.coerceIn(0f, 1f) * (frames - grainLength)).coerceAtLeast(0f)
                                }
                                val frame = (phases[idx].toInt() + positions[idx].toInt()).coerceIn(0, frames - 1)
                                val window = (0.5 - 0.5 * kotlin.math.cos(2.0 * PI *
                                    (positions[idx] / grainLength).coerceIn(0f, 1f))).toFloat()
                                val l = s[frame * 2] * window * t.volume
                                val r = s[frame * 2 + 1] * window * t.volume

                                // Constant-power style balance control, while retaining stereo width.
                                val pan = t.pan.coerceIn(-1f, 1f)
                                val leftGain = if (pan > 0f) 1f - pan else 1f
                                val rightGain = if (pan < 0f) 1f + pan else 1f
                                dryL += l * leftGain
                                dryR += r * rightGain

                                positions[idx] += 1f
                                if (positions[idx] >= grainLength) {
                                    positions[idx] = 0f
                                    phases[idx] = (phases[idx] + hop).mod(frames.toFloat())
                                }
                            }
                        }
                    }

                    val delayFrames = (sr * (0.04f + echoTime.coerceIn(0f, 1f) * 0.7f)).toInt()
                    val di = (delayIndex - delayFrames + delayL.size) % delayL.size
                    val dl = delayL[di]
                    val dr = delayR[di]
                    val fb = echoFeedback.coerceIn(0f, 0.85f)
                    delayL[delayIndex] = (dryL + dl * fb).coerceIn(-2f, 2f)
                    delayR[delayIndex] = (dryR + dr * fb).coerceIn(-2f, 2f)

                    // Four short cross-coupled delay states create a lightweight stereo ambience.
                    val size = reverbSize.coerceIn(0f, 1f)
                    reverbAL = dryL * 0.32f + reverbBR * (0.48f + size * 0.43f)
                    reverbAR = dryR * 0.32f + reverbBL * (0.47f + size * 0.43f)
                    reverbBL = dryL * 0.27f + reverbAR * (0.45f + size * 0.42f)
                    reverbBR = dryR * 0.27f + reverbAL * (0.46f + size * 0.42f)

                    val sat = 1f + saturation.coerceIn(0f, 1f) * 12f
                    val wetL = dryL + dl * echoMix.coerceIn(0f, 1f) + reverbAL * reverbMix.coerceIn(0f, 1f)
                    val wetR = dryR + dr * echoMix.coerceIn(0f, 1f) + reverbAR * reverbMix.coerceIn(0f, 1f)
                    val l = tanh(wetL * sat) * masterVolume.coerceIn(0f, 1f)
                    val r = tanh(wetR * sat) * masterVolume.coerceIn(0f, 1f)
                    out[i * 2] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    out[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    delayIndex = (delayIndex + 1) % delayL.size
                }
                audioTrack?.write(out, 0, out.size)
            }
        }.apply { name = "GranularStereoAudio"; priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
        audioTrack?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }
        audioTrack = null
    }
}
