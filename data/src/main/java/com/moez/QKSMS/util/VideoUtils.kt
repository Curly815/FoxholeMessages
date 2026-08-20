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
import android.opengl.GLES20
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Unlike images (see [ImageUtils]), MMS video attachments were previously always sent at their
 * original size/quality. If a video is larger than what the carrier will accept, the carrier's
 * own MMSC re-transcodes it to fit - usually with much worse quality than we could achieve
 * ourselves, which is the "pixelated video" complaint this exists to fix.
 *
 * Third attempt at this file. The first two hand-rolled MediaCodec transcodes both failed
 * real-device testing: they wired the decoder's output surface directly into the encoder's
 * input surface with no rendering step in between, so a valid frame only came out when the
 * encoder's target resolution happened to match the source's exactly - never true in practice,
 * since fitting a modern phone video into an MMS byte budget always requires a real resolution
 * cut. A third attempt at using a well-tested library (com.otaliastudios:transcoder) hit a hard
 * Kotlin-compiler-metadata incompatibility with this project's pinned Kotlin 1.7.21, and its
 * only compiler-compatible older releases predate the library's own fixes for the exact class of
 * video-corruption bug this file is trying to avoid - so back to a hand-rolled implementation,
 * this time with the actual missing piece: [InputSurface]/[OutputSurface] render each decoded
 * frame through GL as a textured quad before handing it to the encoder, which is what actually
 * performs the resize (and is the same approach Google's own reference MediaCodec transcode
 * sample uses) rather than skipping it.
 *
 * Every call site MUST treat a thrown exception as "give up and send the original bytes
 * unmodified" - this is real device-only multimedia code that can't be exercised in CI, so it's
 * written to fail loudly rather than risk producing a corrupt attachment.
 *
 * [getScaledVideo] verifies the actual output size and retries at a lower bitrate/resolution if
 * it's still over [maxBytes], instead of trusting the initial bitrate formula's estimate - mirrors
 * the shrink-and-retry loop [ImageUtils] already uses for images, since real encoders rarely hit
 * their target bitrate exactly.
 *
 * Audio is decoded and re-encoded to AAC at [TARGET_AUDIO_BITRATE] rather than passed through at
 * the source's original bitrate (see [encodeAudioTrack]) - phone-recorded audio is often 200+kbps,
 * which on an MMS-sized budget for a few seconds of video is real space that's better spent on
 * video quality.
 */
object VideoUtils {

