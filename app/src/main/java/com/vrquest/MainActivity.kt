package com.vrquest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Size
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), VrHost {
    override val act: MainActivity get() = this
    lateinit var hostLayout: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var head: HeadTracker
    private lateinit var hands: HandTracker
    private lateinit var scene: VrScene
    private lateinit var renderer: VrRenderer
    private var provider: ProcessCameraProvider? = null
    private val analysisExec = Executors.newSingleThreadExecutor()
    private var camGranted = false
    private var mediaCb: ((Boolean) -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null

    private val camLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> camGranted = ok; if (ok) { hands.start(); bindCamera() } else toast(L.t("Camera denied: passthrough and hand tracking off, gaze mode on", "Cameră refuzată: fără passthrough și mâini, rămâne modul privire")) }
    private val mediaLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res -> mediaCb?.invoke(res.values.any { it }); mediaCb = null }
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Crash.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.screenBrightness = 1f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }

        head = HeadTracker(this)
        hands = HandTracker(this)
        scene = VrScene(this)
        scene.head = head
        renderer = VrRenderer(scene, head, hands) { runOnUiThread { bindCamera() } }

        val root = FrameLayout(this)
        hostLayout = FrameLayout(this).apply { clipChildren = false }
        root.addView(hostLayout, FrameLayout.LayoutParams(-1, -1))   // hidden behind the GL surface; views are rendered into textures
        glView = GLSurfaceView(this).apply { setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 0, 0); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; preserveEGLContextOnPause = false }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // dock
        val dock = Panel(DockView(this, this), 1500, 170, "dock", "dock").apply { hasTitleBar = false; worldW = 1.4f; pos = V3(0f, -0.5f, -1.25f) }
        scene.dock = dock; scene.addPanel(dock, false)
        scene.createHud()
        val tt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setBackgroundColor(0xCC000000.toInt()); setPadding(20, 10, 20, 10) }
        val toastP = Panel(tt, 900, 110, "toast", "toast").apply { hasTitleBar = false; headLocked = true; worldW = 0.7f; pos = V3(0f, -0.3f, -1.1f) }
        scene.toastPanel = toastP; scene.addPanel(toastP, false)

        camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camGranted) hands.start() else camLauncher.launch(Manifest.permission.CAMERA)
        setEnv(Prefs.env.coerceIn(1, 7).let { if (it == 7) 1 else it })
        if (Prefs.firstRun) { Prefs.firstRun = false; open("welcome") }
        Crash.take()?.let { txt ->
            android.app.AlertDialog.Builder(this).setTitle(L.t("Last crash report (send this to the developer)", "Raport crash anterior (trimite-l dezvoltatorului)"))
                .setMessage(txt.take(3500)).setPositiveButton("OK", null)
                .setNeutralButton(L.t("Copy", "Copiază")) { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", txt)) }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume(); head.start(); scene.startPainter()
        if (camGranted) hands.start()
        Sfx.ambient(ambientFor(Prefs.env))
    }

    override fun onPause() {
        super.onPause()
        scene.stopPainter(); head.stop(); glView.onPause()
        try { provider?.unbindAll() } catch (_: Exception) {}
        hands.stop(); Sfx.stopAmbient()
    }

    override fun onDestroy() { super.onDestroy(); recognizer?.destroy(); analysisExec.shutdown() }

    // ---------- camera ----------
    private fun bindCamera() {
        if (!camGranted) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); provider = p; p.unbindAll()
                val rs = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val rs2 = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val preview = Preview.Builder().setResolutionSelector(rs).build()
                val main = ContextCompat.getMainExecutor(this)
                preview.setSurfaceProvider(main) { req ->
                    val st = renderer.cam.st
                    if (st == null) { req.willNotProvideSurface(); return@setSurfaceProvider }
                    st.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                    val s = Surface(st)
                    req.provideSurface(s, main) { s.release() }
                    req.setTransformationInfoListener(main) { info -> renderer.cam.rotation = info.rotationDegrees }
                }
                val an = ImageAnalysis.Builder().setResolutionSelector(rs2).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                an.setAnalyzer(analysisExec) { img -> hands.analyze(img) }
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, an)
            } catch (e: Exception) { toast("Camera: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- VrHost ----------
    override fun open(id: String) = ui { val app = Apps.create(id, this, this) ?: return@ui; show(app) }
    override fun openUrl(u: String) = ui { show(BrowserApp(this, this, false, u)) }

    private fun show(app: VrApp) {
        val id = app.id
        val existing = scene.panels.firstOrNull { it.appId == id && !it.closing }
        if (existing != null && app.singleton) { existing.pos = scene.freeSlot(existing.worldW, existing.pos.len().coerceIn(1.5f, 4f), existing); return }
        val wins = scene.panels.filter { it !== scene.dock && !it.headLocked && !it.closing }
        if (wins.size >= 5) scene.requestClose(wins.first())
        val content = app.build()
        lateinit var panel: Panel
        val frame = WindowFrame(this, app, { panel }, scene, content)
        panel = Panel(frame, app.pxW, app.pxH + WindowFrame.TITLE, app.title, id)
        panel.worldW = app.widthM; frame.tag = app
        scene.addPanel(panel, true)
    }

    override fun toggleVr() { Prefs.vrMode = !Prefs.vrMode; toast(if (Prefs.vrMode) L.t("VR split-screen", "VR dublu") else L.t("Flat view", "Vedere simplă")) }

    override fun close(p: Panel) = scene.requestClose(p)
    override fun recenter() { head.recenter(); scene.recenterWindows(); toast(L.t("View recentered", "Vedere recentrată")) }

    private fun ambientFor(e: Int) = when (e) { 1 -> 1; 2 -> 2; 3 -> 4; 4 -> 4; 5 -> 3; else -> 0 }
    override fun setEnv(id: Int) { Prefs.env = id; Prefs.passthrough = false; Sfx.ambient(ambientFor(id)); if (id != 7) renderer.setPano(null).let { } }
    override fun setPano(b: Bitmap?) { renderer.setPano(b) }
    override fun togglePassthrough() {
        if (!camGranted && !Prefs.passthrough) { toast(L.t("Camera permission needed", "Este nevoie de permisiunea pentru cameră")); camLauncher.launch(Manifest.permission.CAMERA); return }
        Prefs.passthrough = !Prefs.passthrough
        toast(if (Prefs.passthrough) L.t("Passthrough on", "Passthrough pornit") else L.t("Virtual environment", "Mediu virtual"))
    }
    override fun toast(msg: String) = ui { (scene.toastPanel?.view as? TextView)?.text = msg; scene.toastUntil = scene.nowTime() + 3f }
    override fun handStatus(): String = if (!camGranted) "no camera" else hands.status + (if (hands.status == "ready") " ${hands.fps.toInt()} fps" else "")
    override fun requestMedia(cb: (Boolean) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) cb(true) else { mediaCb = cb; mediaLauncher.launch(perms) }
    }

    // ---------- voice ----------
    override fun startVoice() = ui {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast(L.t("Speech recognition not available", "Recunoașterea vocală nu e disponibilă")); return }
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this); recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) { r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { toast("“$it”"); command(it.lowercase()) } }
            override fun onError(e: Int) { toast(L.t("Didn't catch that", "Nu am înțeles")) }
            override fun onReadyForSpeech(p: Bundle?) { toast(L.t("Listening…", "Ascult…")) }
            override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}; override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, b: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
    private fun command(t: String) {
        fun has(vararg k: String) = k.any { t.contains(it) }
        when {
            has("youtube") -> open("youtube")
            has("browser", "internet", "google") -> open("browser")
            has("gallery", "galerie", "poze") -> open("gallery")
            has("game", "joc") -> open("games")
            has("piano", "pian") -> open("piano")
            has("video", "film") -> open("video")
            has("store", "magazin") -> open("store")
            has("flat", "simpl") -> toggleVr()
            has("setting", "setări", "setari") -> open("settings")
            has("storm", "furtun") -> setEnv(2)
            has("rain", "ploa") -> setEnv(1)
            has("space", "spaț", "spat") -> setEnv(3)
            has("aurora") -> setEnv(4)
            has("sunset", "apus", "mare") -> setEnv(5)
            has("cinema") -> setEnv(6)
            has("passthrough", "camer") -> togglePassthrough()
            has("recenter", "centr") -> recenter()
            has("close", "închide", "inchide") -> scene.panels.filter { it !== scene.dock && !it.headLocked }.forEach { scene.requestClose(it) }
        }
    }

    // tap on the phone screen = click at the gaze cursor
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) scene.padPressed = true
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) scene.padPressed = false
        return true
    }

    // ---------- gamepad ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.source and InputDevice.SOURCE_GAMEPAD != 0 || e.source and InputDevice.SOURCE_JOYSTICK != 0) {
            val down = e.action == KeyEvent.ACTION_DOWN
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> { scene.padPressed = down; return true }
                KeyEvent.KEYCODE_BUTTON_B -> { if (down) recenter(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { if (down) scene.summonDock(); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { if (down) togglePassthrough(); return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { if (down) scene.cycleEnv(-1); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { if (down) scene.cycleEnv(1); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != 0 && e.action == MotionEvent.ACTION_MOVE) {
            val y = e.getAxisValue(MotionEvent.AXIS_RZ)
            if (kotlin.math.abs(y) > 0.3f) { scene.scrollAt(-y * 3f); return true }
        }
        return super.onGenericMotionEvent(e)
    }
}
