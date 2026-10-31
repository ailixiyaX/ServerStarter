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
 * - 含 `mmc-pack.json` + 已展开的游戏目录（`minecraft/` / `.minecraft/`）→ [FORMAT_PRISM]
 *   （Prism / MultiMC 等启动器导出的实例，见 [PrismInstance]）；
 * - 都没有 → [FORMAT_ZIP]（按纯 zip 解压全部内容）。
 *
 * 清单不一定在包根目录：整合包被重新打包时常常多套一层目录（`pack/manifest.json`），
 * 或者直接就是启动器导出的实例目录（`flame/manifest.json` + 已展开的 `minecraft/`）。
 * 这两种情况也会被识别，并通过 [Inspection.manifestEntry] / [Inspection.overridesPrefix]
 * 告诉调用方实际的清单路径与需要剥离的解压前缀。
 *
 * 启动器实例里 `mmc-pack.json` 还带版本信息（MC + loader），识别时一并读出放到
 * [Inspection.mcVersion] / [Inspection.loaderName] / [Inspection.loaderVersion]：
 * 实例导出里的 CurseForge 清单可能没写 modLoaders，没有它就只能让用户手填。
 */
object PackFormatDetector {

    const val FORMAT_MODRINTH = "modrinth"
    const val FORMAT_CURSE = "curse"
    const val FORMAT_PRISM = "prism"
    const val FORMAT_ZIP = "zip"

    private const val MODRINTH_MANIFEST = "modrinth.index.json"
    private const val CURSE_MANIFEST = "manifest.json"
    private const val DEFAULT_OVERRIDES = "overrides/"
    private const val EXPORT_GAME_DIR = "minecraft/"

    /**
     * 识别结果。
     *
     * @param manifestEntry 清单在包内的实际路径（如 `manifest.json`、`flame/manifest.json`）；
     *   Prism 实例没有整合包清单时为 `mmc-pack.json`
     * @param overridesPrefix 解压时要剥掉的前缀；根目录包为 `overrides/`，实例导出为 `minecraft/`
     * @param nestedManifest 清单是否位于子目录（即重新打包时多套了一层）
     * @param instanceExport 是否是启动器导出的实例目录（清单在子目录 + 已展开的 `minecraft/`）
     * @param mcVersion / [loaderName] / [loaderVersion] 从 `mmc-pack.json` 读到的版本；没有则为 null
     */
    data class Inspection(
            val format: String,
            val manifestEntry: String? = null,
            val overridesPrefix: String? = null,
            val nestedManifest: Boolean = false,
            val instanceExport: Boolean = false,
            val mcVersion: String? = null,
            val loaderName: String? = null,
            val loaderVersion: String? = null
    ) {
        /** `mmc-pack.json` 是否给出了可用版版本（清单缺项时可作兜底）。 */
        val declaresVersions: Boolean
            get() = mcVersion != null || loaderVersion != null
    }

    /** 识别不出明确格式时按纯 zip 处理。 */
    @Throws(IOException::class)
    fun detectOrZip(zip: File): String = inspect(zip).format

    /**
     * 识别格式；识别不出返回 null。
     *
     * @throws IOException zip 打不开（不是 zip 容器 / 已损坏）时抛出，由调用方给出可读错误
     */
    @Throws(IOException::class)
    fun detect(zip: File): String? = inspect(zip).format.takeIf { it != FORMAT_ZIP }

