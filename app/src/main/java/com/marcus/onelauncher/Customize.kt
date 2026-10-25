package com.marcus.onelauncher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.concurrent.thread

/** Which parts of the home screen are shown. Stored in the `launcher` prefs. */
object LauncherSettings {

    private const val PREFS = "launcher"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun showAllApps(ctx: Context) = prefs(ctx).getBoolean("show_all_apps", true)
    fun setShowAllApps(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("show_all_apps", on).apply()

    fun showNowPlaying(ctx: Context) = prefs(ctx).getBoolean("show_now_playing", true)
    fun setShowNowPlaying(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("show_now_playing", on).apply()

    fun showGoogleHome(ctx: Context) = prefs(ctx).getBoolean("show_google_home", true)
    fun setShowGoogleHome(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("show_google_home", on).apply()
}

/** The Launcher options menu: wallpaper, rows, weather location and units, TV settings. */
class Customize(private val activity: MainActivity) {

    private val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun show() {
        val ctx = activity
        val rotation = Wallpapers.ROTATION.firstOrNull { it.first == Wallpapers.rotationMinutes(ctx) }?.second ?: "Off"
        val weatherOn = WeatherRepository.enabled(ctx)
        val place = if (WeatherRepository.manualPlace(ctx)) WeatherRepository.placeName(ctx) ?: "City"
        else "Automatic" + (WeatherRepository.placeName(ctx)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        val unit = if (WeatherRepository.fahrenheit(ctx)) "°F" else "°C"
        val allApps = LauncherSettings.showAllApps(ctx)
        val home = LauncherSettings.showGoogleHome(ctx)
        val playing = LauncherSettings.showNowPlaying(ctx)
        val playingState = when {
            !playing -> "Off"
            !NowPlaying.hasAccess(ctx) -> "Needs permission"
            else -> "On"
        }

        val actions = listOf<Pair<String, () -> Unit>>(
            "Wallpaper  ·  ${Wallpapers.current(ctx).label}" to { WallpaperPicker(activity).show() },
            "Get more wallpapers" to { WallpaperGallery(activity).show() },
            "Change wallpaper automatically  ·  $rotation" to { chooseRotation() },
            "Show All Apps  ·  ${if (allApps) "On" else "Off"}" to {
                LauncherSettings.setShowAllApps(ctx, !allApps)
                activity.loadRows()
                show()
            },
            "Show Google Home  ·  ${if (home) "On" else "Off"}" to {
                LauncherSettings.setShowGoogleHome(ctx, !home)
                activity.loadRows()
                show()
            },
            "Show what's playing  ·  $playingState" to {
                if (playing && !NowPlaying.hasAccess(ctx)) askMediaAccess()
                else {
                    LauncherSettings.setShowNowPlaying(ctx, !playing)
                    activity.onNowPlayingSettingChanged()
                    show()
                }
            },
            "Edit favourites" to { editFavourites() },
            "Rename inputs" to { renameInputs() },
            "Show weather  ·  ${if (weatherOn) "On" else "Off"}" to {
                WeatherRepository.setEnabled(ctx, !weatherOn)
                activity.onWeatherSettingsChanged()
                show()
            },
            "Weather location  ·  $place" to { chooseLocation() },
            "Temperature  ·  $unit" to {
                WeatherRepository.setFahrenheit(ctx, !WeatherRepository.fahrenheit(ctx))
                activity.onWeatherSettingsChanged()
                show()
            },
            "TV settings" to {
                activity.openIntent(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        AlertDialog.Builder(ctx, theme)
            .setTitle("Launcher options")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /**
     * Reading other apps' media needs notification access. Google TV often hides that screen,
     * so fall back to the one-line adb command.
     */
    private fun askMediaAccess() {
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            AlertDialog.Builder(activity, theme)
                .setTitle("Allow One Launcher to see what's playing")
                .setMessage("This TV has no setting for it. From a computer, run:\n\n" +
                    "adb shell cmd notification allow_listener com.marcus.onelauncher/.MediaListener")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Checklist of every app; ticked ones are in the Favourites row. */
    fun editFavourites() {
        thread {
            val apps = AppRepository.appLabels(activity)
            val current = Favourites.list(activity).toSet()
            activity.runOnUiThread {
                val checked = BooleanArray(apps.size) { apps[it].first in current }
                AlertDialog.Builder(activity, theme)
                    .setTitle("Favourites")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("Save") { _, _ ->
                        // Keep favourites that aren't installed (e.g. a default app you haven't got yet).
                        val missing = current.filter { pkg -> apps.none { it.first == pkg } }
                        Favourites.setAll(activity, apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet() + missing)
                        activity.loadRows()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** Pick an input, then name it. */
    private fun renameInputs() {
        thread {
            val inputs = AppRepository.inputs(activity).filter { it.inputId != null }
            activity.runOnUiThread {
                if (inputs.isEmpty()) {
                    activity.toast("This TV didn't report any HDMI inputs")
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity, theme)
                    .setTitle("Rename inputs")
                    .setItems(inputs.map { t ->
                        if (t.label != t.defaultLabel) "${t.label}   (${t.defaultLabel})" else t.label
                    }.toTypedArray()) { _, i ->
                        activity.renameInput(inputs[i], index = i, afterwards = { renameInputs() })
                    }
                    .setNegativeButton("Done", null)
                    .show()
            }
        }
    }

    private fun chooseRotation() {
        val labels = Wallpapers.ROTATION.map { it.second }.toTypedArray()
        val current = Wallpapers.ROTATION.indexOfFirst { it.first == Wallpapers.rotationMinutes(activity) }.coerceAtLeast(0)
        AlertDialog.Builder(activity, theme)
            .setTitle("Change wallpaper automatically")
            .setSingleChoiceItems(labels, current) { d, which ->
                Wallpapers.setRotationMinutes(activity, Wallpapers.ROTATION[which].first)
                d.dismiss()
                show()
            }
            .setOnCancelListener { show() }
            .show()
    }

    private fun chooseLocation() {
        AlertDialog.Builder(activity, theme)
            .setTitle("Weather location")
            .setItems(arrayOf("Automatic (from your internet connection)", "Choose a city…")) { _, which ->
                if (which == 0) {
                    WeatherRepository.setPlace(activity, null)
                    activity.onWeatherSettingsChanged()
                    show()
                } else {
                    askCity()
                }
            }
            .setOnCancelListener { show() }
            .show()
    }

    private fun askCity() {
        val input = EditText(activity).apply {
            hint = "City name, e.g. Sarajevo"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(activity).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity, theme)
            .setTitle("Choose a city")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> search(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> show() }
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                search(input.text.toString())
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun search(query: String) {
        if (query.isBlank()) return show()
        activity.toast("Searching…")
        thread {
            val results = try { WeatherRepository.searchCity(query) } catch (_: Exception) { null }
            activity.runOnUiThread {
                when {
                    results == null -> { activity.toast("No connection, try again later"); show() }
                    results.isEmpty() -> { activity.toast("No city called \"$query\""); askCity() }
                    else -> AlertDialog.Builder(activity, theme)
                        .setTitle("Pick your city")
                        .setItems(results.map { if (it.detail.isBlank()) it.name else "${it.name}, ${it.detail}" }
                            .toTypedArray()) { _, i ->
                            WeatherRepository.setPlace(activity, results[i])
                            activity.onWeatherSettingsChanged()
                            show()
                        }
                        .setOnCancelListener { show() }
                        .show()
                }
            }
        }
    }
}
