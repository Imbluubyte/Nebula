package com.nebula.studio.build

import android.content.Context
import com.android.apksig.ApkSigner
import nebula.core.Project
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class ExportOptions(val packageId: String, val label: String, val outFile: File, val keystore: File? = null,
                    val storePass: String = "nebula", val alias: String = "nebula", val keyPass: String = "nebula")

/**
 * Exports a game as an installable APK: Nebula's own APK is the player. We copy it, add the project files
 * (assets/project/) and libscripts.so, give it the game's package id and name, align it and sign it.
 */
object ApkExporter {
    private const val BASE_PACKAGE = "com.nebula.studio"
    private val skip = Regex("""META-INF/.*|assets/nebula-debug\.p12""")

    fun export(ctx: Context, project: Project, opt: ExportOptions, scriptsSo: File?, metaText: String): File {
        require(Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+""").matches(opt.packageId)) { "package id must look like com.example.mygame" }
        val base = File(ctx.applicationInfo.sourceDir)
        val unsigned = File(ctx.cacheDir, "export-unsigned.apk")
        FileOutputStream(unsigned).use { fos ->
            val zip = AlignedZip(fos.buffered())
            ZipFile(base).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || skip.matches(e.name)) continue
                    var data = z.getInputStream(e).use { it.readBytes() }
                    if (e.name == "AndroidManifest.xml") {
                        data = AxmlPatcher.patch(data, mapOf(
                            BASE_PACKAGE to opt.packageId,
                            "$BASE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "${opt.packageId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                            "Nebula" to opt.label), BASE_PACKAGE, opt.packageId)
                    }
                    zip.add(e.name, data, store = e.name == "resources.arsc")
                }
            }
            // project files the player reads at run time
            zip.add("assets/project/project.nebula", project.configFile.readBytes(), false)
            for (rel in project.scenes() + project.assets()) zip.add("assets/project/$rel", project.file(rel).readBytes(), false)
            zip.add("assets/project/scripts.meta", metaText.toByteArray(), false)
            if (scriptsSo != null) zip.add("lib/arm64-v8a/libscripts.so", scriptsSo.readBytes(), false)
            zip.finish()
        }

        val (key, cert) = loadKey(ctx, opt)
        opt.outFile.parentFile?.mkdirs(); opt.outFile.delete()
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("nebula", key, listOf(cert)).build()))
            .setInputApk(unsigned).setOutputApk(opt.outFile).setMinSdkVersion(30)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
        unsigned.delete()
        return opt.outFile
    }

    private fun loadKey(ctx: Context, opt: ExportOptions): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        val stream = if (opt.keystore != null) opt.keystore.inputStream() else ctx.assets.open("nebula-debug.p12")
        stream.use { ks.load(it, opt.storePass.toCharArray()) }
        val key = ks.getKey(opt.alias, opt.keyPass.toCharArray()) as PrivateKey
        return key to (ks.getCertificate(opt.alias) as X509Certificate)
    }
}
