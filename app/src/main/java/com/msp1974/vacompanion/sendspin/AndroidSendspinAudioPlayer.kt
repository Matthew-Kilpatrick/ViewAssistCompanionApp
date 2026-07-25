package com.msp1974.vacompanion.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import java.io.ByteArrayOutputStream
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

        private const val PCM_CODEC = "pcm"
        private const val OPUS_CODEC = "opus"
        
        // Opus always operates internally at 48kHz regardless of the stream's declared
        // sample rate (RFC 7845 section 2) - decoded PCM must be treated as 48kHz.
        private const val OPUS_SAMPLE_RATE = 48_000
        // Fallback pre-skip used only when the stream omits/has invalid OpusHead.
        // 312 samples at 48kHz (~6.5 ms) is the common Opus pre-skip produced by
        // libopus-based encoders and avoids audible leading artifacts.
        private const val OPUS_DEFAULT_PRE_SKIP_SAMPLES = 312
        // Standard Opus seek pre-roll from RFC 7845: 80 ms at 48kHz = 3840 samples.
        // MediaCodec expects this as csd-2 in nanoseconds.
        private const val OPUS_SEEK_PREROLL_SAMPLES = 3_840L
        // Short dequeue timeout keeps audio loop responsive while still allowing decoder work.
        private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L

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

    private var opusDecoder: MediaCodec? = null
    private var lastQueuedOpusPtsUs: Long = 0L
    private var loggedUnsupportedCodec: String? = null

    override fun configure(format: StreamFormat) {
        currentFormat = format
        flush()
        releaseOpusDecoder()

        val isOpus = format.codec.equals(OPUS_CODEC, ignoreCase = true)
        val outputChannels = if (isOpus) configureOpusDecoder(format) else format.channels

        val sampleRate = if (isOpus) OPUS_SAMPLE_RATE else format.sampleRate
        val channelMask = if (outputChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (isOpus) {
            // MediaCodec's Opus decoder always outputs 16-bit PCM.
            AudioFormat.ENCODING_PCM_16BIT
        } else {
            // normalizePcm() always converts 24/32-bit input samples down to 16-bit integer PCM,
            // so the track must be configured for 16-bit output in every case except 8-bit.
            when (format.bitDepth) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
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
        opusDecoder?.let { codec -> runCatching { codec.flush() } }
        lastQueuedOpusPtsUs = 0L
        flushRequested.set(true)
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
        releaseOpusDecoder()
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

    // Only codecs advertised in SendspinControllerImpl's ClientPreferences.supportedFormats
    // (currently "opus" and "pcm") should ever be sent to us by a spec-compliant server; the
    // "else" branch below only guards against a misbehaving/out-of-spec server.
    private fun decodeAsPcm(data: ByteArray, format: StreamFormat): ByteArray? {
        return when (format.codec.lowercase()) {
            PCM_CODEC -> normalizePcm(data, format.bitDepth)
            OPUS_CODEC -> decodeOpusFrame(data)
            else -> {
                if (loggedUnsupportedCodec != format.codec) {
                    loggedUnsupportedCodec = format.codec
                    Timber.w("Sendspin codec ${format.codec} is not supported by this client and was not advertised as a supported format")
                }
                null
            }
        }
    }

    private fun configureOpusDecoder(format: StreamFormat): Int {
        val decodedHeader = format.codecHeader?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }

        // RFC 7845 Opus identification header ('OpusHead') layout: channel count is byte 9,
        // and pre-skip is bytes 10-11 (little endian). We therefore require at least 12 bytes
        // before trusting a received header; shorter headers fall back to a synthesized OpusHead.
        val headerBytes = if (decodedHeader != null && decodedHeader.size >= 12) {
            decodedHeader
        } else {
            // Some servers omit codec headers for Opus. Synthesize a minimal OpusHead
            // so MediaCodec can still initialize and decode.
            val fallbackChannels = format.channels.coerceIn(1, 2)
            Timber.w("Sendspin Opus stream missing/invalid codec header; using synthesized OpusHead (channels=$fallbackChannels)")
            buildOpusIdentificationHeader(fallbackChannels, OPUS_DEFAULT_PRE_SKIP_SAMPLES)
        }

        val preSkipSamples = ((headerBytes[11].toInt() and 0xFF) shl 8) or (headerBytes[10].toInt() and 0xFF)
        val outputChannels = (headerBytes[9].toInt() and 0xFF).coerceIn(1, 2)
        val codecDelayNs = preSkipSamples * 1_000_000_000L / OPUS_SAMPLE_RATE
        val seekPreRollNs = OPUS_SEEK_PREROLL_SAMPLES * 1_000_000_000L / OPUS_SAMPLE_RATE
        lastQueuedOpusPtsUs = 0L

        runCatching {
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_SAMPLE_RATE, outputChannels)
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(headerBytes))
            mediaFormat.setByteBuffer("csd-1", nanosToLittleEndianBuffer(codecDelayNs))
            mediaFormat.setByteBuffer("csd-2", nanosToLittleEndianBuffer(seekPreRollNs))

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
            opusDecoder = codec
        }.onFailure { error ->
            Timber.e(error, "Failed to initialise Sendspin Opus decoder")
            opusDecoder = null
        }

        return outputChannels
    }

    private fun nanosToLittleEndianBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { rewind() }

    private fun buildOpusIdentificationHeader(channels: Int, preSkipSamples: Int): ByteArray {
        val safeChannels = channels.coerceIn(1, 2)
        val safePreSkip = preSkipSamples.coerceIn(0, 0xFFFF)
        // RFC 7845 OpusHead (19 bytes) with mapping family 0.
        return byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, // version
            safeChannels.toByte(),
            (safePreSkip and 0xFF).toByte(),
            ((safePreSkip shr 8) and 0xFF).toByte(),
            (OPUS_SAMPLE_RATE and 0xFF).toByte(),
            ((OPUS_SAMPLE_RATE shr 8) and 0xFF).toByte(),
            ((OPUS_SAMPLE_RATE shr 16) and 0xFF).toByte(),
            ((OPUS_SAMPLE_RATE shr 24) and 0xFF).toByte(),
            0, 0, // output gain
            0, // channel mapping family
        )
    }

    private fun nextOpusPtsUs(): Long {
        val candidate = ClockSync.localMicros()
        val next = if (candidate > lastQueuedOpusPtsUs) candidate else lastQueuedOpusPtsUs + 1L
        lastQueuedOpusPtsUs = next
        return next
    }

    private fun decodeOpusFrame(data: ByteArray): ByteArray? {
        val codec = opusDecoder ?: return null
        return runCatching {
            val inputIndex = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                if (inputBuffer == null || inputBuffer.remaining() < data.size) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    return@runCatching null
                }
                codec.queueInputBuffer(inputIndex, 0, data.size, nextOpusPtsUs(), 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val output = ByteArrayOutputStream()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_DEQUEUE_TIMEOUT_US)
            while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    val outRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Timber.d("Sendspin Opus output format changed: rate=$outRate channels=$outChannels")
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    continue
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    continue
                }

                if (outputIndex < 0) {
                    break
                }

                if (bufferInfo.size > 0) {
                    codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        output.write(chunk)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
            if (output.size() == 0) null else output.toByteArray()
        }.getOrElse { error ->
            Timber.w(error, "Sendspin Opus decode error")
            null
        }
    }

    private fun releaseOpusDecoder() {
        opusDecoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        opusDecoder = null
        lastQueuedOpusPtsUs = 0L
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
