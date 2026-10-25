package com.marcus.onelauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the FTP server in the foreground so it keeps going while you use other apps.
 * It switches itself off after [IDLE_LIMIT_MS] without any client activity.
 */
class FtpService : Service() {

    companion object {
        const val PORT = 2121
        const val USER = "tv"
        private const val IDLE_LIMIT_MS = 60 * 60 * 1000L // 1 hour
        private const val CHANNEL = "ftp"
        private const val PREFS = "ftp"

        @Volatile var running = false
            private set
        @Volatile var lastError: String? = null
            private set
        /** Newest APK uploaded over FTP, waiting for the user to install it. */
        @Volatile var pendingApk: File? = null
        /** Newest photo uploaded to a Wallpapers folder, waiting to be offered as wallpaper. */
        @Volatile var pendingWallpaper: File? = null

        private val main = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }
        internal fun notifyChanged() = main.post { listeners.forEach { it() } }

        fun start(ctx: Context) {
            lastError = null
            ctx.startForegroundService(Intent(ctx, FtpService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FtpService::class.java))
        }

        /** 6-digit password, created once and remembered so saved FileZilla sites keep working. */
        fun password(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            return prefs.getString("pass", null) ?: newPassword(ctx)
        }

        fun newPassword(ctx: Context): String {
            val p = (SecureRandom().nextInt(900_000) + 100_000).toString()
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("pass", p).apply()
            return p
        }

        /** True when "All files access" is granted, so the whole internal storage is shared. */
        fun hasFullAccess(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

        fun rootDir(ctx: Context): File =
            if (hasFullAccess()) Environment.getExternalStorageDirectory()
            else (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }

        /** The TV's LAN address (Ethernet preferred over Wi-Fi). */
        fun ipAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private var server: FtpServer? = null
    @Volatile private var lastActivity = System.currentTimeMillis()
    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) stopSelf()
            else main.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        val s = FtpServer(rootDir(this), PORT, USER, password(this), object : FtpServer.Listener {
            override fun onUpload(file: File) {
                if (file.name.endsWith(".apk", ignoreCase = true)) {
                    pendingApk = file
                    notifyChanged()
                }
                if (Wallpapers.isImage(file) && file.parentFile?.name.equals("Wallpapers", ignoreCase = true)) {
                    pendingWallpaper = file
                    notifyChanged()
                }
            }
            override fun onActivity() {
                lastActivity = System.currentTimeMillis()
            }
        })
        try {
            s.start()
            server = s
            running = true
            lastActivity = System.currentTimeMillis()
            main.postDelayed(idleCheck, 60_000)
        } catch (e: Exception) {
            lastError = "Couldn't start: ${e.message}"
            stopSelf()
        }
        notifyChanged()
    }

    override fun onDestroy() {
        main.removeCallbacks(idleCheck)
        server?.stop()
        server = null
        running = false
        notifyChanged()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FTP server", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ftp)
            .setContentTitle("FTP server on")
            .setContentText("ftp://${ipAddress() ?: "?"}:$PORT")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
