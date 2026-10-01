package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * 整合包格式自动识别（`modpackFormat` 留空 / `auto` / `detect` 时使用）。
 *
 * 只看包内的约定文件，不看文件名与扩展名：
 * - 含 `modrinth.index.json` → [FORMAT_MODRINTH]；
 * - 含**带 `minecraft` 对象**的 `manifest.json` → [FORMAT_CURSE]（普通 zip 里也可能有同名文件，
 *   所以额外要求能解析出 minecraft 段）；
 * - 都没有 → [FORMAT_ZIP]（按纯 zip 解压全部内容）。
 *
 * 这样 `modpackFormat` 写错（例如 mrpack 配成 `curse` 走了 CF 分支）这类问题就不必再靠用户填对。
 */
object PackFormatDetector {

    const val FORMAT_MODRINTH = "modrinth"
    const val FORMAT_CURSE = "curse"
    const val FORMAT_ZIP = "zip"

    private const val MODRINTH_MANIFEST = "modrinth.index.json"
    private const val CURSE_MANIFEST = "manifest.json"

    /** 识别不出明确格式时按纯 zip 处理。 */
    @Throws(IOException::class)
    fun detectOrZip(zip: File): String = detect(zip) ?: FORMAT_ZIP

    /**
     * 识别格式；识别不出返回 null。
     *
     * @throws IOException zip 打不开（不是 zip 容器 / 已损坏）时抛出，由调用方给出可读错误
     */
    @Throws(IOException::class)
    fun detect(zip: File): String? {
        ZipFile(zip).use { zipFile ->
            if (zipFile.getEntry(MODRINTH_MANIFEST) != null) return FORMAT_MODRINTH

            val manifest = zipFile.getEntry(CURSE_MANIFEST) ?: return null
            val hasMinecraftSection = try {
                zipFile.getInputStream(manifest).use { input ->
                    JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                            .get("minecraft")?.isJsonObject == true
                }
            } catch (e: Exception) {
                // manifest.json 不是合法 JSON：那它只是普通 zip 里的同名文件
                LOGGER.warn("manifest.json in ${zip.name} could not be parsed, treating the pack as a plain zip: ${e.message}")
                false
            }

            return if (hasMinecraftSection) FORMAT_CURSE else null
        }
    }
}
