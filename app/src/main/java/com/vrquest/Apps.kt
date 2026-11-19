package com.vrquest

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlin.math.*

object Apps {
    fun create(id: String, ctx: Context, host: VrHost): VrApp? = when (id) {
        "youtube" -> BrowserApp(ctx, host, true)
        "browser" -> BrowserApp(ctx, host, false)
        "gallery" -> GalleryApp(ctx, host)
        "games" -> GamesApp(ctx, host)
        "env" -> EnvApp(ctx, host)
        "settings" -> SettingsApp(ctx, host)
        "apps" -> DrawerApp(ctx, host)
        "welcome" -> WelcomeApp(ctx, host)
        "store" -> StoreApp(ctx, host)
        "video" -> VideoApp(ctx, host)
        "mole" -> MoleApp(ctx, host)
        "piano" -> PianoApp(ctx, host)
        "bubbles" -> BubbleApp(ctx, host)
        "fruit" -> FruitApp(ctx, host)
        "ape" -> ApeApp(ctx, host)
        else -> null
    }
}

// ---------- virtual keyboard ----------
class VrKeyboard(ctx: Context, private val onKey: (String) -> Unit) : LinearLayout(ctx) {
    private var shift = false
    private val letters = ArrayList<TextView>()
    init {
        orientation = VERTICAL; setBackgroundColor(Theme.bar); setPadding(10, 8, 10, 8)
        val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        for ((ri, r) in rows.withIndex()) {
            val row = LinearLayout(ctx); row.gravity = Gravity.CENTER
            if (ri == 3) row.addView(key("⇧", 1.5f) { shift = !shift; for (t in letters) t.text = if (shift) t.text.toString().uppercase() else t.text.toString().lowercase() })
            for (ch in r) { val k = key(ch.toString(), 1f) { onKey(if (shift) it.uppercase() else it) }; if (ch.isLetter()) letters.add(k); row.addView(k) }
            if (ri == 3) row.addView(key("⌫", 1.5f) { onKey("⌫") })
            addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val last = LinearLayout(ctx); last.gravity = Gravity.CENTER
        last.addView(key(".", 1f) { onKey(".") }); last.addView(key("/", 1f) { onKey("/") })
        last.addView(key(L.t("space", "spațiu"), 5f) { onKey(" ") }); last.addView(key("-", 1f) { onKey("-") })
        last.addView(key("↵", 2f, Theme.accent) { onKey("↵") })
        addView(last, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private fun key(t: String, w: Float, bg: Int = Theme.card, f: (String) -> Unit): TextView =
        tv(context, t, 34f, if (bg == Theme.accent) Color.BLACK else Theme.text).apply {
            gravity = Gravity.CENTER; background = rounded(bg, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply { setMargins(4, 4, 4, 4) }
            setOnClickListener { Sfx.click(); f(t) }
        }
}

// ---------- browser / YouTube ----------
class BrowserApp(ctx: Context, host: VrHost, val yt: Boolean, val start: String? = null) : VrApp(ctx, host) {
    override val id = if (yt) "youtube" else if (start != null) "web:$start" else "browser"
    override val title = if (yt) "YouTube" else "Browser"
    override val icon = if (yt) "youtube" else "browser"
    override val pxH = 760
    override val singleton = !yt && false
    private var web: WebView? = null
    private lateinit var urlView: TextView
    private val typed = StringBuilder()
    private var typing = false
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val home get() = start ?: if (yt) "https://www.youtube.com/" else "https://www.google.com/"

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val kb = VrKeyboard(ctx) { k ->
            when (k) { "⌫" -> if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1); "↵" -> { submit(); return@VrKeyboard }; else -> typed.append(k) }
            urlView.text = typed.toString() + "|"
        }.apply { visibility = View.GONE }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Theme.card); setPadding(10, 0, 10, 0) }
        fun btn(kind: String, f: () -> Unit) = bar.addView(IconBtn(ctx, kind, Theme.text) { f() }, LinearLayout.LayoutParams(84, 84))
        btn("back") { if (web?.canGoBack() == true) web?.goBack() }
        btn("fwd") { if (web?.canGoForward() == true) web?.goForward() }
        btn("reload") { web?.reload() }
        btn("home") { web?.loadUrl(home) }
        urlView = tv(ctx, if (yt) L.t("Search YouTube", "Caută pe YouTube") else L.t("Search or type address", "Caută sau scrie adresa"), 30f, Theme.sub).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(Theme.bg, 40f); setPadding(32, 0, 32, 0); maxLines = 1
            setOnClickListener { typing = true; typed.setLength(0); text = "|"; setTextColor(Theme.text); kb.visibility = View.VISIBLE }
        }
        bar.addView(urlView, LinearLayout.LayoutParams(0, 64, 1f).apply { setMargins(8, 0, 8, 0) })
        btn("search") { submit() }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90))

        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.useWideViewPort = true; w.settings.loadWithOverviewMode = true
        w.settings.userAgentString = ua
        CookieManager.getInstance().setAcceptCookie(true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { if (!typing) { urlView.text = url; urlView.setTextColor(Theme.sub) } }
        }
        w.webChromeClient = WebChromeClient()
        w.loadUrl(home)
        web = w
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 330))
        keyboard = kb
        return root
    }
    private var keyboard: View? = null

    private fun submit() {
        val q = typed.toString().trim(); typing = false; keyboard?.visibility = View.GONE
        if (q.isEmpty()) return
        val url = when {
            yt -> "https://www.youtube.com/results?search_query=" + Uri.encode(q)
            q.startsWith("http") -> q
            q.contains('.') && !q.contains(' ') -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        web?.loadUrl(url)
    }
    override fun onClose() { web?.apply { loadUrl("about:blank"); stopLoading(); destroy() }; web = null }
}

