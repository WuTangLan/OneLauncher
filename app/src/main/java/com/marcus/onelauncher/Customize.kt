package com.marcus.onelauncher

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.concurrent.thread

/** The Customize menu: wallpaper, rotation, weather location and units, TV settings. */
class Customize(private val activity: MainActivity) {

    private val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun show() {
        val ctx = activity
        val rotation = Wallpapers.ROTATION.firstOrNull { it.first == Wallpapers.rotationMinutes(ctx) }?.second ?: "Off"
        val weatherOn = WeatherRepository.enabled(ctx)
        val place = if (WeatherRepository.manualPlace(ctx)) WeatherRepository.placeName(ctx) ?: "City"
        else "Automatic" + (WeatherRepository.placeName(ctx)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        val unit = if (WeatherRepository.fahrenheit(ctx)) "°F" else "°C"

        val actions = listOf<Pair<String, () -> Unit>>(
            "Wallpaper  ·  ${Wallpapers.current(ctx).label}" to { WallpaperPicker(activity).show() },
            "Change wallpaper automatically  ·  $rotation" to { chooseRotation() },
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
            .setTitle("Customize")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
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
