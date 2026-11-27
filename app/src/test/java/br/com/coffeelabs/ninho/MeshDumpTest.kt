package br.com.coffeelabs.ninho

import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sin

/** Gera a malha do Ryu fora do aparelho, para conferir o porte visualmente antes de liberar o APK. */
class MeshDumpTest {
    private fun dump(name: String, m: Mesh) {
        val f = File("build/dump/$name.bin"); f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(m.vn); o.writeInt(m.inN)
            for (i in 0 until m.vn) o.writeFloat(m.v[i])
            for (i in 0 until m.inN) o.writeInt(m.idx[i])
        }
    }

    @Test
    fun dumpMeshes() {
        val b = RyuBuilder()
        for (stage in listOf(Stage.BABY, Stage.ADULT, Stage.ELDER)) {
            val shape = RyuShape.of(stage)
            val sw = Swimmer(V3(0f, 0.3f, 0.3f))
            val home = V3(0f, 0.3f, 0.15f)
            for (k in 0 until 420) {
                val tt = k / 30f
                sw.step(1f / 30f, home + V3(0.55f * sin(tt * 0.45f), 0.4f * sin(tt * 0.33f + 1f), 0.16f * sin(tt * 0.27f + 0.5f)), 0.75f, shape.L, 0.35f)
            }
            val n = Scene.N
            val spine = FloatArray(n * 3)
            sw.spine(spine, n, shape.L)
            for (k in 0 until n) spine[k * 3 + 2] -= 0.85f * (shape.L / 3.3f) * k / (n - 1f)
            val pose = RyuPose()
            pose.t = 1f
            val v = sw.vel.norm()
            pose.headF = V3(v.x, v.y * 0.35f, maxOf(v.z, 0.2f)).norm().lerp(V3(0f, 0.05f, 1f), 0.25f).norm()
            val m = Mesh()
            b.pond(m)
            b.build(m, spine, n, shape, pose)
            if (stage == Stage.BABY) { b.egg(m, V3(0.9f, RyuBuilder.WATER_Y + 0.02f, 1.2f), 0.6f, 0.1f, 0.5f, 0.3f); b.pearl(m, V3(-0.8f, 0.2f, 0.6f), 0.13f) }
            dump(stage.name, m)
        }
    }
}