// ---------- gallery (+ 360° as a VR environment) ----------
class GalleryApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "gallery"; override val title = L.t("Gallery", "Galerie"); override val icon = "gallery"
    private val root = FrameLayout(ctx)
    private val uris = ArrayList<Uri>()
    private val cache = HashMap<Int, Bitmap>()

    override fun build(): View {
        root.setBackgroundColor(Theme.bg)
        val msg = tv(ctx, L.t("Loading…", "Se încarcă…"), 34f, Theme.sub).apply { gravity = Gravity.CENTER }
        root.addView(msg, FrameLayout.LayoutParams(-1, -1))
        host.requestMedia { ok ->
            if (!ok) { msg.text = L.t("Allow photo access in Android settings, then reopen Gallery.", "Permite accesul la poze din setările Android, apoi redeschide Galeria."); return@requestMedia }
            Thread {
                try {
                    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), null, null, "date_added DESC")?.use { c ->
                        var n = 0; while (c.moveToNext() && n < 240) { uris.add(ContentUris.withAppendedId(base, c.getLong(0))); n++ }
                    }
                } catch (_: Exception) {}
                ui { if (uris.isEmpty()) msg.text = L.t("No photos found.", "Nu s-au găsit poze.") else showGrid() }
            }.start()
        }
        return root
    }

    private fun decode(u: Uri, side: Int): Bitmap? = try {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (max(o.outWidth, o.outHeight) / s > side * 2) s *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = s
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Throwable) { null }

    private fun showGrid() {
        root.removeAllViews()
        val grid = GridView(ctx); grid.numColumns = 5; grid.horizontalSpacing = 8; grid.verticalSpacing = 8; grid.setPadding(8, 8, 8, 8)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = uris.size
            override fun getItem(i: Int) = uris[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val iv = (cv as? ImageView) ?: ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = AbsListView.LayoutParams(240, 240); setBackgroundColor(Theme.card) }
                iv.tag = i; val c = cache[i]
                if (c != null) iv.setImageBitmap(c) else { iv.setImageDrawable(null); Thread { val b = decode(uris[i], 240); if (b != null) { cache[i] = b; ui { if (iv.tag == i) iv.setImageBitmap(b) } } }.start() }
                return iv
            }
        }
        grid.setOnItemClickListener { _, _, i, _ -> showPhoto(i) }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showPhoto(i0: Int) {
        var i = i0
        root.removeAllViews()
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        fun load() { val u = uris[i]; iv.setImageDrawable(null); Thread { val b = decode(u, 1600); ui { iv.setImageBitmap(b) } }.start() }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1)); load()
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setBackgroundColor(0x99000000.toInt()) }
        row.addView(button(ctx, "◀") { i = (i + uris.size - 1) % uris.size; load() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 6, 10, 6) })
        row.addView(button(ctx, L.t("Grid", "Grilă")) { showGrid() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 6, 10, 6) })
        row.addView(button(ctx, "360° VR", 30f, Theme.accent, Color.BLACK) {
            Thread { val b = decode(uris[i], 4096); ui { if (b != null) { host.setPano(b); host.setEnv(7) } } }.start()
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 6, 10, 6) })
        row.addView(button(ctx, "▶") { i = (i + 1) % uris.size; load() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 6, 10, 6) })
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }
}

