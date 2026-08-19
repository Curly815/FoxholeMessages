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
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unlike images (see [ImageUtils]), MMS video attachments were previously always sent at their
 * original size/quality. If a video is larger than what the carrier will accept, the carrier's
 * own MMSC re-transcodes it to fit - usually with much worse quality than we could achieve
 * ourselves, which is the "pixelated video" complaint this exists to fix.
 *
 * A hand-rolled MediaCodec surface-to-surface transcode was tried twice here and failed
 * real-device testing both times: the decoder's output surface was wired directly into the
 * encoder's input surface with no rendering step in between, so it only produced a valid frame
 * when the encoder's target resolution happened to match the source's - which never held in
 * practice, since fitting a modern phone video into an MMS byte budget always requires a real
 * resolution cut. This re-implementation uses com.otaliastudios:transcoder instead, which does
 * real GL-based scaling between decode and encode (the same approach Google's own reference
 * MediaCodec transcode sample uses), rather than a third attempt at hand-rolling that ourselves.
 *
 * Every call site MUST treat a thrown exception as "give up and send the original bytes
 * unmodified" - this is real device-only multimedia code that can't be exercised in CI, so it's
 * written to fail loudly rather than risk producing a corrupt attachment.
 */
object VideoUtils {

    private const val TRANSCODE_TIMEOUT_MINUTES = 5L

    // Caps the longer/shorter side so a video that's already small enough isn't needlessly
    // re-encoded at full resolution just to hit a bitrate target - matches roughly what most
    // carriers consider a reasonable MMS video resolution.
    private const val MAX_MINOR_DIMENSION = 720
    private const val MAX_MAJOR_DIMENSION = 1280

    private const val MIN_VIDEO_BITRATE = 250_000 // below this, encoder output gets unreliable

    /**
     * Re-encodes the video at [uri] to fit within [maxBytes], returning the new file's bytes.
     * Only worth calling when the source is already known to exceed [maxBytes].
     */
    fun getScaledVideo(context: Context, uri: Uri, maxBytes: Long): ByteArray {
        // MediaMetadataRetriever.close() (Closeable) only exists from API 29 - this app's minSdk
        // is 23, and calling a framework method the OS doesn't have throws NoSuchMethodError at
        // runtime rather than failing to compile, so release() is used explicitly instead.
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        } finally {
            retriever.release()
        }?.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalStateException("could not read video duration")

        // Reserve a rough share of the budget for audio and container overhead, same "leave
        // some wiggle room" idea the image compressor uses.
        val videoBudgetBytes = (maxBytes * 0.75).toLong().coerceAtLeast(1)
        val durationSec = durationMs / 1000.0
        val targetBitrate = ((videoBudgetBytes * 8) / durationSec).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)

        Timber.d("Transcoding video to ${targetBitrate / 1000}kbps " +
                "(budget ${maxBytes / 1024}Kb, duration ${durationSec}s)")

        val outputFile = File.createTempFile("video_compress", ".mp4", context.cacheDir)
        try {
            val latch = CountDownLatch(1)
            var failure: Throwable? = null

            val future = Transcoder.into(outputFile.absolutePath)
                    .addDataSource(context, uri)
                    .setVideoTrackStrategy(
                            DefaultVideoStrategy.atMost(MAX_MINOR_DIMENSION, MAX_MAJOR_DIMENSION)
                                    .bitRate(targetBitrate.toLong())
                                    .build()
                    )
                    .setListener(object : TranscoderListener {
                        override fun onTranscodeProgress(progress: Double) = Unit
                        override fun onTranscodeCompleted(successCode: Int) = latch.countDown()
                        override fun onTranscodeCanceled() {
                            failure = IllegalStateException("transcode cancelled")
                            latch.countDown()
                        }
                        override fun onTranscodeFailed(exception: Throwable) {
                            failure = exception
                            latch.countDown()
                        }
                    })
                    .transcode()

            if (!latch.await(TRANSCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                future.cancel(true)
                throw IllegalStateException("video transcode timed out")
            }
            failure?.let { throw it }

            val bytes = outputFile.readBytes()
            if (bytes.isEmpty()) throw IllegalStateException("transcoded output was empty")
            return bytes
        } finally {
            outputFile.delete()
        }
    }
}
