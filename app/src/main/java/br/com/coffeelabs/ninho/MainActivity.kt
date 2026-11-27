package br.com.coffeelabs.ninho

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout

class MainActivity : Activity(), GameView.Host {

    private lateinit var st: DragonState
    private lateinit var game: GameView
    private lateinit var gl: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = DragonState.load(this)
        val scene = Scene()
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(MsaaChooser())
            setRenderer(GLRenderer(scene))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        game = GameView(this, st, this, scene)
        val root = FrameLayout(this)
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        root.addView(game, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        setupEdgeToEdge()
        MealScheduler.ensureChannel(this)
        if (!st.exists) askName(first = true) else MealScheduler.scheduleAll(this)
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        game.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                game.setInsets(i.top, i.bottom)
            } else {
                game.setInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    @Suppress("DEPRECATION")
    override fun onDarkSky(dark: Boolean) {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                window.decorView.systemUiVisibility = if (dark) base else base or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        game.resume()
    }

    override fun onPause() {
        game.pause()
        gl.onPause()
        st.save(this)
        super.onPause()
    }

    override fun saveState() {
        st.save(this)
    }

    override fun askNewEgg() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Chocar um novo ovo?")
            .setMessage("${st.name} vai seguir a vida na floresta e um novo ovo prateado aparecerá sobre a folha. Não dá para desfazer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Novo ovo") { _, _ -> askName(first = false) }
            .show()
    }

    private fun askName(first: Boolean) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Lumi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(14))
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Um ovo prateado apareceu!")
            .setMessage("Ele brilha com reflexos azulados. Que nome você quer dar ao seu dragão?")
            .setView(box)
            .setCancelable(!first)
            .setPositiveButton("Levar ao lago") { _, _ ->
                val nm = input.text.toString().trim().ifEmpty { "Lumi" }
                st.newEgg(nm, System.currentTimeMillis())
                st.save(this)
                game.onNewDragon()
                MealScheduler.scheduleAll(this)
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                }
            }
            .show()
    }
}