// ---------- environments ----------
class EnvApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "env"; override val title = L.t("Environments", "Fundaluri"); override val icon = "env"
    override fun build(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(24, 20, 24, 20) }
        val items = listOf(
            Triple(1, L.t("Rain Night", "Ploaie de noapte"), intArrayOf(0xFF3A4A5E.toInt(), 0xFF0B1118.toInt())),
            Triple(2, L.t("Thunderstorm", "Furtună"), intArrayOf(0xFF2B3140.toInt(), 0xFF05070B.toInt())),
            Triple(3, L.t("Space", "Spațiu"), intArrayOf(0xFF7B2D8E.toInt(), 0xFF02030A.toInt())),
            Triple(4, L.t("Aurora", "Aurora boreală"), intArrayOf(0xFF2EE59D.toInt(), 0xFF020510.toInt())),
            Triple(5, L.t("Sunset Sea", "Apus la mare"), intArrayOf(0xFFFF8A3D.toInt(), 0xFF4A2A6B.toInt())),
            Triple(6, L.t("Cinema", "Cinema"), intArrayOf(0xFF2A2A33.toInt(), 0xFF040406.toInt()))
        )
        val grid = GridLayout(ctx).apply { columnCount = 3 }
        for ((id, name, cols) in items) {
            val card = TextView(ctx).apply {
                text = name; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34f); setTextColor(Color.WHITE); gravity = Gravity.BOTTOM or Gravity.START; setPadding(24, 24, 24, 24)
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, cols).apply { cornerRadius = 28f }
                setOnClickListener { Sfx.click(); host.setEnv(id) }
            }
            grid.addView(card, GridLayout.LayoutParams().apply { width = 390; height = 220; setMargins(10, 10, 10, 10) })
        }
        col.addView(grid)
        col.addView(button(ctx, L.t("Passthrough camera (mixed reality)", "Cameră (realitate mixtă)")) { host.togglePassthrough() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 14, 10, 8) })
        col.addView(slider(ctx, L.t("Volume", "Volum"), 0f, 1f, Prefs.volume) { Prefs.volume = it })
        return col
    }
}

fun slider(ctx: Context, label: String, min: Float, max: Float, value: Float, fmt: (Float) -> String = { "%.2f".format(it) }, onChange: (Float) -> Unit): View {
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 4, 10, 4) }
    val name = tv(ctx, label, 28f); val v = tv(ctx, fmt(value), 28f, Theme.accent).apply { gravity = Gravity.END }
    val sb = SeekBar(ctx); sb.max = 1000; sb.progress = ((value - min) / (max - min) * 1000).toInt()
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { val x = min + (max - min) * p / 1000f; v.text = fmt(x); if (u) onChange(x) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    })
    row.addView(name, LinearLayout.LayoutParams(330, -2)); row.addView(sb, LinearLayout.LayoutParams(0, 64, 1f)); row.addView(v, LinearLayout.LayoutParams(130, -2))
    return row
}

fun toggle(ctx: Context, label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
    val sw = Switch(ctx); sw.isChecked = value; sw.setOnCheckedChangeListener { _, b -> onChange(b) }
    return LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(10, 6, 10, 6); addView(tv(ctx, label, 28f), LinearLayout.LayoutParams(0, -2, 1f)); addView(sw, LinearLayout.LayoutParams(120, 56)) }
}

