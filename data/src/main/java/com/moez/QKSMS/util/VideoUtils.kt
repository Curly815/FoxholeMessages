/*
 * Copyright (C) 2026 Foxhole Messages contributors
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Unlike images (see [ImageUtils]), MMS video attachments were previously always sent at their
 * original size/quality. If a video is larger than what the carrier will accept, the carrier's
 * own MMSC re-transcodes it to fit - usually with much worse quality than we could achieve
 * ourselves, which is the "pixelated video" complaint this exists to fix. This re-encodes the
 * video to fit a target byte budget using the standard MediaCodec surface-to-surface transcode
 * pattern (decoder output surface == encoder input surface, so no raw pixel format handling is
 * needed), keeping the audio track as a straight copy since it's rarely the size problem.
 *
 * Every call site MUST treat a thrown exception as "give up and send the original bytes
 * unmodified" - this is real device-only multimedia code that can't be exercised in CI, so it's
 * written to fail loudly rather than risk producing a corrupt attachment.
 */
object VideoUtils {

    private const val TIMEOUT_US = 10_000L
    private const val MIN_VIDEO_BITRATE = 250_000 // below this, MediaCodec output gets unreliable
    private const val MIN_BITS_PER_PIXEL = 0.6 // floor before we scale resolution down instead

    /**
     * Re-encodes the video at [uri] to fit within [maxBytes], returning the new file's bytes.
     * Only worth calling when the source is already known to exceed [maxBytes].
     */
    fun getScaledVideo(context: Context, uri: Uri, maxBytes: Long): ByteArray {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException("could not read video duration")
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        retriever.release()

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val videoTrack = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).mimeStart("video/") }
                ?: throw IllegalStateException("no video track")
        val audioTrack = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).mimeStart("audio/") }

        val sourceFormat = extractor.getTrackFormat(videoTrack)
        val sourceWidth = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
        val sourceHeight = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = tryOrNull(false) { sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE) } ?: 30

        // Reserve a rough share of the budget for the (untouched) audio track and container
        // overhead, same "leave some wiggle room" idea the image compressor uses.
        val audioBytesEstimate = if (audioTrack != null) (maxBytes * 0.15).toLong() else 0L
        val videoBudgetBytes = ((maxBytes - audioBytesEstimate) * 0.9).toLong().coerceAtLeast(1)
        val durationSec = durationMs / 1000.0
        var targetBitrate = ((videoBudgetBytes * 8) / durationSec).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)

        var targetWidth = sourceWidth
        var targetHeight = sourceHeight

        // If that bitrate is too low for the source resolution to look decent, scale the
        // resolution down instead of just starving the bitrate - mirrors why the image
        // compressor shrinks dimensions rather than only turning down JPEG quality.
        val bitsPerPixel = targetBitrate.toDouble() / (sourceWidth * sourceHeight * frameRate)
        if (bitsPerPixel < MIN_BITS_PER_PIXEL) {
            val scale = sqrt(bitsPerPixel / MIN_BITS_PER_PIXEL)
            // Round to a multiple of 16 - some hardware encoders reject arbitrary dimensions
            targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(16).let { it - it % 16 }.coerceAtLeast(16)
            targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(16).let { it - it % 16 }.coerceAtLeast(16)
            targetBitrate = ((videoBudgetBytes * 8) / durationSec).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)
        }

        Timber.d("Transcoding video ${sourceWidth}x$sourceHeight -> " +
                "${targetWidth}x$targetHeight @ ${targetBitrate / 1000}kbps " +
                "(budget ${maxBytes / 1024}Kb, duration ${durationSec}s)")

        val outputFile = File.createTempFile("video_compress", ".mp4", context.cacheDir)
        try {
            transcode(
                    extractor, videoTrack, audioTrack, sourceFormat,
                    targetWidth, targetHeight, targetBitrate, frameRate, rotation,
                    outputFile.absolutePath
            )
            val bytes = outputFile.readBytes()
            if (bytes.isEmpty()) throw IllegalStateException("transcoded output was empty")
            return bytes
        } finally {
            extractor.release()
            outputFile.delete()
        }
    }

    private fun MediaFormat.mimeStart(prefix: String) =
            getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true

    private fun transcode(
        extractor: MediaExtractor,
        videoTrack: Int,
        audioTrack: Int?,
        sourceVideoFormat: MediaFormat,
        targetWidth: Int,
        targetHeight: Int,
        targetBitrate: Int,
        frameRate: Int,
        rotationDegrees: Int,
        outputPath: String
    ) {
        val encoderFormat = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(
                sourceVideoFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(sourceVideoFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        if (audioTrack != null) {
            muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
        }

        extractor.selectTrack(videoTrack)

        val bufferInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false

        try {
            while (!encoderDone) {
                // Feed the decoder from the extractor
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain the decoder, rendering frames straight to the encoder's input surface
                if (!decoderDone) {
                    when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                            if (eos) {
                                encoder.signalEndOfInputStream()
                                decoderDone = true
                            }
                        }
                    }
                }

                // Drain the encoder into the muxer
                when (val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Guaranteed to fire at most once before real output arrives, but
                        // guard anyway rather than risk adding a duplicate muxer track
                        if (muxerVideoTrack == -1) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (!muxerStarted && (audioTrack == null || muxerAudioTrack >= 0)) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                    }
                    else -> if (outIndex >= 0) {
                        if (bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(muxerVideoTrack, encoder.getOutputBuffer(outIndex)!!, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            // Audio passthrough - copy samples straight from source to muxer, no re-encoding
            if (audioTrack != null && muxerStarted) {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioBufferInfo = MediaCodec.BufferInfo()
                val audioBuffer = java.nio.ByteBuffer.allocate(1 shl 20)
                while (true) {
                    val sampleSize = extractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) break
                    audioBufferInfo.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                    extractor.advance()
                }
            }
        } finally {
            tryOrNull(false) { decoder.stop() }
            tryOrNull(false) { decoder.release() }
            tryOrNull(false) { encoder.stop() }
            tryOrNull(false) { encoder.release() }
            tryOrNull(false) { inputSurface.release() }
            if (muxerStarted) tryOrNull(false) { muxer.stop() }
            tryOrNull(false) { muxer.release() }
        }
    }
}
