package com.zeekrmate.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoPartSplitter {

    fun split(source: File, outDir: File, maxBytes: Long = MAX_PART_BYTES): List<File> {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        val parts = mutableListOf<File>()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndexes = selectableTracks(extractor)
            if (trackIndexes.none { mimeOf(extractor, it).startsWith("video/") }) {
                error("Нет видеодорожки")
            }
            trackIndexes.forEach { extractor.selectTrack(it) }
            val videoTrack = trackIndexes.first { mimeOf(extractor, it).startsWith("video/") }
            var buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            var partIndex = 0
            var bytesInPart = 0L
            var ptsOffset = 0L
            var started = false
            var muxTracks = intArrayOf()

            fun startPart(offsetUs: Long) {
                muxer?.let { finishMuxer(it) }
                partIndex += 1
                val part = File(outDir, partFileName(source, partIndex))
                if (part.exists()) {
                    part.delete()
                }
                val next = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxTracks = IntArray(extractor.trackCount) { -1 }
                trackIndexes.forEach { index ->
                    muxTracks[index] = next.addTrack(extractor.getTrackFormat(index))
                }
                rotationOf(extractor, videoTrack)?.let { next.setOrientationHint(it) }
                next.start()
                muxer = next
                parts += part
                bytesInPart = 0
                ptsOffset = offsetUs
                started = true
            }

            while (true) {
                buffer.clear()
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (_: IllegalArgumentException) {
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    extractor.readSampleData(buffer, 0)
                }
                if (size < 0) {
                    break
                }
                val track = extractor.sampleTrackIndex
                val isVideoKey = track == videoTrack &&
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (started && bytesInPart >= maxBytes && isVideoKey) {
                    startPart(extractor.sampleTime)
                }
                if (!started) {
                    startPart(extractor.sampleTime.coerceAtLeast(0L))
                }
                val muxTrack = muxTracks.getOrNull(track) ?: -1
                if (muxTrack < 0) {
                    extractor.advance()
                    continue
                }
                val info = MediaCodec.BufferInfo()
                info.offset = buffer.position()
                info.size = size
                info.presentationTimeUs = (extractor.sampleTime - ptsOffset).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer?.writeSampleData(muxTrack, buffer, info)
                bytesInPart += size
                extractor.advance()
            }
            muxer?.let { finishMuxer(it) }
            muxer = null
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
        return parts.filter { it.exists() && it.length() > 0L }
    }

    private fun finishMuxer(muxer: MediaMuxer) {
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun selectableTracks(extractor: MediaExtractor): List<Int> {
        return (0 until extractor.trackCount).filter { index ->
            val mime = mimeOf(extractor, index)
            mime.startsWith("video/") || mime.startsWith("audio/")
        }
    }

    private fun mimeOf(extractor: MediaExtractor, track: Int): String {
        return extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
    }

    private fun rotationOf(extractor: MediaExtractor, videoTrack: Int): Int? {
        val format = extractor.getTrackFormat(videoTrack)
        return when {
            format.containsKey(MediaFormat.KEY_ROTATION) -> format.getInteger(MediaFormat.KEY_ROTATION)
            format.containsKey("rotation-degrees") -> format.getInteger("rotation-degrees")
            else -> null
        }
    }

    private fun partFileName(source: File, index: Int): String {
        return "${source.nameWithoutExtension}_part$index.mp4"
    }

    private const val BUFFER_BYTES = 2 * 1024 * 1024
    const val MAX_PART_BYTES = 42L * 1024L * 1024L
}