// ---------- settings ----------
class SettingsApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "settings"; override val title = L.t("Settings", "Setări"); override val icon = "settings"
    override val pxH = 760
    override fun build(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 10, 24, 30) }
        fun h(t: String) = col.addView(tv(ctx, t, 32f, Theme.accent, true).apply { setPadding(10, 22, 0, 4) })
        h(L.t("Display & lenses", "Ecran și lentile"))
        col.addView(toggle(ctx, L.t("VR split-screen (off = flat, test on phone)", "VR dublu (oprit = o singură imagine, test pe telefon)"), Prefs.vrMode) { Prefs.vrMode = it })
        col.addView(slider(ctx, "IPD (mm)", 50f, 75f, Prefs.ipdMm, { "%.0f".format(it) }) { Prefs.ipdMm = it })
        col.addView(slider(ctx, L.t("Lens offset", "Distanța lentilelor"), -0.3f, 0.3f, Prefs.lensSep) { Prefs.lensSep = it })
        col.addView(slider(ctx, "FOV", 70f, 120f, Prefs.fov, { "%.0f°".format(it) }) { Prefs.fov = it })
        col.addView(slider(ctx, L.t("Distortion k1", "Distorsiune k1"), 0f, 0.6f, Prefs.k1) { Prefs.k1 = it })
        col.addView(slider(ctx, L.t("Distortion k2", "Distorsiune k2"), 0f, 0.3f, Prefs.k2) { Prefs.k2 = it })
        col.addView(slider(ctx, L.t("Image zoom", "Zoom imagine"), 0.7f, 1.3f, Prefs.zoom) { Prefs.zoom = it })
        col.addView(slider(ctx, L.t("Render scale", "Rezoluție"), 0.4f, 1f, Prefs.renderScale) { Prefs.renderScale = it })
        col.addView(toggle(ctx, L.t("60 FPS (off = 30 FPS, cooler)", "60 FPS (oprit = 30 FPS, mai rece)"), Prefs.fps60) { Prefs.fps60 = it })
        h(L.t("Hands", "Mâini"))
        col.addView(toggle(ctx, L.t("Hand tracking", "Detectare mâini"), Prefs.handsOn) { Prefs.handsOn = it })
        col.addView(slider(ctx, L.t("Sensitivity", "Sensibilitate"), 0.6f, 1.8f, Prefs.handSens) { Prefs.handSens = it })
        col.addView(slider(ctx, L.t("Smoothing", "Netezire"), 0f, 1f, Prefs.smoothing) { Prefs.smoothing = it })
        col.addView(slider(ctx, L.t("Poke sensitivity", "Sensibilitate apăsare"), 0.4f, 2f, Prefs.pokeSens) { Prefs.pokeSens = it })
        col.addView(toggle(ctx, L.t("Gaze + dwell click when no hand", "Privire + click automat fără mână"), Prefs.gaze) { Prefs.gaze = it })
        col.addView(toggle(ctx, L.t("Mirror camera image", "Oglindește camera"), Prefs.camMirror) { Prefs.camMirror = it })
        col.addView(toggle(ctx, L.t("Debug overlay (FPS, hands)", "Overlay debug"), Prefs.debug) { Prefs.debug = it })
        h(L.t("Passthrough camera", "Camera (passthrough)"))
        col.addView(slider(ctx, L.t("Camera FOV", "FOV cameră"), 50f, 100f, Prefs.camFov, { "%.0f°".format(it) }) { Prefs.camFov = it })
        col.addView(slider(ctx, L.t("Passthrough zoom", "Zoom cameră"), 0.6f, 2f, Prefs.ptZoom) { Prefs.ptZoom = it })
        h(L.t("General", "General"))
        col.addView(toggle(ctx, L.t("Dark theme (new windows)", "Temă întunecată (ferestre noi)"), Prefs.dark) { Prefs.dark = it })
        col.addView(slider(ctx, L.t("Volume", "Volum"), 0f, 1f, Prefs.volume) { Prefs.volume = it })
        col.addView(button(ctx, L.t("Recenter view", "Recentrează")) { host.recenter() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 16, 10, 8) })
        col.addView(button(ctx, L.t("Show tutorial", "Arată tutorialul")) { host.open("welcome") }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 8, 10, 8) })
        return ScrollView(ctx).apply { setBackgroundColor(Theme.bg); addView(col) }
    }
}