    private const val TIMEOUT_US = 10_000L
    private const val MIN_VIDEO_BITRATE = 250_000 // below this, MediaCodec output gets unreliable
    private const val MIN_BITS_PER_PIXEL = 0.6 // floor before we scale resolution down instead
    private const val MIN_VIDEO_DIMENSION = 96 // floor before we give up shrinking further
    private const val MAX_ATTEMPTS = 3 // initial pass + up to 2 shrink-and-retry passes
    private const val TARGET_AUDIO_BITRATE = 96_000 // AAC - plenty for typical talking/ambient audio

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
                    ?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalStateException("could not read video duration")
        } finally {
            retriever.release()
        }

        // Only used to read track info/format up front - each transcode attempt below opens its
        // own fresh MediaExtractor rather than reusing this one, since a retry needs to re-read
        // the source from the beginning and resetting an already-consumed extractor's read
        // position/track selection correctly is more fragile than just reopening it.
        //
        // Deliberately does NOT read/apply the source's rotation metadata at all (earlier
        // versions of this method did, first via MediaMetadataRetriever, then via this same
        // extractor's "rotation-degrees" format key - both agreed, so this was never actually an
        // API-disagreement bug as first suspected). Confirmed via two separate real device-shot
        // videos: each declared a 90 degree rotation that directly contradicted its own pixel
        // content - decoding the frames both ways showed the UN-rotated buffer was the correct,
        // normally-proportioned picture, while applying the declared rotation squished it into an
        // impossible portrait strip. Since this reproduced identically on two different clips
        // from the same device, the rotation tag itself is untrustworthy here, not just one bad
        // file - so this method now always passes the raw decoded orientation through unchanged.
        val (videoTrack, audioTrack, sourceFormat, sourceWidth, sourceHeight, frameRate) =
                MediaExtractor().let { extractor ->
                    try {
                        extractor.setDataSource(context, uri, null)
                        val videoTrack = (0 until extractor.trackCount)
                                .firstOrNull { extractor.getTrackFormat(it).mimeStart("video/") }
                                ?: throw IllegalStateException("no video track")
                        val audioTrack = (0 until extractor.trackCount)
                                .firstOrNull { extractor.getTrackFormat(it).mimeStart("audio/") }

                        val sourceFormat = extractor.getTrackFormat(videoTrack)
                        val sourceWidth = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val sourceHeight = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        val frameRate =
                                tryOrNull(false) { sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE) } ?: 30

                        VideoSourceInfo(
                                videoTrack, audioTrack, sourceFormat,
                                sourceWidth, sourceHeight, frameRate
                        )
                    } finally {
                        extractor.release()
                    }
                }

        val durationSec = durationMs / 1000.0

        // Audio is now re-encoded down to TARGET_AUDIO_BITRATE (see transcode()/encodeAudioTrack())
        // rather than passed through at the source's original bitrate, so the budget reserved for
        // it can be a real, tight estimate instead of a rough guess - a small margin still covers
        // container/CSD overhead.
        val audioBytesEstimate = if (audioTrack != null) {
            ((TARGET_AUDIO_BITRATE / 8.0) * durationSec * 1.05).toLong()
        } else 0L
        val videoBudgetBytes = ((maxBytes - audioBytesEstimate) * 0.9).toLong().coerceAtLeast(1)

        var targetWidth = sourceWidth
        var targetHeight = sourceHeight
        var targetBitrate = ((videoBudgetBytes * 8) / durationSec).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)

        // If that bitrate is too low for the source resolution to look decent, scale the
        // resolution down instead of just starving the bitrate - mirrors why the image
        // compressor shrinks dimensions rather than only turning down JPEG quality.
        val bitsPerPixel = targetBitrate.toDouble() / (sourceWidth * sourceHeight * frameRate)
        if (bitsPerPixel < MIN_BITS_PER_PIXEL) {
            val scale = sqrt(bitsPerPixel / MIN_BITS_PER_PIXEL)
            targetWidth = roundToNearest((sourceWidth * scale).roundToInt(), 16)
            targetHeight = roundToNearest((targetWidth.toDouble() * sourceHeight / sourceWidth).roundToInt(), 2)
        }

        var bytes = ByteArray(0)
        for (attempt in 1..MAX_ATTEMPTS) {
            Timber.d("Transcoding video (attempt $attempt/$MAX_ATTEMPTS) " +
                    "${sourceWidth}x$sourceHeight -> ${targetWidth}x$targetHeight @ " +
                    "${targetBitrate / 1000}kbps (budget ${maxBytes / 1024}Kb, duration ${durationSec}s)")

            val outputFile = File.createTempFile("video_compress", ".mp4", context.cacheDir)
            val attemptExtractor = MediaExtractor()
            try {
                attemptExtractor.setDataSource(context, uri, null)
                transcode(
                        attemptExtractor, videoTrack, audioTrack, sourceFormat,
                        targetWidth, targetHeight, targetBitrate, frameRate,
                        outputFile.absolutePath
                )
                bytes = outputFile.readBytes()
                if (bytes.isEmpty()) throw IllegalStateException("transcoded output was empty")
            } finally {
                attemptExtractor.release()
                outputFile.delete()
            }

            val atFloor = targetBitrate <= MIN_VIDEO_BITRATE &&
                    (targetWidth <= MIN_VIDEO_DIMENSION || targetHeight <= MIN_VIDEO_DIMENSION)
            if (bytes.size <= maxBytes || attempt == MAX_ATTEMPTS || atFloor) {
                Timber.d("Transcoded video: ${bytes.size / 1024}Kb " +
                        "(target was ${maxBytes / 1024}Kb) after $attempt attempt(s)")
                break
            }

            // Still too big - the bitrate formula above is only an estimate (real encoders
            // rarely hit their target bitrate exactly, and short clips especially are dominated
            // by container/keyframe overhead the formula doesn't account for), so verify and
            // shrink further instead of trusting a single guess. Prefer cutting bitrate first;
            // once that's floored, cut resolution instead - endlessly starving bitrate at a
            // fixed resolution is what MIN_BITS_PER_PIXEL/MIN_VIDEO_BITRATE already guard against
            // above, so the same guard applies here.
            if (targetBitrate > MIN_VIDEO_BITRATE) {
                targetBitrate = (targetBitrate * 0.75).toInt().coerceAtLeast(MIN_VIDEO_BITRATE)
            } else {
                val scale = sqrt(0.75)
                targetWidth = roundToNearest((targetWidth * scale).roundToInt(), 16)
                        .coerceAtLeast(MIN_VIDEO_DIMENSION)
                targetHeight = roundToNearest((targetWidth.toDouble() * sourceHeight / sourceWidth).roundToInt(), 2)
                        .coerceAtLeast(MIN_VIDEO_DIMENSION)
            }
        }
        return bytes
    }

    // Round width to the nearest multiple of 16 - hardware encoders commonly require that
    // alignment on the row stride - then derive height from the ROUNDED width via the source's
    // exact aspect ratio, only rounding it to the nearest EVEN number (not 16). Two device tests
    // confirmed 16-aligning height too still left a real, visible stretch (a 3840x2160 source
    // measured 320x176, ~2.3% off true 16:9) simply because 16 is a coarse grid at this kind of
    // extreme downscale (4K source down to a ~160-350px target) - the nearest valid 16-multiple
    // can be several percent off even with correct nearest-rounding. Encoders need width-stride
    // alignment far more consistently than height alignment, so relaxing height to just "even"
    // removes most of the remaining error (in that same case, 320x180 is an exact 16:9 match)
    // while still avoiding an odd height, which some encoders do reject.
    private fun roundToNearest(px: Int, multiple: Int) =
            (((px + multiple / 2) / multiple) * multiple).coerceAtLeast(multiple)

    private data class VideoSourceInfo(
        val videoTrack: Int,
        val audioTrack: Int?,
        val sourceFormat: MediaFormat,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val frameRate: Int
    )

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
        outputPath: String
    ) {
        val encoderFormat = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // Keyframes are far larger than regular frames, and these clips are watched
            // start-to-finish rather than seeked/scrubbed, so there's no need for more than the
            // one keyframe every encoder already emits at the start. A 1-second interval (the
            // previous value) meant 6-7 redundant full-frame refreshes on a 7-second clip, all
            // eating into an already tiny byte budget instead of going toward picture detail.
            // 3600s is just "effectively never" without risking overflow in the encoder's
            // internal frame-count math - MMS clips are always far shorter than that.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3600)
            // Main profile rather than Baseline (this method's previous choice): Baseline was a
            // defensive move against an in-app crash on transcoded video that was, at the time,
            // unconfirmed to actually be caused by the profile choice - it's since been root-caused
            // to an unrelated RecyclerView/Realm bug (see git history), so that restriction was
            // costing real quality for nothing. Main is still comfortably supported by any device
            // from roughly the last decade+ and typically encodes 10-20% more efficiently than
            // Baseline at the same bitrate. If a specific device's encoder can't configure with
            // this profile/level, configure() throws, which the caller already treats as "give up
            // and send the original bytes unmodified" - not a crash risk.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // makeCurrent() before OutputSurface is constructed - it creates its GL texture and
        // compiles its shader against whatever EGL context is current, and reuses this one
        // rather than setting up a context of its own, since everything here runs on one thread.
        val inputSurface = InputSurface(encoder.createInputSurface())
        inputSurface.makeCurrent()
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        encoder.start()

        val outputSurface = OutputSurface()

        val decoder = MediaCodec.createDecoderByType(
                sourceVideoFormat.getString(MediaFormat.KEY_MIME)!!)
        // The decoder now writes into OutputSurface's SurfaceTexture, not directly into the
        // encoder's input surface - OutputSurface.drawImage() is the actual resize step,
        // rendering each decoded frame as a GL quad sized to the encoder's target dimensions.
        decoder.configure(sourceVideoFormat, outputSurface.surface, null, 0)
        decoder.start()

        // No rotation hint is written - see the comment on getScaledVideo()'s source-info read
        // for why this method deliberately never applies the source's declared rotation.
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false

        try {
            // Audio is fully decoded and re-encoded (down to TARGET_AUDIO_BITRATE - see
            // encodeAudioTrack()) to completion here, before any video work starts, with its
            // samples buffered in memory rather than written to the muxer yet. MediaMuxer requires
            // every track to be added via addTrack() before start(), and the audio encoder's real
            // output format (which carries the AAC codec-init data players need) isn't known until
            // it's actually been run - so this has to finish first to get that format for
            // addTrack(), while actually muxing the samples waits until the video loop below
            // starts the muxer, same as it always has. A few seconds of AAC at this bitrate is a
            // trivial amount of memory to hold onto in the meantime.
            val audioSamples = if (audioTrack != null) {
                val (audioFormat, samples) = encodeAudioTrack(extractor, audioTrack)
                muxerAudioTrack = muxer.addTrack(audioFormat)
                samples
            } else {
                emptyList()
            }

            extractor.selectTrack(videoTrack)

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

                // Drain the decoder: render each frame through GL onto the encoder's input
                // surface (at the encoder's target size), instead of passing it through raw.
                if (!decoderDone) {
                    when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val hasFrame = bufferInfo.size > 0
                            decoder.releaseOutputBuffer(outIndex, hasFrame)
                            if (hasFrame) {
                                outputSurface.awaitNewImage()
                                outputSurface.drawImage()
                                inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                                inputSurface.swapBuffers()
                            }
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

            // Flush the audio samples encoded up front, now that the video loop above has
            // started the muxer (or confirmed there's nothing to flush, if muxerStarted never
            // became true - eg. the source had no video frames at all).
            if (muxerStarted) {
                for (sample in audioSamples) {
                    muxer.writeSampleData(
                            muxerAudioTrack,
                            ByteBuffer.wrap(sample.data),
                            MediaCodec.BufferInfo().apply {
                                set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                            }
                    )
                }
            }
        } finally {
            tryOrNull(false) { decoder.stop() }
            tryOrNull(false) { decoder.release() }
            tryOrNull(false) { encoder.stop() }
            tryOrNull(false) { encoder.release() }
            tryOrNull(false) { outputSurface.release() }
            tryOrNull(false) { inputSurface.release() }
            if (muxerStarted) tryOrNull(false) { muxer.stop() }
            tryOrNull(false) { muxer.release() }
        }
    }

    // A MediaCodec.BufferInfo's fields get overwritten in place on every dequeueOutputBuffer
    // call, so encodeAudioTrack() below snapshots the fields it actually needs (plus a real copy
    // of the sample data, since the source ByteBuffer is also reused/invalidated once its buffer
    // index is released) rather than holding onto anything MediaCodec-owned.
    private data class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    // Fully decodes then re-encodes (to AAC at TARGET_AUDIO_BITRATE) the audio track at
    // [audioTrack], returning the encoder's real output format (needed by transcode() for
    // muxer.addTrack() - it carries the AAC codec-init data players need, which isn't available
    // until the encoder has actually run) alongside every encoded sample, buffered in memory.
    // Selects/seeks/unselects [audioTrack] on [extractor] internally; the caller is expected to
    // select whatever track it needs next (video) itself afterward.
    private fun encodeAudioTrack(
        extractor: MediaExtractor,
        audioTrack: Int
    ): Pair<MediaFormat, List<EncodedSample>> {
        val sourceAudioFormat = extractor.getTrackFormat(audioTrack)
        val sampleRate = sourceAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = sourceAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val audioDecoder = MediaCodec.createDecoderByType(sourceAudioFormat.getString(MediaFormat.KEY_MIME)!!)
        audioDecoder.configure(sourceAudioFormat, null, null, 0)

        val audioEncoderFormat =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(audioEncoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null

        val bufferInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false

        try {
            audioDecoder.start()
            audioEncoder.start()

            extractor.selectTrack(audioTrack)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            while (!encoderDone) {
                // Feed the decoder from the extractor
                if (!extractorDone) {
                    val inIndex = audioDecoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = audioDecoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            audioDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            audioDecoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain the decoder straight into the encoder's input - unlike video, there's no
                // resize step needed, so the decoded PCM just gets copied across rather than
                // rendered through anything.
                if (!decoderDone) {
                    when (val outIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (bufferInfo.size > 0) {
                                val decoded = audioDecoder.getOutputBuffer(outIndex)!!
                                val encInIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encInIndex >= 0) {
                                    val encIn = audioEncoder.getInputBuffer(encInIndex)!!
                                    encIn.clear()
                                    encIn.put(decoded)
                                    audioEncoder.queueInputBuffer(
                                            encInIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, 0)
                                }
                            }
                            audioDecoder.releaseOutputBuffer(outIndex, false)
                            if (eos) {
                                val encInIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encInIndex >= 0) {
                                    audioEncoder.queueInputBuffer(
                                            encInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                decoderDone = true
                            }
                        }
                    }
                }

                // Drain the encoder into the buffered sample list
                when (val outIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = audioEncoder.outputFormat
                    else -> if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val out = audioEncoder.getOutputBuffer(outIndex)!!
                            val copy = ByteArray(bufferInfo.size)
                            out.get(copy)
                            samples += EncodedSample(copy, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        audioEncoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                        }
                    }
                }
            }
        } finally {
            tryOrNull(false) { audioDecoder.stop() }
            tryOrNull(false) { audioDecoder.release() }
            tryOrNull(false) { audioEncoder.stop() }
            tryOrNull(false) { audioEncoder.release() }
            tryOrNull(false) { extractor.unselectTrack(audioTrack) }
        }

        val format = outputFormat
                ?: throw IllegalStateException("audio encoder never produced an output format")
        return format to samples
    }
}
