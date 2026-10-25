package com.marcus.onelauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Installs an APK received over FTP using the system installer (shows the normal confirm screen). */
object ApkInstaller {

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for One Launcher so the user can allow it once. */
    fun permissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(ctx: Context, apk: File) {
        val app = ctx.applicationContext
        thread {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("base.apk", 0, apk.length()).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    }
                    val callback = PendingIntent.getBroadcast(
                        app, id,
                        Intent(app, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                toast(app, "Install failed: ${e.message}")
            }
        }
    }

    internal fun toast(ctx: Context, msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.toast(context, "App installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                ApkInstaller.toast(context, "Install failed: $msg")
            }
        }
    }
}