// ---------- app drawer ----------
class DrawerApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "apps"; override val title = L.t("All apps", "Toate aplicațiile"); override val icon = "apps"
    override fun build(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg) }
        col.addView(tv(ctx, L.t("Tapping an app opens it on the phone (outside VR). Return to VrQuest with the Recent button.", "Atingerea unei aplicații o deschide pe telefon (în afara VR). Revino în VrQuest din butonul Recente."), 24f, Theme.sub).apply { setPadding(24, 12, 24, 8) })
        val pm = ctx.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).filter { it.activityInfo.packageName != ctx.packageName }.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val grid = GridView(ctx); grid.numColumns = 6; grid.setPadding(10, 10, 10, 10)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                val l = (cv as? LinearLayout) ?: LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(6, 10, 6, 10)
                    addView(ImageView(ctx), LinearLayout.LayoutParams(110, 110)); addView(tv(ctx, "", 22f).apply { gravity = Gravity.CENTER; maxLines = 1 }) }
                (l.getChildAt(0) as ImageView).setImageDrawable(list[i].loadIcon(pm)); (l.getChildAt(1) as TextView).text = list[i].loadLabel(pm)
                return l
            }
        }
        grid.setOnItemClickListener { _, _, i, _ ->
            try { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(list[i].activityInfo.packageName, list[i].activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }
}

// ---------- games launcher ----------
class GamesApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "games"; override val title = L.t("Games", "Jocuri"); override val icon = "games"
    override val pxH = 760
    override fun build(): View {
        val row = GridLayout(ctx).apply { columnCount = 2; setBackgroundColor(Theme.bg); setPadding(24, 24, 24, 24) }
        val g = listOf(
            Triple("piano", "🎹  " + L.t("Piano", "Pian"), L.t("Play with both hands", "Cântă cu ambele mâini")),
            Triple("bubbles", "🫧  " + L.t("Bubble Pop", "Sparge bule"), L.t("Pop bubbles with your fingers", "Sparge bulele cu degetele")),
            Triple("fruit", "🍉  " + L.t("Fruit Slice", "Taie fructe"), L.t("Swipe to slice, avoid bombs", "Taie fructele, evită bombele")),
            Triple("ape", "🦍  " + L.t("Ape Tag", "Gorila te prinde"), L.t("Run from the gorilla, grab bananas", "Fugi de gorilă, adună banane")),
            Triple("mole", "🐹  " + L.t("Whack-a-Mole", "Dă în cârtiță"), L.t("Hit the moles with your hand", "Lovește cârtițele cu mâna")),
            Triple("store", "🛒  " + L.t("Store", "Magazin"), L.t("Web games and apps", "Jocuri și aplicații web"))
        )
        for ((id, name, sub) in g) {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(34, 20, 34, 20); background = rounded(Theme.card, 30f); setOnClickListener { Sfx.click(); host.open(id) } }
            c.addView(tv(ctx, name, 42f, Theme.text, true)); c.addView(tv(ctx, sub, 26f, Theme.sub))
            row.addView(c, GridLayout.LayoutParams().apply { width = 590; height = 220; setMargins(10, 10, 10, 10) })
        }
        return row
    }
}

// ---------- welcome / tutorial ----------
class WelcomeApp(ctx: Context, host: VrHost) : VrApp(ctx, host) {
    override val id = "welcome"; override val title = L.t("Welcome to VrQuest", "Bun venit în VrQuest"); override val icon = "vr"
    override val pxH = 700
    override fun build(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(36, 24, 36, 24) }
        val lines = listOf(
            L.t("☝️  Point: the cursor follows your index fingertip", "☝️  Arată: cursorul urmează vârful degetului arătător"),
            L.t("🤏  Pinch (thumb + index): click, drag, scroll", "🤏  Ciupește (deget mare + arătător): click, trage, derulează"),
            L.t("✊  Fist on a window: grab and move it (push hand away = farther)", "✊  Pumn pe o fereastră: o muți (împingi mâna = mai departe)"),
            L.t("👉  Poke forward with one finger: press buttons", "👉  Împinge înainte cu un deget: apeși butoane"),
            L.t("🖐️  Open palm for 1 s: bring the dock to you", "🖐️  Palma deschisă 1 s: aduce bara la tine"),
            L.t("↔️  Swipe open palm left/right: change environment", "↔️  Mișcă palma stânga/dreapta: schimbă fundalul"),
            L.t("🤏🤏  Two hands pinching one window: resize like a TV", "🤏🤏  Două mâini pe o fereastră: o mărești ca pe un TV"),
            L.t("👀  No hand? Look at a button ~1.5 s to click (gaze)", "👀  Fără mână? Uită-te 1,5 s la un buton ca să dai click")
        )
        for (l in lines) col.addView(tv(ctx, l, 30f).apply { setPadding(0, 8, 0, 8) })
        val st = tv(ctx, "", 26f, Theme.sub).apply { setPadding(0, 18, 0, 0) }
        col.addView(st)
        val upd = object : Runnable { override fun run() { st.text = L.t("Hand model: ", "Model mâini: ") + host.handStatus(); st.postDelayed(this, 1000) } }
        st.post(upd)
        col.addView(button(ctx, L.t("Start", "Începe")) { host.open("env") }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14 })
        return col
    }
}