    /**
     * 完整识别：格式 + 清单路径 + overrides 前缀（自动识别模式下由 `ServerStarter` 注入包型）。
     */
    @Throws(IOException::class)
    fun inspect(zip: File): Inspection {
        ZipFile(zip).use { zipFile ->
            val names = zipFile.entries().asSequence().map { it.name }.toList()

            fun has(entry: String) = zipFile.getEntry(entry) != null
            fun curseManifest(entry: String) = has(entry) && hasMinecraftSection(zipFile, entry, zip.name)

            // ① 清单在包根目录（标准整合包）
            if (has(MODRINTH_MANIFEST)) {
                return Inspection(FORMAT_MODRINTH, MODRINTH_MANIFEST, DEFAULT_OVERRIDES)
            }
            if (curseManifest(CURSE_MANIFEST)) {
                return Inspection(FORMAT_CURSE, CURSE_MANIFEST, DEFAULT_OVERRIDES)
            }

            // ② 清单在一层子目录里
            val topLevelDirs = names.mapNotNull { name ->
                val slash = name.indexOf('/')
                if (slash > 0) name.substring(0, slash) else null
            }.toSortedSet()

            for (dir in topLevelDirs) {
                val entry = "$dir/$MODRINTH_MANIFEST"
                if (!has(entry)) continue
                val prefix = when {
                    names.any { it.startsWith("$dir/$DEFAULT_OVERRIDES") } -> "$dir/$DEFAULT_OVERRIDES"
                    else -> instancePrefix(names, "$dir/") ?: "$dir/"
                }
                return Inspection(FORMAT_MODRINTH, entry, prefix,
                        nestedManifest = true,
                        instanceExport = prefix != "$dir/")
            }

            for (dir in topLevelDirs) {
                val entry = "$dir/$CURSE_MANIFEST"
                if (!curseManifest(entry)) continue

                if (names.any { it.startsWith("$dir/$DEFAULT_OVERRIDES") }) {
                    return Inspection(FORMAT_CURSE, entry, "$dir/$DEFAULT_OVERRIDES", nestedManifest = true)
                }

                // 启动器实例导出：清单在子目录（常是 flame/），游戏目录已经展开为 minecraft/
                val gameDir = instancePrefix(names, "$dir/")
                if (gameDir != null) {
                    return Inspection(FORMAT_CURSE, entry, gameDir, nestedManifest = true, instanceExport = true)
                }

                return Inspection(FORMAT_CURSE, entry, "$dir/", nestedManifest = true)
            }

            // ③ 启动器实例导出，且没有整合包清单（只有 mmc-pack.json + 游戏目录）
            PrismInstance.read(zipFile)?.let { meta ->
                return Inspection(FORMAT_PRISM, meta.mmcPack, meta.prefix, instanceExport = true,
                        mcVersion = meta.mcVersion, loaderName = meta.loaderName, loaderVersion = meta.loaderVersion)
            }

            return Inspection(FORMAT_ZIP)
        }
    }

    /**
     * 实例导出的游戏目录前缀（`minecraft/` / `.minecraft/`，可能带一层 `Instance/`）。
     *
     * 先看清单所在目录下，再看包根目录——Prism 把清单放在 `flame/`，但游戏目录在根上的 `minecraft/`。
     *
     * @return 完整前缀（如 `minecraft/`、`Instance/minecraft/`）；不是实例导出时返回 null
     */
    private fun instancePrefix(names: List<String>, dirPrefix: String): String? {
        PrismInstance.gameDirUnder(names, dirPrefix)?.let { return dirPrefix + it }
        // 清单被塞进子目录、游戏目录却留在根上（Prism 的 flame/manifest.json 就是这样）。
        // 根上有 mmc-pack.json / instance.cfg 时这是启动器实例的确定证据；没有时也照样认，
        // 因为 2.5.9 之前就是这样识别的，收紧会让旧的实例导出包退回 "包内多一层 minecraft/"。
        if (dirPrefix.isNotEmpty()) {
            PrismInstance.gameDirUnder(names, "")?.let { return it }
        }
        return null
    }

    /** manifest.json 是否带 `minecraft` 对象（普通 zip 里的同名文件不算整合包清单）。 */
    private fun hasMinecraftSection(zipFile: ZipFile, entry: String, zipName: String): Boolean {
        return try {
            zipFile.getInputStream(zipFile.getEntry(entry)).use { input ->
                JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                        .get("minecraft")?.isJsonObject == true
            }
        } catch (e: Exception) {
            LOGGER.warn("$entry in $zipName could not be parsed, treating the pack as a plain zip: ${e.message}")
            false
        }
    }
}
