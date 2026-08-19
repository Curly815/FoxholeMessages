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

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Wraps a SurfaceTexture that receives the video decoder's output frames and draws each one as
 * a full-screen textured quad into whatever GL context/surface is current when [drawImage] is
 * called - the [InputSurface] in this file's usage. This draw call is the actual resize step:
 * the encoder's input surface can be a different size than the decoder's source frames, and GL
 * handles that scaling as an ordinary part of rendering a texture onto a differently-sized quad.
 * A prior implementation of this file skipped this rendering step entirely and wired the
 * decoder's output surface directly into the encoder's input surface, which only produced a
 * valid frame when no resize was needed - never true in practice for this app's use case.
 * Ported from Android's own MediaCodec reference sample (OutputSurface.java) rather than written
 * from scratch, for the same reason as [InputSurface].
 *
 * Must be constructed (and [drawImage] called) while an EGL context is already current on this
 * thread - it creates its texture and compiles its shader against whatever context that is,
 * rather than setting up a context of its own, since this file always shares [InputSurface]'s.
 */
internal class OutputSurface : SurfaceTexture.OnFrameAvailableListener {

    private val textureId: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val frameLock = Object()
    private var frameAvailable = false

    private val program: Int
    private val positionHandle: Int
    private val texCoordHandle: Int
    private val mvpMatrixHandle: Int
    private val texMatrixHandle: Int
    private val textureMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    init {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)

        program = createProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteProgram(program)
    }

    /** Blocks until the decoder has produced (and this has consumed) a new frame. */
    fun awaitNewImage() {
        synchronized(frameLock) {
            while (!frameAvailable) {
                frameLock.wait(FRAME_WAIT_TIMEOUT_MS)
                if (!frameAvailable) throw RuntimeException("timed out waiting for a decoded frame")
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
    }

    fun drawImage() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameLock) {
            frameAvailable = true
            frameLock.notifyAll()
        }
    }

    private fun createProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("could not link GL program: $log")
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("could not compile GL shader (type $type): $log")
        }
        return shader
    }

    companion object {
        private const val FRAME_WAIT_TIMEOUT_MS = 10_000L

        private val VERTEX_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private val vertexBuffer: FloatBuffer = directFloatBuffer(VERTEX_COORDS)
        private val texCoordBuffer: FloatBuffer = directFloatBuffer(TEX_COORDS)

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                        .apply { put(values); position(0) }

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
