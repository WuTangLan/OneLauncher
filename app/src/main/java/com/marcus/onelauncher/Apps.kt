package com.marcus.onelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri

/** One thing the launcher can open: an app, an HDMI input or a shortcut. */
data class Tile(
    val label: String,
    val intent: Intent?,
    /** Wide TV banner; fills the whole tile when present. */
    val banner: Drawable? = null,
    /** Square icon, shown on a tinted card when there's no banner. */
    val icon: Drawable? = null,
    val iconRes: Int = 0,
    val tint: Int = 0xFF1C2540.toInt(),
    val packageName: String? = null,
    val installed: Boolean = true,
    /** Set for HDMI/AV inputs, so they can be renamed. */
    val inputId: String? = null,
    /** The TV's own name for an input, shown as "Reset to …". */
    val defaultLabel: String? = null,
    /** Special tile that opens the favourites editor when the row is empty. */
    val isAddFavourites: Boolean = false,
    /** Special tile that opens the Launcher options menu. */
    val opensOptions: Boolean = false,
)

/**
 * Default favourites, used until you add or remove any (hold OK on an app). Each entry lists
 * every package name the app has used, so it works whichever build is installed.
 */
data class Favourite(val label: String, val packages: List<String>)

val FAVOURITES = listOf(
    Favourite("Stremio", listOf("com.stremio.one")),
    Favourite(
        "SmartTube",
        listOf(
            "com.teamsmart.videomanager.tv",      // stable
            "com.liskovsoft.smarttubetv.beta",    // beta
            "com.liskovsoft.smarttubetv",
        ),
    ),
    Favourite(
        "HBO Max",
        listOf(
            "com.wbd.stream",          // current HBO Max app
            "com.hbo.hbonow",          // older builds
            "com.hbo.android.app",
        ),
    ),
)

object AppRepository {

    private fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun appTile(pm: PackageManager, pkg: String, label: String, intent: Intent): Tile {
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        val banner = try {
            info?.loadBanner(pm) ?: pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
        } catch (_: Exception) { null }
        val icon = if (banner == null) info?.loadIcon(pm) else null
        return Tile(
            label = label,
            intent = intent,
            banner = banner,
            icon = icon,
            tint = icon?.let(::cardTintFor) ?: 0xFF1C2540.toInt(),
            packageName = pkg,
        )
    }

    fun favourites(context: Context): List<Tile> {
        val pm = context.packageManager
        val tiles = Favourites.list(context).map { saved ->
            // A default app may be installed under another of its package names.
            val pkg = Favourites.alternates(saved).firstOrNull { launchIntent(pm, it) != null }
                ?.takeIf { launchIntent(pm, saved) == null } ?: saved
            val intent = launchIntent(pm, pkg)
            if (intent != null) {
                val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
                appTile(pm, pkg, Favourites.defaultLabel(pkg) ?: label, intent).copy(packageName = saved)
            } else {
                // Not installed: tile opens the Play Store page instead.
                val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$saved"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Tile(Favourites.defaultLabel(saved) ?: saved, store, iconRes = R.drawable.ic_download,
                    packageName = saved, installed = false)
            }
        }
        return tiles.ifEmpty {
            listOf(Tile("Add favourites", null, iconRes = R.drawable.ic_add, tint = 0xFF262A36.toInt(), isAddFavourites = true))
        }
    }

    /** Name + package of every launchable app, for the favourites checklist. Light: no images. */
    fun appLabels(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                out += pkg to ri.loadLabel(pm).toString()
            }
        }
        return out.sortedBy { it.second.lowercase() }
    }

    /** Every launchable app on the TV, alphabetical, excluding this launcher. */
    fun allApps(context: Context): List<Tile> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = ArrayList<Tile>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                val intent = launchIntent(pm, pkg) ?: continue
                result += appTile(pm, pkg, ri.loadLabel(pm).toString(), intent)
            }
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** HDMI / AV inputs reported by the TV, each opening straight into that source. */
    fun inputs(context: Context): List<Tile> {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val list = try { tim?.tvInputList.orEmpty() } catch (_: Exception) { emptyList() }
        val inputTint = 0xFF1E2A4A.toInt()

        val tiles = list
            .filter { it.isPassthroughInput && it.parentId == null && !it.isHidden(context) }
            .sortedWith(compareBy<TvInputInfo>({ it.type != TvInputInfo.TYPE_HDMI }, { it.id }))
            .map { info ->
                // The TV's name (including any name set in the TV's own menu)…
                val tvName = info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.loadLabel(context).toString()
                // …unless you renamed it in the launcher.
                val label = InputLabels.get(context, info.id) ?: tvName
                val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Tile(label, intent, iconRes = InputLabels.iconFor(label), tint = inputTint,
                    inputId = info.id, defaultLabel = tvName)
            }
            .toMutableList()

        // Live TV (tuner) shortcut.
        val liveTv = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (liveTv.resolveActivity(context.packageManager) != null) {
            tiles += Tile("Live TV", liveTv, iconRes = R.drawable.ic_tv, tint = inputTint)
        }

        tiles += Tile("Launcher options", null, iconRes = R.drawable.ic_tune, tint = 0xFF262A36.toInt(), opensOptions = true)
        tiles += Tile(
            "Settings",
            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            iconRes = R.drawable.ic_settings,
            tint = 0xFF262A36.toInt(),
        )
        return tiles
    }

    /**
     * Google TV's own Home panel (lights, cameras, thermostats), opened by the widget under the
     * weather. It lives in the screensaver package, so it works with the stock launcher disabled.
     */
    fun googleHomeIntent(): Intent =
        Intent("com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A dark, slightly muted version of the icon's average colour, used behind icon-only tiles. */
    private fun cardTintFor(icon: Drawable): Int = try {
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 12, 12)
        icon.draw(Canvas(bmp))
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 12) for (y in 0 until 12) {
            val c = bmp.getPixel(x, y)
            val a = Color.alpha(c)
            if (a < 128) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        bmp.recycle()
        if (n == 0L) 0xFF1C2540.toInt() else {
            val hsv = FloatArray(3)
            Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
            hsv[1] = (hsv[1] * 0.75f).coerceAtMost(0.7f)
            hsv[2] = 0.32f
            Color.HSVToColor(hsv)
        }
    } catch (_: Exception) {
        0xFF1C2540.toInt()
    }
}
