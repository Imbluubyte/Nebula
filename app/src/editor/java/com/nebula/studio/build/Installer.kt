package com.nebula.studio.build

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import java.io.File

/** Installs an APK through the system installer (asks the user to confirm). */
object Installer {
    fun install(ctx: Context, apk: File, say: (String) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            s.openWrite("game.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val action = "com.nebula.studio.INSTALL_RESULT"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(it) }
                    } else {
                        say(if (status == PackageInstaller.STATUS_SUCCESS) "Installed." else "Install failed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"))
                        try { ctx.unregisterReceiver(this) } catch (e: Exception) { }
                    }
                }
            }
            ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pending.intentSender)
        }
    }
}
