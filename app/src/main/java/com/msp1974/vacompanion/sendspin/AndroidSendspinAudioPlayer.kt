package com.msp1974.vacompanion.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import timber.log.Timber

internal class AndroidSendspinAudioPlayer(
    private val audioBuffer: AudioBuffer,
    private val clockSync: ClockSync,
) : AudioPlayer {

    companion object {
        private const val NORMAL_GAIN = 1f

        @Volatile
        private var duckingEnabled: Boolean = false

        @Volatile
        private var duckingGain: Float = 0.25f

        private val activePlayers = Collections.synchronizedSet(mutableSetOf<AndroidSendspinAudioPlayer>())

        fun setDuckingEnabled(enabled: Boolean, gain: Float = duckingGain) {
            duckingEnabled = enabled
            duckingGain = gain.coerceIn(0f, 1f)
            synchronized(activePlayers) {
                activePlayers.forEach { player ->
                    player.applyEffectiveVolume()
                }
            }
        }
    }

    @Volatile
    private var playbackGain: Float = 1f

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var currentFormat: StreamFormat? = null

    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var droppedDecodeFrames: Long = 0L
        private set

    private val stopRequested = AtomicBoolean(false)

    override fun configure(format: StreamFormat) {
        currentFormat = format
        flush()

        val sampleRate = format.sampleRate
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = when (format.bitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            24 -> AudioFormat.ENCODING_PCM_FLOAT
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val trackBufferSize = (minBuffer * 2).coerceAtLeast(minBuffer)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.release()
        audioTrack = newTrack
        applyEffectiveVolume()
    }

    override fun start() {
        val track = audioTrack ?: return
        if (isPlaying) return

        stopRequested.set(false)
        track.play()
        isPlaying = true
        activePlayers.add(this)
        worker = thread(start = true, name = "sendspin-audio-worker") {
            runPlaybackLoop(track)
        }
    }

    override fun flush() {
        audioBuffer.flush()
        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                if (isPlaying) {
                    track.play()
                }
            }
        }
    }

    override fun stop() {
        stopRequested.set(true)
        worker?.join(300)
        worker = null

        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
            }
            track.release()
        }
        audioTrack = null
        isPlaying = false
        activePlayers.remove(this)
    }

    override fun transition(format: StreamFormat) {
        configure(format)
        start()
    }

    override fun setVolume(gain: Float) {
        playbackGain = gain.coerceIn(0f, 1f)
        applyEffectiveVolume()
    }

    private fun applyEffectiveVolume() {
        val targetGain = if (duckingEnabled) duckingGain else NORMAL_GAIN
        audioTrack?.setVolume((playbackGain * targetGain).coerceIn(0f, 1f))
    }

    private fun runPlaybackLoop(track: AudioTrack) {
        while (!stopRequested.get()) {
            val now = ClockSync.localMicros()
            val chunk = audioBuffer.poll(now)
            if (chunk == null) {
                audioBuffer.signalUnderrun()
                val delayMicros = audioBuffer.nextChunkDelayMicros(now) ?: 10_000L
                val sleepMs = (delayMicros / 1000L).coerceIn(2L, 50L)
                runCatching { Thread.sleep(sleepMs) }
                continue
            }

            val format = currentFormat
            if (format == null) {
                droppedDecodeFrames++
                continue
            }

            val pcm = decodeAsPcm(chunk.data, format)
            if (pcm == null) {
                droppedDecodeFrames++
                continue
            }

            val writeResult = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (writeResult < 0) {
                droppedDecodeFrames++
            }
        }
    }

    // MVP: handle PCM directly. Compressed codecs can be added in the next phase.
    private fun decodeAsPcm(data: ByteArray, format: StreamFormat): ByteArray? {
        return when (format.codec.lowercase()) {
            "pcm" -> normalizePcm(data, format.bitDepth)
            else -> {
                Timber.w("Sendspin codec ${format.codec} not supported in MVP decoder")
                null
            }
        }
    }

    private fun normalizePcm(data: ByteArray, bitDepth: Int): ByteArray {
        return when (bitDepth) {
            16, 8 -> data
            24 -> convert24BitTo16Bit(data)
            32 -> convertFloat32To16Bit(data)
            else -> data
        }
    }

    private fun convert24BitTo16Bit(input: ByteArray): ByteArray {
        if (input.size < 3) return ByteArray(0)
        val output = ByteArray((input.size / 3) * 2)
        var inIndex = 0
        var outIndex = 0
        while (inIndex + 2 < input.size) {
            val sample = ((input[inIndex + 2].toInt() shl 24) or
                ((input[inIndex + 1].toInt() and 0xFF) shl 16) or
                ((input[inIndex].toInt() and 0xFF) shl 8)) shr 16
            output[outIndex] = (sample and 0xFF).toByte()
            output[outIndex + 1] = ((sample shr 8) and 0xFF).toByte()
            inIndex += 3
            outIndex += 2
        }
        return output
    }

    private fun convertFloat32To16Bit(input: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArray((input.size / 4) * 2)
        var outIndex = 0
        while (buffer.remaining() >= 4) {
            val sample = (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            output[outIndex] = (sample and 0xFF).toByte()
            output[outIndex + 1] = ((sample shr 8) and 0xFF).toByte()
            outIndex += 2
        }
        return output
    }
}
