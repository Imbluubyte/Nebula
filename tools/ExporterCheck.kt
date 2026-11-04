import com.nebula.studio.build.AlignedZip
import com.nebula.studio.build.AxmlPatcher
import java.io.File
import java.util.zip.ZipFile

/** CI self-check: repackages the real debug APK the way ApkExporter does (without signing) so aapt2/zipalign can inspect it. */
fun main(args: Array<String>) {
    val out = File(args[1]).outputStream().buffered()
    val zip = AlignedZip(out)
    ZipFile(File(args[0])).use { z ->
        for (e in z.entries()) {
            if (e.isDirectory || Regex("""META-INF/.*|assets/nebula-debug\.p12""").matches(e.name)) continue
            var data = z.getInputStream(e).use { it.readBytes() }
            if (e.name == "AndroidManifest.xml") data = AxmlPatcher.patch(data, mapOf(
                "com.nebula.studio" to "com.example.testgame",
                "com.nebula.studio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "com.example.testgame.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                "Nebula" to "Test Game Label"), "com.nebula.studio", "com.example.testgame")
            zip.add(e.name, data, store = e.name == "resources.arsc")
        }
    }
    zip.add("assets/project/project.nebula", "title=Test\n".toByteArray(), false)
    zip.add("lib/arm64-v8a/libscripts.so", ByteArray(100), false)
    zip.finish(); out.close()
    println("repackaged ok")
}
