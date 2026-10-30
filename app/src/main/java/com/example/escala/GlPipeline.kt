package com.example.escala

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thread própria com EGL/GLES 3.0. Fluxo:
 *   VirtualDisplay -> [captureSurface = SurfaceTexture OES] -> shader de upscaling -> [outSurface = overlay]
 *
 * Aqui entram depois: passes extras (interpolação de frames), HUD, etc.
 */
class GlPipeline(
    private val outSurface: Surface,
    initW: Int,
    initH: Int,
    private val mode: Int,
    private val sharp: Float,
    private val onFps: (Int) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    private companion object {
        const val TAG = "EscalaGL"

        const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        // Catmull-Rom bicúbico em 9 amostras bilineares (técnica de M. Pettineo) + nitidez limitada
        // pela vizinhança (sem halos). uMode: 0 bilinear | 1 bicúbico | 2 bicúbico + nitidez.
        const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform vec2 uSrcSize;
uniform int uMode;
uniform float uSharp;
in vec2 vUv;
out vec4 outColor;

vec3 catmullRom(vec2 uv) {
    vec2 pos = uv * uSrcSize;
    vec2 center = floor(pos - 0.5) + 0.5;
    vec2 f = pos - center;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;
    vec2 w0 = -0.5 * f3 + f2 - 0.5 * f;
    vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
    vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
    vec2 w3 = 0.5 * f3 - 0.5 * f2;
    vec2 w12 = w1 + w2;
    vec2 off12 = w2 / w12;
    vec2 t0 = (center - 1.0) / uSrcSize;
    vec2 t3 = (center + 2.0) / uSrcSize;
    vec2 t12 = (center + off12) / uSrcSize;
    vec3 r = vec3(0.0);
    r += texture(uTex, vec2(t0.x,  t0.y )).rgb * (w0.x  * w0.y );
    r += texture(uTex, vec2(t12.x, t0.y )).rgb * (w12.x * w0.y );
    r += texture(uTex, vec2(t3.x,  t0.y )).rgb * (w3.x  * w0.y );
    r += texture(uTex, vec2(t0.x,  t12.y)).rgb * (w0.x  * w12.y);
    r += texture(uTex, vec2(t12.x, t12.y)).rgb * (w12.x * w12.y);
    r += texture(uTex, vec2(t3.x,  t12.y)).rgb * (w3.x  * w12.y);
    r += texture(uTex, vec2(t0.x,  t3.y )).rgb * (w0.x  * w3.y );
    r += texture(uTex, vec2(t12.x, t3.y )).rgb * (w12.x * w3.y );
    r += texture(uTex, vec2(t3.x,  t3.y )).rgb * (w3.x  * w3.y );
    return clamp(r, 0.0, 1.0);
}

void main() {
    if (uMode == 0) {
        outColor = vec4(texture(uTex, vUv).rgb, 1.0);
        return;
    }
    vec3 c = catmullRom(vUv);
    if (uMode == 2) {
        vec2 d = 1.0 / uSrcSize;
        vec3 n = texture(uTex, vUv + vec2(0.0, -d.y)).rgb;
        vec3 s = texture(uTex, vUv + vec2(0.0,  d.y)).rgb;
        vec3 e = texture(uTex, vUv + vec2( d.x, 0.0)).rgb;
        vec3 w = texture(uTex, vUv + vec2(-d.x, 0.0)).rgb;
        vec3 blur = (n + s + e + w) * 0.25;
        vec3 mn = min(min(min(n, s), min(e, w)), c);
        vec3 mx = max(max(max(n, s), max(e, w)), c);
        c = clamp(c + uSharp * (c - blur), mn, mx);
    }
    outColor = vec4(c, 1.0);
}
"""
    }

    @Volatile private var srcW = initW
    @Volatile private var srcH = initH
    @Volatile private var released = false

    private val thread = HandlerThread("escala-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var vbo = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    private var uTexMatrix = 0
    private var uTex = 0
    private var uSrcSize = 0
    private var uMode = 0
    private var uSharp = 0

    private val texMatrix = FloatArray(16)
    private val wh = IntArray(1)
    private var frames = 0
    private var lastStatMs = SystemClock.elapsedRealtime()

    /** Cria o contexto GL e devolve (na thread GL) a Surface que deve receber a captura. */
    fun start(onReady: (Surface) -> Unit) {
        handler.post {
            try {
                initEgl()
                initGl()
                onReady(captureSurface!!)
            } catch (t: Throwable) {
                Log.e(TAG, "falha ao iniciar GL", t)
            }
        }
    }

    fun setSourceSize(w: Int, h: Int) {
        srcW = w
        srcH = h
        handler.post { surfaceTexture?.setDefaultBufferSize(w, h) }
    }

    fun stop() {
        released = true
        handler.post {
            surfaceTexture?.setOnFrameAvailableListener(null)
            releaseAll()
            thread.quitSafely()
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (!released) draw()
    }

    // ---- EGL / GL ----------------------------------------------------------------------------

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }
    }

    private fun initGl() {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(program) }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uSrcSize = GLES30.glGetUniformLocation(program, "uSrcSize")
        uMode = GLES30.glGetUniformLocation(program, "uMode")
        uSharp = GLES30.glGetUniformLocation(program, "uSharp")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val fb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(quad).position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        vbo = b[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, fb, GLES30.GL_STATIC_DRAW)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        texId = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(srcW, srcH)
        st.setOnFrameAvailableListener(this, handler)
        surfaceTexture = st
        captureSurface = Surface(st)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("shader: $log")
        }
        return s
    }

    private fun draw() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, e)
            return
        }
        st.getTransformMatrix(texMatrix)

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, wh, 0)
        val w = wh[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, wh, 0)
        val h = wh[0]
        GLES30.glViewport(0, 0, w, h)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform2f(uSrcSize, srcW.toFloat(), srcH.toFloat())
        GLES30.glUniform1i(uMode, mode)
        GLES30.glUniform1f(uSharp, sharp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        frames++
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatMs >= 1000) {
            onFps((frames * 1000L / (now - lastStatMs)).toInt())
            frames = 0
            lastStatMs = now
        }
    }

    private fun releaseAll() {
        try {
            if (program != 0) GLES30.glDeleteProgram(program)
            if (texId != 0) GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            captureSurface?.release()
            surfaceTexture?.release()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
