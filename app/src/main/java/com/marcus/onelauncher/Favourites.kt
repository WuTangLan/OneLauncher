package com.marcus.onelauncher

import android.content.Context

/**
 * The user's Favourites row: an ordered list of package names. Until the user changes it,
 * it holds the built-in defaults from [FAVOURITES].
 */
object Favourites {

    private const val PREFS = "launcher"
    private const val KEY = "favourites"

    fun list(ctx: Context): List<String> {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (saved != null) return saved.split('\n').filter { it.isNotBlank() }
        val pm = ctx.packageManager
        // Defaults: whichever build of each default app is installed, else its main package.
        return FAVOURITES.map { fav ->
            fav.packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null || pm.getLeanbackLaunchIntentForPackage(it) != null }
                ?: fav.packages.first()
        }
    }

    private fun save(ctx: Context, list: List<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, list.distinct().joinToString("\n")).apply()

    fun contains(ctx: Context, pkg: String) = pkg in list(ctx)

    fun add(ctx: Context, pkg: String) {
        if (!contains(ctx, pkg)) save(ctx, list(ctx) + pkg)
    }

    fun remove(ctx: Context, pkg: String) = save(ctx, list(ctx) - pkg)

    /** Moves [pkg] by [delta] places. Returns its new index, or -1 if it isn't a favourite. */
    fun move(ctx: Context, pkg: String, delta: Int): Int {
        val l = list(ctx).toMutableList()
        val i = l.indexOf(pkg)
        if (i < 0) return -1
        val j = (i + delta).coerceIn(0, l.size - 1)
        l.removeAt(i)
        l.add(j, pkg)
        save(ctx, l)
        return j
    }

    /** Replaces the whole set, keeping the existing order for apps that stay and appending new ones. */
    fun setAll(ctx: Context, packages: Set<String>) {
        val kept = list(ctx).filter { it in packages }
        save(ctx, kept + packages.filter { it !in kept }.sorted())
    }

    /** Display name for a default favourite that isn't installed yet. */
    fun defaultLabel(pkg: String): String? = FAVOURITES.firstOrNull { pkg in it.packages }?.label

    /** Other package names the same default app has used (e.g. SmartTube stable vs beta). */
    fun alternates(pkg: String): List<String> = FAVOURITES.firstOrNull { pkg in it.packages }?.packages ?: listOf(pkg)
}

/**
 * Your own names for HDMI inputs ("PC", "STB", "Xbox 360"…), stored per input,
 * plus an icon chosen from the name.
 */
object InputLabels {

    private const val PREFS = "input_labels"

    /** Quick choices offered in the rename menu. */
    val SUGGESTIONS = listOf(
        "PC", "STB", "Xbox 360", "Xbox Series X", "PlayStation 5", "PlayStation 4", "Wii", "Nintendo Switch",
        "Apple TV", "Chromecast", "Fire TV", "Blu-ray", "Soundbar", "Laptop", "Camera",
    )

    fun get(ctx: Context, inputId: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(inputId, null)

    fun set(ctx: Context, inputId: String, label: String?) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (label.isNullOrBlank()) e.remove(inputId) else e.putString(inputId, label.trim().take(24))
        e.apply()
    }

    private val CONSOLE = listOf("xbox", "playstation", "ps2", "ps3", "ps4", "ps5", "wii", "switch", "nintendo",
        "sega", "console", "game", "gaming", "steam", "retro")
    private val COMPUTER = listOf("pc", "computer", "laptop", "mac", "macbook", "desktop", "notebook", "imac")
    private val BOX = listOf("stb", "box", "cable", "sat", "satellite", "decoder", "receiver", "tuner", "iptv",
        "apple", "chromecast", "fire", "roku", "shield", "stick", "blu", "bluray", "dvd", "player", "soundbar")

    /** Picks an icon that matches the name; plain HDMI icon otherwise. */
    fun iconFor(label: String): Int {
        val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        fun matches(keys: List<String>) = words.any { w -> keys.any { k -> w == k || (k.length >= 4 && w.startsWith(k)) } }
        return when {
            matches(CONSOLE) -> R.drawable.ic_gamepad
            matches(COMPUTER) -> R.drawable.ic_pc
            matches(BOX) -> R.drawable.ic_settopbox
            else -> R.drawable.ic_hdmi
        }
    }
}
