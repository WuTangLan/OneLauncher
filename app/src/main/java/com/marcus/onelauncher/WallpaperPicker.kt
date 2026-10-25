package com.marcus.onelauncher

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Bottom sheet with wallpaper thumbnails. Moving focus previews a wallpaper behind the sheet;
 * OK keeps it, Back restores the previous one.
 */
class WallpaperPicker(private val activity: MainActivity) {

    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var committed = false
    private val original = Wallpapers.currentKey(activity)
    private var pendingPreview: Runnable? = null

    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val view = LayoutInflater.from(activity).inflate(R.layout.wallpaper_picker, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val folder = if (FtpService.hasFullAccess()) "Wallpapers folder (or on a USB stick)" else "Wallpapers folder"
        view.findViewById<TextView>(R.id.picker_hint).text =
            "Add your own photos: send them to the $folder with File Transfer"

        val row = view.findViewById<LinearLayout>(R.id.picker_row)
        val choices = Wallpapers.all(activity)
        val thumbW = (176 * activity.resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(activity)
        var toFocus: View? = null

        for (choice in choices) {
            val item = inflater.inflate(R.layout.wallpaper_item, row, false)
            val card = item.findViewById<View>(R.id.wp_card)
            val image = item.findViewById<ImageView>(R.id.wp_image)
            val label = item.findViewById<TextView>(R.id.wp_label)
            card.clipToOutline = true
            label.text = choice.label
            if (choice.key == original) {
                item.findViewById<View>(R.id.wp_check).visibility = View.VISIBLE
                toFocus = item
            }
            // Photos decode in the background; presets are instant.
            if (choice.isPhoto) {
                loader.execute {
                    val d = Wallpapers.thumbnail(activity, choice, thumbW)
                    ui.post { image.setImageDrawable(d) }
                }
            } else {
                image.setImageDrawable(Wallpapers.thumbnail(activity, choice, thumbW))
            }

            item.setOnFocusChangeListener { _, has ->
                val s = if (has) 1.1f else 1f
                card.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
                card.elevation = if (has) 16f else 0f
                label.setTextColor(if (has) Color.WHITE else 0xB3FFFFFF.toInt())
                if (has) schedulePreview(choice)
            }
            item.setOnClickListener {
                committed = true
                Wallpapers.set(activity, choice.key)
                dialog.dismiss()
            }
            row.addView(item)
        }

        activity.pickerOpen = true
        dialog.setOnDismissListener {
            activity.pickerOpen = false
            pendingPreview?.let(ui::removeCallbacks)
            loader.shutdown()
            // Back without choosing: put the old wallpaper back.
            activity.applyWallpaper(animate = true, key = if (committed) null else original)
        }
        dialog.show()
        (toFocus ?: row.getChildAt(0))?.requestFocus()
    }

    /** Waits a moment so scrolling quickly past photos doesn't decode every one. */
    private fun schedulePreview(choice: WallpaperChoice) {
        pendingPreview?.let(ui::removeCallbacks)
        pendingPreview = Runnable { activity.applyWallpaper(animate = true, key = choice.key) }
            .also { ui.postDelayed(it, if (choice.isPhoto) 300L else 120L) }
    }
}
