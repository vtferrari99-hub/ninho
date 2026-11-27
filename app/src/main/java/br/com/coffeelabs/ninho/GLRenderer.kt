package br.com.coffeelabs.ninho

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** Câmera fixa compartilhada entre o desenho 3D e a lógica (toque, partículas). */
object Cam {
    val EYE = V3(0f, 0.35f, 7f)
    const val FOV = 38f

    fun viewProj(aspect: Float, out: FloatArray) {
        val p = FloatArray(16); val v = FloatArray(16)
        Matrix.perspectiveM(p, 0, FOV, aspect, 0.5f, 40f)
        Matrix.setLookAtM(v, 0, EYE.x, EYE.y, EYE.z, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(out, 0, p, 0, v, 0)
    }
}

/** Estado da cena publicado pela lógica (thread de UI) e lido pelo renderizador (thread GL). */
class Scene {
    val lock = Any()
    val spine = FloatArray(N * 3)
    var dragon = false
    var shape: RyuShape = RyuShape.of(Stage.BABY)
    val pose = RyuPose()
    var egg = false
    var eggWobble = 0f
    var eggCrack = 0f
    var eggGlow = 0f
    var pearl = false
    var pearlPos = V3.ZERO
    var night = 0f
    var time = 0f
    var lookX = 0f
    var lookY = 0f
    /** deslocamento de paralaxe, em fração da largura da tela */
    var parallax = 0f

    companion object { const val N = 72 }
}

class MsaaChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(samples: Int): EGLConfig? {
            val base = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 0x40
            )
            val attrs = if (samples > 0) base + intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
            else base + intArrayOf(EGL10.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!egl.eglChooseConfig(display, attrs, cfgs, 1, n) || n[0] < 1) return null
            return cfgs[0]
        }
        return pick(4) ?: pick(2) ?: pick(0) ?: throw IllegalStateException("Sem configuração EGL compatível")
    }
}

class GLRenderer(private val scene: Scene, private val assets: android.content.res.AssetManager) : GLSurfaceView.Renderer {
    private val mesh = Mesh()
    private val builder = RyuBuilder()
    private var prog = 0
    private var depthProg = 0
    private var bgProg = 0
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    private val waterVbo = IntArray(1)
    private val quadVbo = IntArray(1)
    private val shadowFbo = IntArray(1)
    private val shadowTex = IntArray(1)
    private val bgTex = IntArray(4)
    private val lr = FloatArray(4)
    private var vBuf: FloatBuffer = newFloats(Mesh.STRIDE * 30000)
    private var iBuf: IntBuffer = newInts(180000)
    private val pondVbo = IntArray(1)
    private val pondIbo = IntArray(1)
    private var pondCount = 0
    private val vp = FloatArray(16)
    private val lightVP = FloatArray(16)
    private var w = 1
    private var h = 1
    private val uni = HashMap<String, Int>()

    // cópia local do estado
    private val spine = FloatArray(Scene.N * 3)
    private val pose = RyuPose()

