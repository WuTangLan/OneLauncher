package com.marcus.onelauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var ramPercent: TextView
    private lateinit var ramDetail: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var ftpStatus: TextView
    private lateinit var ftpLine1: TextView
    private lateinit var clockCard: View
    private lateinit var weatherBox: View
    private lateinit var weatherDivider: View
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherHigh: TextView
    private lateinit var weatherLow: TextView
    private lateinit var weatherLine: TextView
    private lateinit var wallpaperBack: ImageView
    private lateinit var wallpaperFront: ImageView
    private lateinit var wallpaperScrim: View

    private var cleaning = false
    private var ramMessageUntil = 0L
    private var hotBar = false
    private var resumed = false
    private var installDialog: AlertDialog? = null
    private var wallpaperDialog: AlertDialog? = null
    private var shownWallpaper: String? = null
    private var wallpaperJob = 0
    private var lastMinuteCheck = 0L
    /** True while the wallpaper picker is open, so rotation doesn't fight the live preview. */
    var pickerOpen = false
    /** Row + position to focus once the rows reload (after moving or renaming a tile). */
    private var focusAfterLoad: Pair<Int, Int>? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            updateRam()
            val now = System.currentTimeMillis()
            if (now - lastMinuteCheck > 60_000) {
                lastMinuteCheck = now
                if (!pickerOpen && Wallpapers.maybeRotate(this@MainActivity)) applyWallpaper(animate = true)
                updateWeather()
                maybeRefreshWeather()
            }
            ui.postDelayed(this, 2000)
        }
    }

    private val ftpListener: () -> Unit = {
        updateFtp()
        maybeOfferInstall()
        maybeOfferWallpaper()
    }

    // Refresh the rows when apps are installed or removed.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        ramPercent = findViewById(R.id.ram_percent)
        ramDetail = findViewById(R.id.ram_detail)
        ramBar = findViewById(R.id.ram_bar)
        ftpStatus = findViewById(R.id.ftp_status)
        ftpLine1 = findViewById(R.id.ftp_line1)
        clockCard = findViewById(R.id.clock_card)
        weatherBox = findViewById(R.id.weather_box)
        weatherDivider = findViewById(R.id.weather_divider)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weatherHigh = findViewById(R.id.weather_high)
        weatherLow = findViewById(R.id.weather_low)
        weatherLine = findViewById(R.id.weather_line)
        wallpaperBack = findViewById(R.id.wallpaper_back)
        wallpaperFront = findViewById(R.id.wallpaper_front)
        wallpaperScrim = findViewById(R.id.wallpaper_scrim)

        findViewById<View>(R.id.ram_card).apply {
            setOnClickListener { cleanRam() }
            onFocusChangeListener = cardFocus
        }
        findViewById<View>(R.id.ftp_card).apply {
            setOnClickListener { toggleFtp() }
            setOnLongClickListener { resetFtpPassword(); true }
            onFocusChangeListener = cardFocus
        }
        clockCard.apply {
            setOnClickListener { Customize(this@MainActivity).show() }
            onFocusChangeListener = cardFocus
        }

        thread { Wallpapers.ensureFolders(applicationContext) }
        applyWallpaper(animate = false)
        updateWeather()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        FtpService.addListener(ftpListener)

        loadRows()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ui.post(ticker)
        updateFtp()
        maybeOfferInstall()
        maybeOfferWallpaper()
        Wallpapers.maybeRotate(this)
        applyWallpaper(animate = false)
        updateWeather()
        maybeRefreshWeather()
        if (currentFocus == null) {
            findViewById<LinearLayout>(R.id.row_favourites).getChildAt(0)?.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        FtpService.removeListener(ftpListener)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // Pressing Home while already home jumps back to the first favourite.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        findViewById<LinearLayout>(R.id.row_favourites).getChildAt(0)?.requestFocus()
        findViewById<View>(R.id.root_scroll).scrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* a home screen stays put */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            Customize(this).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- rows

    fun loadRows() {
        thread {
            val favs = AppRepository.favourites(this)
            val inputs = AppRepository.inputs(this)
            val apps = AppRepository.allApps(this)
            ui.post {
                fillRow(R.id.row_favourites, favs)
                fillRow(R.id.row_inputs, inputs)
                fillRow(R.id.row_apps, apps)
                focusAfterLoad?.let { (rowId, index) ->
                    val row = findViewById<LinearLayout>(rowId)
                    row.getChildAt(index.coerceIn(0, maxOf(0, row.childCount - 1)))?.requestFocus()
                }
                focusAfterLoad = null
                if (currentFocus == null) {
                    findViewById<LinearLayout>(R.id.row_favourites).getChildAt(0)?.requestFocus()
                }
            }
        }
    }

    private fun fillRow(rowId: Int, tiles: List<Tile>) {
        val row = findViewById<LinearLayout>(rowId)
        val focusedIndex = (0 until row.childCount).firstOrNull { row.getChildAt(it).hasFocus() }
        row.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (tile in tiles) {
            val v = inflater.inflate(R.layout.tile, row, false)
            val card = v.findViewById<View>(R.id.tile_card)
            val bannerView = v.findViewById<ImageView>(R.id.tile_banner)
            val iconBox = v.findViewById<View>(R.id.tile_iconbox)
            val iconView = v.findViewById<ImageView>(R.id.tile_icon)
            val iconLabel = v.findViewById<TextView>(R.id.tile_icon_label)
            val label = v.findViewById<TextView>(R.id.tile_label)

            card.clipToOutline = true
            label.text = tile.label

            if (tile.banner != null) {
                bannerView.setImageDrawable(tile.banner)
                bannerView.visibility = View.VISIBLE
                iconBox.visibility = View.GONE
            } else {
                card.background = tileGradient(tile.tint)
                if (tile.icon != null) iconView.setImageDrawable(tile.icon) else iconView.setImageResource(tile.iconRes)
                iconLabel.text = tile.label
            }
            if (!tile.installed) {
                v.findViewById<View>(R.id.tile_badge).visibility = View.VISIBLE
                iconView.alpha = 0.6f
            }

            v.contentDescription = tile.label
            v.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                // Lift the artwork and reveal the name underneath (tvOS-style).
                zoom(card, has, 1.12f)
                label.visibility = if (has && tile.banner != null) View.VISIBLE else View.INVISIBLE
            }
            v.setOnClickListener {
                if (tile.isAddFavourites) Customize(this).editFavourites() else tile.intent?.let(::openIntent)
            }
            v.setOnLongClickListener { showTileOptions(tile, rowId, row.indexOfChild(v)); true }
            row.addView(v)
        }
        focusedIndex?.let { row.getChildAt(it.coerceAtMost(row.childCount - 1))?.requestFocus() }
    }

    // ---------------------------------------------------------------- hold-OK menus

    /** Hold OK on a tile: favourites for apps, rename for inputs. */
    private fun showTileOptions(tile: Tile, rowId: Int, index: Int) {
        val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
        when {
            tile.inputId != null -> renameInput(tile, rowId, index)
            tile.packageName != null -> {
                val pkg = tile.packageName
                val isFav = Favourites.contains(this, pkg)
                val inFavRow = rowId == R.id.row_favourites
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (isFav) {
                    if (inFavRow) {
                        actions += "Move left" to { moveFavourite(pkg, -1) }
                        actions += "Move right" to { moveFavourite(pkg, +1) }
                    }
                    actions += "Remove from Favourites" to {
                        Favourites.remove(this, pkg)
                        if (inFavRow) focusAfterLoad = rowId to index
                        loadRows()
                    }
                } else {
                    actions += "Add to Favourites" to {
                        Favourites.add(this, pkg)
                        toast("Added ${tile.label} to Favourites")
                        loadRows()
                    }
                }
                if (tile.installed) {
                    actions += "App info" to {
                        openIntent(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                AlertDialog.Builder(this, theme)
                    .setTitle(tile.label)
                    .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
                    .show()
            }
        }
    }

    private fun moveFavourite(pkg: String, delta: Int) {
        val newIndex = Favourites.move(this, pkg, delta)
        if (newIndex >= 0) {
            focusAfterLoad = R.id.row_favourites to newIndex
            loadRows()
        }
    }

    /** Rename an HDMI input: quick picks, your own name, or back to the TV's name. */
    fun renameInput(tile: Tile, rowId: Int = R.id.row_inputs, index: Int = 0, afterwards: (() -> Unit)? = null) {
        val id = tile.inputId ?: return
        val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
        val hasCustom = InputLabels.get(this, id) != null
        val options = mutableListOf("Type a name…")
        options += InputLabels.SUGGESTIONS
        if (hasCustom) options += "Reset to \"${tile.defaultLabel}\""

        fun done(label: String?) {
            InputLabels.set(this, id, label)
            focusAfterLoad = rowId to index
            loadRows()
            afterwards?.invoke()
        }

        AlertDialog.Builder(this, theme)
            .setTitle("Name for ${tile.label}")
            .setItems(options.toTypedArray()) { _, i ->
                when {
                    i == 0 -> {
                        val input = android.widget.EditText(this).apply {
                            setText(tile.label)
                            selectAll()
                            setSingleLine()
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        }
                        val box = android.widget.FrameLayout(this).apply {
                            val pad = (24 * resources.displayMetrics.density).toInt()
                            setPadding(pad, pad / 3, pad, 0)
                            addView(input)
                        }
                        val d = AlertDialog.Builder(this, theme)
                            .setTitle("Name for ${tile.label}")
                            .setView(box)
                            .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }
                            .setNegativeButton("Cancel", null)
                            .create()
                        input.setOnEditorActionListener { _, action, _ ->
                            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                                done(input.text.toString()); d.dismiss(); true
                            } else false
                        }
                        d.show()
                        input.requestFocus()
                    }
                    hasCustom && i == options.size - 1 -> done(null)
                    else -> done(options[i])
                }
            }
            .show()
    }

    /** Glass widgets become a white platter with dark text when focused. */
    private val cardFocus = View.OnFocusChangeListener { v, has ->
        zoom(v, has, 1.05f)
        applyPlatterColors(v, has)
    }

    private fun applyPlatterColors(v: View, focused: Boolean) {
        when (v) {
            is TextView -> {
                if (v.getTag(R.id.rest_color) == null) v.setTag(R.id.rest_color, v.currentTextColor)
                val rest = v.getTag(R.id.rest_color) as Int
                // Status colours (blue %, green "On") stay; everything else turns dark on white.
                val keepsColour = v.id == R.id.ram_percent || (v.id == R.id.ftp_status && FtpService.running)
                val dark = if (v.id == R.id.ftp_line1 || v.id == R.id.ram_detail) 0xFF636366.toInt() else 0xFF1C1C1E.toInt()
                v.setTextColor(if (focused && !keepsColour) dark else rest)
                // Drop text shadows on the white platter, restore them afterwards.
                if (v.getTag(R.id.rest_shadow) == null) v.setTag(R.id.rest_shadow, intArrayOf(v.shadowRadius.toBits(), v.shadowColor))
                val shadow = v.getTag(R.id.rest_shadow) as IntArray
                if (focused) v.setShadowLayer(0f, 0f, 0f, 0)
                else v.setShadowLayer(Float.fromBits(shadow[0]), 0f, 0f, shadow[1])
            }
            is ImageView -> v.imageTintList =
                if (focused) ColorStateList.valueOf(0xFF1C1C1E.toInt()) else null
            is ViewGroup -> for (i in 0 until v.childCount) applyPlatterColors(v.getChildAt(i), focused)
        }
    }

    /** Soft top-to-bottom gradient behind icon-only tiles. */
    private fun tileGradient(tint: Int): GradientDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(tint, hsv)
        val top = FloatArray(3).also { hsv.copyInto(it); it[2] = (hsv[2] + 0.12f).coerceAtMost(1f) }
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.HSVToColor(top), tint),
        ).apply { cornerRadius = 14f * resources.displayMetrics.density }
    }

    private fun zoom(v: View, focused: Boolean, scale: Float) {
        val s = if (focused) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        v.elevation = if (focused) 16f else 0f
    }

    fun openIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Can't open that on this TV")
        } catch (_: SecurityException) {
            toast("The TV blocked that shortcut")
        }
    }

    // ---------------------------------------------------------------- clock & RAM

    private fun updateClock() {
        val now = Date()
        clock.text = android.text.format.DateFormat.getTimeFormat(this).format(now)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        date.text = android.text.format.DateFormat.format(pattern, now)
    }

    private fun updateRam() {
        if (cleaning) return
        val r = RamCleaner.read(this)
        val hot = r.usedPercent >= 80
        if (hot != hotBar) {
            hotBar = hot
            ramBar.progressDrawable = getDrawable(if (hot) R.drawable.ram_bar_hot else R.drawable.ram_bar)
            ramPercent.setTextColor(if (hot) 0xFFFF9F0A.toInt() else 0xFF0A84FF.toInt())
            ramPercent.setTag(R.id.rest_color, ramPercent.currentTextColor)
        }
        ramBar.setProgress(r.usedPercent, true)
        ramPercent.text = "${r.usedPercent}%"
        if (System.currentTimeMillis() > ramMessageUntil) {
            ramDetail.text = "${RamCleaner.format(r.availBytes)} available · Select to clean"
        }
    }

    private fun cleanRam() {
        if (cleaning) return
        cleaning = true
        ramDetail.text = "Cleaning…"
        thread {
            val freed = RamCleaner.clean(this)
            ui.post {
                cleaning = false
                ramDetail.text = if (freed > 0) "Freed ${RamCleaner.format(freed)}" else "Already clean"
                ramMessageUntil = System.currentTimeMillis() + 5000
                updateRam()
            }
        }
    }

    // ---------------------------------------------------------------- FTP

    private fun toggleFtp() {
        if (FtpService.running) {
            FtpService.stop(this)
        } else {
            FtpService.start(this)
            ftpLine1.text = "Starting…"
        }
    }

    private fun resetFtpPassword() {
        FtpService.newPassword(this)
        if (FtpService.running) {
            FtpService.stop(this)
            ui.postDelayed({ FtpService.start(this) }, 400)
        }
        updateFtp()
        toast("New FTP password: ${FtpService.password(this)}")
    }

    private fun updateFtp() {
        val on = FtpService.running
        ftpStatus.text = if (on) "On" else "Off"
        ftpStatus.setBackgroundResource(if (on) R.drawable.pill_on else R.drawable.pill_off)
        val pillColor = if (on) 0xFF30D158.toInt() else 0xB3FFFFFF.toInt()
        ftpStatus.setTextColor(pillColor)
        ftpStatus.setTag(R.id.rest_color, pillColor)

        ftpLine1.text = when {
            on -> "${FtpService.ipAddress() ?: "No network"}:${FtpService.PORT}  ·  " +
                "${FtpService.USER} / ${FtpService.password(this)}"
            FtpService.lastError != null -> "${FtpService.lastError} · Select to retry"
            else -> "Select to send files from your PC or phone"
        }
        val card = findViewById<View>(R.id.ftp_card)
        if (card.hasFocus() || card.isFocused) applyPlatterColors(card, true)
    }

    /** When an APK arrives over FTP, offer to install it. */
    private fun maybeOfferInstall() {
        val apk = FtpService.pendingApk ?: return
        if (!resumed || installDialog?.isShowing == true) return
        FtpService.pendingApk = null

        installDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Install ${apk.name}?")
            .setMessage("This app was just sent to the TV over FTP.")
            .setPositiveButton("Install") { _, _ ->
                if (ApkInstaller.canInstall(this)) {
                    ApkInstaller.install(this, apk)
                } else {
                    toast("Allow One Launcher to install apps, then press Back")
                    FtpService.pendingApk = apk
                    openIntent(ApkInstaller.permissionIntent(this))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---------------------------------------------------------------- wallpaper

    /** Shows [key] (or the saved wallpaper when null), fading it in over the current one. */
    fun applyWallpaper(animate: Boolean, key: String? = null) {
        val choice = key?.let { Wallpapers.byKey(this, it) } ?: Wallpapers.current(this)
        if (choice.key == shownWallpaper) return
        shownWallpaper = choice.key
        val job = ++wallpaperJob
        val app = applicationContext
        thread {
            val d = Wallpapers.load(app, choice)
            ui.post {
                if (job != wallpaperJob) return@post // a newer choice won
                if (d == null) shownWallpaper = null
                showWallpaper(d ?: getDrawable(R.drawable.wallpaper), choice.isPhoto && d != null, animate)
            }
        }
    }

    private fun showWallpaper(d: Drawable?, photo: Boolean, animate: Boolean) {
        wallpaperFront.animate().cancel()
        wallpaperBack.setImageDrawable(wallpaperFront.drawable)
        wallpaperFront.setImageDrawable(d)
        wallpaperScrim.visibility = View.VISIBLE
        val scrimAlpha = if (photo) 1f else 0f
        if (animate) {
            wallpaperFront.alpha = 0f
            wallpaperFront.animate().alpha(1f).setDuration(450)
                .withEndAction { wallpaperBack.setImageDrawable(null) }.start()
            wallpaperScrim.animate().alpha(scrimAlpha).setDuration(450).start()
        } else {
            wallpaperFront.alpha = 1f
            wallpaperBack.setImageDrawable(null)
            wallpaperScrim.alpha = scrimAlpha
        }
    }

    /** A photo was sent to the Wallpapers folder over FTP: offer to use it. */
    private fun maybeOfferWallpaper() {
        val file = FtpService.pendingWallpaper ?: return
        if (!resumed || wallpaperDialog?.isShowing == true) return
        FtpService.pendingWallpaper = null
        wallpaperDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New wallpaper received")
            .setMessage("Use \"${file.nameWithoutExtension}\" as your wallpaper?")
            .setPositiveButton("Use it") { _, _ ->
                Wallpapers.set(this, "file:${file.absolutePath}")
                applyWallpaper(animate = true)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------------------------------------------------------------- weather

    private fun updateWeather() {
        val w = if (WeatherRepository.enabled(this)) WeatherRepository.cached(this) else null
        val vis = if (w != null) View.VISIBLE else View.GONE
        weatherBox.visibility = vis
        weatherDivider.visibility = vis
        if (w == null) return
        weatherIcon.setImageResource(WeatherRepository.icon(w.code, w.isDay))
        weatherTemp.text = "${w.temp}°"
        weatherHigh.text = "H ${w.high}°"
        weatherLow.text = "L ${w.low}°"
        weatherLine.text = listOf(WeatherRepository.condition(w.code), w.place)
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        if (clockCard.isFocused) applyPlatterColors(clockCard, true)
    }

    private fun maybeRefreshWeather() {
        if (!WeatherRepository.needsRefresh(this)) return
        val app = applicationContext
        thread {
            WeatherRepository.refresh(app)
            ui.post { updateWeather() }
        }
    }

    /** Called by Customize after a weather setting changes. */
    fun onWeatherSettingsChanged() {
        updateWeather()
        maybeRefreshWeather()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
