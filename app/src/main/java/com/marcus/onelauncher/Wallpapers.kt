package com.marcus.onelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A wallpaper option: a built-in preset or an image file. */
data class WallpaperChoice(val key: String, val label: String, val res: Int = 0, val file: File? = null) {
    val isPhoto get() = file != null
}

/**
 * Wallpapers: five built-in looks plus any JPG/PNG/WebP the user drops into a "Wallpapers"
 * folder (over FTP, or on a USB stick when all-files access is granted).
 */
object Wallpapers {

    private const val PREFS = "launcher"
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    val PRESETS = listOf(
        WallpaperChoice("preset:midnight", "Midnight", res = R.drawable.wallpaper),
        WallpaperChoice("preset:aurora", "Aurora", res = R.drawable.wp_aurora),
        WallpaperChoice("preset:sunset", "Sunset", res = R.drawable.wp_sunset),
        WallpaperChoice("preset:ocean", "Ocean", res = R.drawable.wp_ocean),
        WallpaperChoice("preset:graphite", "Graphite", res = R.drawable.wp_graphite),
    )

    /** Rotation choices in minutes (0 = off). */
    val ROTATION = listOf(0 to "Off", 60 to "Every hour", 360 to "Every 6 hours", 1440 to "Every day")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Folders scanned for images. The first one always exists and is reachable over FTP. */
    fun folders(ctx: Context): List<File> {
        val dirs = mutableListOf(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Wallpapers"))
        if (FtpService.hasFullAccess()) {
            val storage = Environment.getExternalStorageDirectory()
            dirs += File(storage, "Wallpapers")
            dirs += File(storage, "Pictures/Wallpapers")
            // USB sticks / SD cards: a "Wallpapers" folder at the top of the drive.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sm = ctx.getSystemService(StorageManager::class.java)
                sm?.storageVolumes?.filter { it.isRemovable }?.mapNotNull { it.directory }?.forEach {
                    dirs += File(it, "Wallpapers")
                }
            }
        }
        return dirs
    }

    /** Creates the default Wallpapers folder(s) so they show up in FTP clients. */
    fun ensureFolders(ctx: Context) {
        folders(ctx).take(2).forEach { runCatching { it.mkdirs() } }
    }

    fun isImage(f: File) = f.isFile && f.extension.lowercase() in IMAGE_EXT

    fun userImages(ctx: Context): List<WallpaperChoice> =
        folders(ctx)
            .flatMap { dir -> dir.listFiles()?.filter(::isImage).orEmpty() }
            .distinctBy { it.canonicalPath }
            .sortedBy { it.name.lowercase() }
            .map { WallpaperChoice("file:${it.absolutePath}", it.nameWithoutExtension, file = it) }

    fun all(ctx: Context) = PRESETS + userImages(ctx)

    fun currentKey(ctx: Context): String = prefs(ctx).getString("wallpaper", null) ?: PRESETS[0].key

    fun current(ctx: Context): WallpaperChoice = byKey(ctx, currentKey(ctx)) ?: PRESETS[0]

    fun byKey(ctx: Context, key: String): WallpaperChoice? = when {
        key.startsWith("preset:") -> PRESETS.firstOrNull { it.key == key }
        key.startsWith("file:") -> File(key.removePrefix("file:")).takeIf { it.isFile }
            ?.let { WallpaperChoice(key, it.nameWithoutExtension, file = it) }
        else -> null
    }

    fun set(ctx: Context, key: String) =
        prefs(ctx).edit().putString("wallpaper", key).putLong("rotated_at", System.currentTimeMillis()).apply()

    fun rotationMinutes(ctx: Context) = prefs(ctx).getInt("rotate_minutes", 0)
    fun setRotationMinutes(ctx: Context, m: Int) =
        prefs(ctx).edit().putInt("rotate_minutes", m).putLong("rotated_at", System.currentTimeMillis()).apply()

    /**
     * Advances to the next wallpaper when rotation is on and the interval has passed.
     * Cycles through your own images, or the presets if there are none. Returns true if it changed.
     */
    fun maybeRotate(ctx: Context): Boolean {
        val minutes = rotationMinutes(ctx)
        if (minutes <= 0) return false
        val last = prefs(ctx).getLong("rotated_at", 0)
        if (System.currentTimeMillis() - last < minutes * 60_000L) return false
        val pool = userImages(ctx).ifEmpty { PRESETS }
        val i = pool.indexOfFirst { it.key == currentKey(ctx) }
        set(ctx, pool[(i + 1) % pool.size].key)
        return true
    }

    /** Full-screen drawable. Photos are decoded at roughly screen size to keep memory low. */
    fun load(ctx: Context, choice: WallpaperChoice): Drawable? {
        val dm = ctx.resources.displayMetrics
        return if (choice.file != null) {
            decode(choice.file, dm.widthPixels, dm.heightPixels)?.let { BitmapDrawable(ctx.resources, it) }
        } else {
            ctx.getDrawable(choice.res)
        }
    }

    fun thumbnail(ctx: Context, choice: WallpaperChoice, widthPx: Int): Drawable? {
        val heightPx = widthPx * 9 / 16
        if (choice.file != null) {
            return decode(choice.file, widthPx, heightPx)?.let { BitmapDrawable(ctx.resources, it) }
        }
        // Presets: draw at full-screen size, then shrink, so the glows look like the real thing.
        val d = ctx.getDrawable(choice.res) ?: return null
        val dm = ctx.resources.displayMetrics
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(widthPx / dm.widthPixels.toFloat(), heightPx / dm.heightPixels.toFloat())
        d.setBounds(0, 0, dm.widthPixels, dm.heightPixels)
        d.draw(canvas)
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun decode(file: File, reqW: Int, reqH: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null // unreadable or too large: skip it
    }
}
