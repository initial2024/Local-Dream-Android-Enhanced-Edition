package io.github.xororz.localdream.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Locally turns 2..30 user-selected pictures into an H.264 MP4.
 *
 * Does not load or modify diffusion models or send photos to any server.
 * Memory stays bounded: at most two decoded image textures at a time.
 */
internal object PhotoVideoEncoder {
    private const val FPS = 24
    private const val MAX_DURATION_SECONDS = 90
    private const val BITRATE = 3_000_000

    suspend fun export(
        context: Context,
        photos: List<Uri>,
        destination: Uri,
        portrait: Boolean,
        secondsPerPhoto: Int,
        onProgress: suspend (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val selected = photos.toList()
        require(selected.size in 2..30) { "Choose between 2 and 30 photos" }
        require(secondsPerPhoto in 1..5) { "Photo length must be 1–5 seconds" }
        require(selected.size * secondsPerPhoto <= MAX_DURATION_SECONDS) {
            "Maximum video duration is 90 seconds"
        }
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 1280 else 720
        val total = PhotoVideoTimeline.totalFrames(selected.size, FPS, secondsPerPhoto)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var input: Surface? = null
        var egl: PhotoEglRenderer? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            egl = PhotoEglRenderer(input, width, height)
            codec.start()
            context.contentResolver.openFileDescriptor(destination, "w")?.use { file ->
                muxer = MediaMuxer(file.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val state = TrackState()
                var displayedPhoto = -1
                var currentTexture = 0
                var followingTexture = 0
                try {
                    for (frame in 0 until total) {
                        coroutineContext.ensureActive()
                        val position = PhotoVideoTimeline.frameAt(
                            frame, selected.size, FPS, secondsPerPhoto,
                        )
                        if (position.index != displayedPhoto) {
                            egl.deleteTexture(currentTexture)
                            egl.deleteTexture(followingTexture)
                            displayedPhoto = position.index
                            currentTexture = egl.loadTexture(decode(context, selected[displayedPhoto]))
                            followingTexture = if (displayedPhoto + 1 < selected.size) {
                                egl.loadTexture(decode(context, selected[displayedPhoto + 1]))
                            } else {
                                0
                            }
                        }
                        egl.render(currentTexture, followingTexture, position.opacity)
                        egl.present(frame * 1_000_000_000L / FPS)
                        drain(codec, muxer!!, state, finish = false)
                        if (frame % FPS == 0) {
                            onProgress(frame * 100 / total)
                        }
                    }
                    egl.deleteTexture(currentTexture)
                    egl.deleteTexture(followingTexture)
                    codec.signalEndOfInputStream()
                    drain(codec, muxer!!, state, finish = true)
                    started = state.started
                    onProgress(100)
                } finally {
                    if (state.started) {
                        // A failed/cancelled stream can have an incomplete last GOP.
                        // The exporter reports errors; do not claim valid video then.
                        runCatching { muxer?.stop() }
                    }
                }
            } ?: throw IOException("Cannot open the selected output destination")
        } finally {
            egl?.close()
            input?.release()
            runCatching { codec.stop() }
            codec.release()
            muxer?.release()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Decode at most 1280x1280; never load the full original 50 MP image.
            val factor = minOf(
                1f,
                1280f / info.size.width,
                1280f / info.size.height,
            )
            if (factor < 1f) {
                decoder.setTargetSize(
                    (info.size.width * factor).roundToInt().coerceAtLeast(1),
                    (info.size.height * factor).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    private class TrackState(var track: Int = -1, var started: Boolean = false)

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, state: TrackState, finish: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (finish) 10_000L else 0L)
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!finish) return
                    if (++idleTries >= 500) throw IOException("Encoder timed out at end of stream")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!state.started) { "Video format changed twice" }
                    state.track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.started = true
                }
                else -> if (index >= 0) {
                    idleTries = 0
                    val bytes = codec.getOutputBuffer(index)
                        ?: throw IOException("Encoder did not return an output buffer")
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        check(state.started) { "Received video bytes before MP4 track" }
                        bytes.position(info.offset)
                        bytes.limit(info.offset + info.size)
                        muxer.writeSampleData(state.track, bytes, info)
                    }
                    val ended = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return
                }
            }
        }
    }
}

/** EGL window bound to a MediaCodec input surface, OpenGL ES 2 textured quad. */
private class PhotoEglRenderer(surface: Surface, private val width: Int, private val height: Int) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val window: EGLSurface
    private val program: Int
    private val vertex: Int
    private val texture: Int
    private val opacity: Int
    private val coordinates = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(
            display, IntArray(2), 0, IntArray(2), 0,
        )) { "EGL display unavailable" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) &&
            found[0] > 0) { "No recordable EGL config" }
        val selected = configs[0] ?: error("EGL config missing")
        context = EGL14.eglCreateContext(
            display, selected, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Cannot create GLES2 context" }
        window = EGL14.eglCreateWindowSurface(
            display, selected, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(window != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, window, window, context)) {
            "Cannot bind video recording surface"
        }
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPos; attribute vec2 aUv; varying vec2 uv;" +
                "void main(){ gl_Position=aPos; uv=aUv; }",
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; varying vec2 uv; uniform sampler2D image;" +
                "uniform float alpha; void main(){ vec4 c=texture2D(image,uv);" +
                " gl_FragColor=vec4(c.rgb,alpha); }",
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        check(link[0] == GLES20.GL_TRUE) { "Cannot link slideshow GPU shader" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        GLES20.glUseProgram(program)
        vertex = GLES20.glGetAttribLocation(program, "aPos")
        texture = GLES20.glGetAttribLocation(program, "aUv")
        opacity = GLES20.glGetUniformLocation(program, "alpha")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "Video GPU shader failed to compile" }
        return shader
    }

    fun loadTexture(bitmap: Bitmap): Int {
        try {
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            val id = names[0]
            check(id != 0) { "Cannot allocate image texture" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return id
        } finally {
            bitmap.recycle()
        }
    }

    fun render(current: Int, following: Int, fade: Float) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        draw(current, 1f)
        if (following != 0 && fade > 0f) draw(following, fade)
    }

    private fun draw(id: Int, alpha: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        // For slide exports images are already bounded to 1280x1280. The texture
        // is stretched in this first implementation; exact aspect-preserving
        // cropping will be addressed in the next verification pass.
        coordinates.clear()
        coordinates.put(floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        ))
        coordinates.position(0)
        GLES20.glEnableVertexAttribArray(vertex)
        GLES20.glVertexAttribPointer(vertex, 2, GLES20.GL_FLOAT, false, 16, coordinates)
        coordinates.position(2)
        GLES20.glEnableVertexAttribArray(texture)
        GLES20.glVertexAttribPointer(texture, 2, GLES20.GL_FLOAT, false, 16, coordinates)
        GLES20.glUniform1f(opacity, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun present(timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, window, timestampNs)
        check(EGL14.eglSwapBuffers(display, window)) { "Video encoder surface rejected frame" }
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun close() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        GLES20.glDeleteProgram(program)
        EGL14.eglDestroySurface(display, window)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