    private fun newFloats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun newInts(n: Int): IntBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Ninho", "shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Ninho", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun u(name: String): Int = uni.getOrPut(name) { GLES30.glGetUniformLocation(prog, name) }
    private fun set1(name: String, v: Float) = GLES30.glUniform1f(u(name), v)
    private fun set3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(u(name), x, y, z)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uni.clear()
        prog = link(Shaders.VS, Shaders.FS)
        depthProg = link(Shaders.DEPTH_VS, Shaders.DEPTH_FS)
        bgProg = link(Shaders.BG_VS, Shaders.BG_FS)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenBuffers(1, ibo, 0)
        GLES30.glGenBuffers(1, waterVbo, 0)
        GLES30.glGenBuffers(1, quadVbo, 0)
        // plantas do lago: malha estática, montada uma única vez
        GLES30.glGenBuffers(1, pondVbo, 0)
        GLES30.glGenBuffers(1, pondIbo, 0)
        val pm = Mesh()
        builder.pond(pm)
        val pv = newFloats(pm.vn); pv.put(pm.v, 0, pm.vn); pv.position(0)
        val pi = newInts(pm.inN); pi.put(pm.idx, 0, pm.inN); pi.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pondVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pm.vn * 4, pv, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, pondIbo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, pm.inN * 4, pi, GLES30.GL_STATIC_DRAW)
        pondCount = pm.inN
        // plano da água (2 triângulos, mesmo formato de vértice)
        val wm = Mesh()
        val y = RyuBuilder.WATER_Y
        val pts = floatArrayOf(-5f, -9f, 5f, -9f, 5f, 4f, -5f, -9f, 5f, 4f, -5f, 4f)
        for (i in 0 until 6) wm.vert(pts[i * 2], y, pts[i * 2 + 1], 0f, 1f, 0f, V3.ZERO, 0f, 0f, 0f, 0f, 0f)
        val wb = newFloats(wm.vn); wb.put(wm.v, 0, wm.vn); wb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, waterVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, wm.vn * 4, wb, GLES30.GL_STATIC_DRAW)
        val qb = newFloats(12); qb.put(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 1f)); qb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 48, qb, GLES30.GL_STATIC_DRAW)
        // mapa de sombras
        GLES30.glGenTextures(1, shadowTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, SHADOW, SHADOW, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, shadowFbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex[0], 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // camadas do cenário (recortes da sprite sheet)
        GLES30.glGenTextures(4, bgTex, 0)
        val bmps = Backdrop.load(assets)
        for (i in 0 until 4) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex[i])
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmps[i], 0)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, if (i == Backdrop.WATER) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            bmps[i].recycle()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = maxOf(1, width); h = maxOf(1, height)
        Cam.viewProj(w.toFloat() / h, vp)
    }

    private fun bindMeshAttribs(buffer: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        val st = Mesh.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, st, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, st, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, st, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, st, 36)
    }

    override fun onDrawFrame(gl: GL10?) {
        var dragon: Boolean; var egg: Boolean; var pearl: Boolean
        var shape: RyuShape; var eggWobble: Float; var eggCrack: Float; var eggGlow: Float
        var pearlPos: V3; var night: Float; var time: Float; var lookX: Float; var lookY: Float
        var par: Float
        synchronized(scene.lock) {
            System.arraycopy(scene.spine, 0, spine, 0, spine.size)
            dragon = scene.dragon; egg = scene.egg; pearl = scene.pearl; shape = scene.shape
            pose.t = scene.pose.t; pose.open = scene.pose.open; pose.eyeMode = scene.pose.eyeMode; pose.jaw = scene.pose.jaw
            pose.headF = scene.pose.headF; pose.headU = scene.pose.headU; pose.paddle = scene.pose.paddle; pose.nod = scene.pose.nod
            eggWobble = scene.eggWobble; eggCrack = scene.eggCrack; eggGlow = scene.eggGlow
            pearlPos = scene.pearlPos; night = scene.night; time = scene.time; lookX = scene.lookX; lookY = scene.lookY
            par = scene.parallax
        }
        // malha
        mesh.reset()
        if (egg) builder.egg(mesh, RyuBuilder.NEST, 0.6f, eggWobble, eggCrack, eggGlow)
        if (dragon) builder.build(mesh, spine, Scene.N, shape, pose)
        if (pearl) builder.pearl(mesh, pearlPos, 0.13f)
        if (vBuf.capacity() < mesh.vn) vBuf = newFloats(mesh.v.size)
        if (iBuf.capacity() < mesh.inN) iBuf = newInts(mesh.idx.size)
        vBuf.clear(); vBuf.put(mesh.v, 0, mesh.vn); vBuf.position(0)
        iBuf.clear(); iBuf.put(mesh.idx, 0, mesh.inN); iBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vn * 4, vBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.inN * 4, iBuf, GLES30.GL_DYNAMIC_DRAW)

        // luz (dia → noite)
        val lx = lerp(-0.5f, -0.45f, night); val ly = lerp(0.85f, 0.8f, night); val lz = lerp(0.45f, 0.3f, night)
        val L = V3(lx, ly, lz).norm()
        val cx = 0f; val cy = -0.3f; val cz = 0.2f
        val lv = FloatArray(16); val lp = FloatArray(16)
        Matrix.setLookAtM(lv, 0, cx + L.x * 8f, cy + L.y * 8f, cz + L.z * 8f, cx, cy, cz, 0f, 1f, 0f)
        Matrix.orthoM(lp, 0, -3.4f, 3.4f, -3.4f, 3.4f, 0.5f, 16f)
        Matrix.multiplyMM(lightVP, 0, lp, 0, lv, 0)

        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        // 1) sombras
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo[0])
        GLES30.glViewport(0, 0, SHADOW, SHADOW)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(depthProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(depthProg, "uLightVP"), 1, false, lightVP, 0)
        drawGeometry()

        // 2) tela
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0.74f, 0.82f, 0.92f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val tr = lerp(1f, 0.40f, night); val tg = lerp(1f, 0.45f, night); val tb = lerp(1f, 0.70f, night)
        val wf = w.toFloat(); val hf = h.toFloat()
        val pp = par * wf
        val shore = hf * Backdrop.SHORE
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(bgProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(bgProg, "uTex"), 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(bgProg, "uTint"), tr, tg, tb)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2); GLES30.glDisableVertexAttribArray(3)
        // céu + Fuji (o céu acima da faixa é a 1ª linha esticada)
        Backdrop.rect(Backdrop.FAR, wf, hf, pp, lr)
        val fx = lr[0]; val fy = lr[1]; val fw = lr[2]; val fh = lr[3]
        layer(Backdrop.FAR, fx, 0f, fx + fw, fy + 1f, 0f, 0.004f, 1f, 0.004f, 1f)
        layer(Backdrop.FAR, fx, fy, fx + fw, fy + fh, 0f, 0f, 1f, 1f, 1f)
        // reflexos espelhados na linha da margem
        val vs = ((shore - fy) / fh).coerceIn(0f, 1f)
        layer(Backdrop.FAR, fx, shore, fx + fw, shore + (shore - fy) * 0.9f, 0f, vs, 1f, 0f, 1f)
        Backdrop.rect(Backdrop.MID, wf, hf, pp, lr)
        val mx = lr[0]; val my = lr[1]; val mw = lr[2]; val mh = lr[3]
        layer(Backdrop.MID, mx, shore, mx + mw, shore + mh * 0.9f, 0f, 1f, 1f, 0f, 1f)
        // água por cima dos reflexos (deixa-os azulados e com brilhos), deslizando devagar
        val u0 = -pp * Backdrop.PAR_WATER / wf + time * 0.006f
        layer(Backdrop.WATER, 0f, shore, wf, hf, u0, 0f, u0 + 1.25f, 1f, 0.58f)
        layer(Backdrop.MID, mx, my, mx + mw, my + mh, 0f, 0f, 1f, 1f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u("uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(u("uLightVP"), 1, false, lightVP, 0)
        set3("uLight", L.x, L.y, L.z)
        set3("uLightCol", lerp(0.74f, 0.40f, night), lerp(0.69f, 0.47f, night), lerp(0.62f, 0.70f, night))
        set3("uSkyCol", lerp(0.40f, 0.15f, night), lerp(0.45f, 0.19f, night), lerp(0.56f, 0.34f, night))
        set3("uGroundCol", lerp(0.30f, 0.09f, night), lerp(0.28f, 0.11f, night), lerp(0.30f, 0.20f, night))
        set3("uEye", Cam.EYE.x, Cam.EYE.y, Cam.EYE.z)
        GLES30.glUniform2f(u("uLook"), lookX, lookY)
        val pale = shape.pale
        val bel = RyuBuilder.BELLY.lerp(V3(1f, 1f, 1f), pale * 0.8f)
        val lid = RyuBuilder.BODY.lerp(RyuBuilder.SILVER, pale)
        set3("uBelly", bel.x, bel.y, bel.z)
        set3("uLid", lid.x, lid.y, lid.z)
        set1("uScaleK", 54f * shape.L / 3.3f)
        set1("uBankPx", h * 0.1f)
        set1("uTime", time)
        set1("uWaterY", RyuBuilder.WATER_Y)
        set1("uMirror", 0f)
        set1("uShadowOnly", 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex[0])
        GLES30.glUniform1i(u("uShadow"), 0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // reflexo na água
        set1("uMirror", 1f)
        drawGeometry()
        set1("uMirror", 0f)
        // sombra projetada na água
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        bindMeshAttribs(waterVbo[0])
        set1("uShadowOnly", 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        set1("uShadowOnly", 0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        // cena
        drawGeometry()

        // margem em primeiro plano (a camada que mais se move na paralaxe)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(bgProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2); GLES30.glDisableVertexAttribArray(3)
        Backdrop.rect(Backdrop.FG, wf, hf, pp, lr)
        layer(Backdrop.FG, lr[0], lr[1], lr[0] + lr[2], lr[1] + lr[3], 0f, 0f, 1f, 1f, 1f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Desenha uma camada 2D: retângulo em pixels (origem no topo) e recorte de textura. */
    private fun layer(tex: Int, x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex[tex])
        GLES30.glUniform4f(GLES30.glGetUniformLocation(bgProg, "uRect"), x0 / w * 2f - 1f, 1f - y0 / h * 2f, x1 / w * 2f - 1f, 1f - y1 / h * 2f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(bgProg, "uUv"), u0, v0, u1, v1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(bgProg, "uAlpha"), alpha)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    /** Desenha o lago (estático) e a malha dinâmica com o programa atual. */
    private fun drawGeometry() {
        bindMeshAttribs(pondVbo[0])
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, pondIbo[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, pondCount, GLES30.GL_UNSIGNED_INT, 0)
        if (mesh.inN > 0) {
            bindMeshAttribs(vbo[0])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.inN, GLES30.GL_UNSIGNED_INT, 0)
        }
    }

    companion object { const val SHADOW = 1024 }
}
