package com.zedokmusic.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/** Encodes a 16-bit PCM WAV file to AAC-LC in an .m4a container with the platform codec (no extra libraries). */
object AacEncoder {
    /** Returns null on success or an error message. */
    fun encode(wav: File, out: File, bitrate: Int = 256_000): String? {
        RandomAccessFile(wav, "r").use { f ->
            // locate the fmt and data chunks
            val head = ByteArray(12); f.readFully(head)
            var channels = 2; var rate = 48000; var bits = 16; var dataPos = -1L; var dataLen = 0L
            while (f.filePointer + 8 <= f.length()) {
                val id = ByteArray(4); f.readFully(id); val sz = java.lang.Integer.reverseBytes(f.readInt()).toLong() and 0xFFFFFFFFL
                val tag = String(id)
                if (tag == "fmt ") { val b = ByteArray(sz.toInt()); f.readFully(b); val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.short; channels = bb.short.toInt(); rate = bb.int; bb.int; bb.short; bits = bb.short.toInt() }
                else if (tag == "data") { dataPos = f.filePointer; dataLen = minOf(sz, f.length() - dataPos); break }
                else f.seek(f.filePointer + sz + (sz and 1))
            }
            if (dataPos < 0 || bits != 16) return "Unsupported WAV for AAC encoding."
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            var muxer: MediaMuxer? = null
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1; var inDone = false; var outDone = false
                val info = MediaCodec.BufferInfo(); val buf = ByteArray(16384); val bytesPerFrame = channels * 2
                var consumed = 0L
                f.seek(dataPos)
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val ib = codec.getInputBuffer(i)!!; ib.clear()
                            val want = minOf(buf.size.toLong(), dataLen - consumed, ib.capacity().toLong()).toInt() / bytesPerFrame * bytesPerFrame
                            if (want <= 0) { codec.queueInputBuffer(i, 0, 0, consumed / bytesPerFrame * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { f.readFully(buf, 0, want); ib.put(buf, 0, want); codec.queueInputBuffer(i, 0, want, consumed / bytesPerFrame * 1_000_000L / rate, 0); consumed += want }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                        o >= 0 -> {
                            val ob = codec.getOutputBuffer(o)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(track, ob, info) }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
                muxer.stop()
                return null
            } catch (e: Exception) {
                return "AAC encoding failed: ${e.message}"
            } finally {
                runCatching { codec.stop() }; codec.release(); runCatching { muxer?.release() }
            }
        }
    }
}
