package com.marcus.onelauncher

import android.app.ActivityManager
import android.content.Context

data class RamInfo(val totalBytes: Long, val availBytes: Long, val lowMemory: Boolean) {
    val usedBytes get() = totalBytes - availBytes
    val usedPercent get() = if (totalBytes == 0L) 0 else (usedBytes * 100 / totalBytes).toInt()
}

object RamCleaner {

    fun read(context: Context): RamInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamInfo(mi.totalMem, mi.availMem, mi.lowMemory)
    }

    /**
     * Stops background and cached processes of every installed app (except this launcher).
     * Android only lets a normal app kill *background* processes: anything playing, in the
     * foreground or a protected system service is left alone, which is what you want.
     * Must run off the main thread. Returns bytes freed (approximate).
     */
    fun clean(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = read(context).availBytes

        val packages = context.packageManager.getInstalledApplications(0)
        for (app in packages) {
            if (app.packageName == context.packageName) continue
            try {
                am.killBackgroundProcesses(app.packageName)
            } catch (_: Exception) {
                // Some system packages refuse; skip them.
            }
        }

        Runtime.getRuntime().gc()
        Thread.sleep(1500) // give the system a moment to reclaim memory
        val after = read(context).availBytes
        return (after - before).coerceAtLeast(0)
    }

    fun format(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
}
