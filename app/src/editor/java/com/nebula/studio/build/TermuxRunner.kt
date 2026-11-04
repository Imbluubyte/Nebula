package com.nebula.studio.build

import android.content.Context
import android.content.Intent
import nebula.core.CommandRunner
import java.io.File

/**
 * Runs commands inside Termux (where snc and clang live) through its RUN_COMMAND service.
 * Termux needs `allow-external-apps=true` in ~/.termux/termux.properties and storage access (termux-setup-storage).
 * The command's output goes to a log file next to the work dir; a "done" file carries the exit code.
 */
class TermuxRunner(private val ctx: Context, private val timeoutSeconds: Int = 300) : CommandRunner {
    companion object {
        const val PREFIX = "/data/data/com.termux/files/usr"
        fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }
        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    override fun run(cmd: List<String>, workDir: File, env: Map<String, String>): Pair<Int, String> {
        if (!installed(ctx)) return 127 to "Termux is not installed. Install it from F-Droid, then install SuperNova (see docs/GUIDE.md)."
        val id = System.nanoTime()
        val log = File(workDir, ".termux_$id.log"); val done = File(workDir, ".termux_$id.done")
        val exports = env.entries.joinToString("") { "export ${it.key}=${q(it.value)}; " }
        val line = "export PATH=\"\$HOME/.cargo/bin:\$PATH\"; $exports cd ${q(workDir.absolutePath)} && ${cmd.joinToString(" ") { q(it) }}"
        val script = "( $line ) > ${q(log.absolutePath)} 2>&1; echo \$? > ${q(done.absolutePath)}"
        val intent = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "$PREFIX/..")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        try { ctx.startForegroundService(intent) }
        catch (e: Exception) { return 126 to "Cannot start Termux: ${e.message}. Enable 'allow-external-apps' in ~/.termux/termux.properties and allow the Run command permission for Nebula." }
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (done.exists()) {
                val code = done.readText().trim().toIntOrNull() ?: 1
                val out = if (log.exists()) log.readText() else ""
                log.delete(); done.delete()
                return code to out
            }
            Thread.sleep(250)
        }
        return 124 to "Timed out after ${timeoutSeconds}s waiting for Termux. Open Termux once, run termux-setup-storage and check allow-external-apps=true."
    }

    /** Build command for a project: compile inside Termux's home (shared storage cannot hold executables) and copy the result back. */
    fun buildCommand(apiDir: File): List<String> = listOf("bash", "-c",
        "mkdir -p \"\$HOME/.nebula\" && snc build game.sn --lib -I ${q(apiDir.absolutePath)} -o \"\$HOME/.nebula/libscripts.so\" && cp \"\$HOME/.nebula/libscripts.so\" libscripts.so")
}
