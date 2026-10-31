package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Prism / MultiMC（以及所有继承这套实例布局的启动器）导出的实例目录。
 *
 * 这类包不是"整合包格式"，而是一整个游戏目录：
 * - `instance.cfg` / `mmc-pack.json` 在实例根目录；
 * - 游戏文件（mods/ config/ ...）在 `minecraft/` 或老版本的 `.minecraft/` 下；
 * - CurseForge 的清单（如果有）在 `flame/manifest.json`，Modrinth 的实例则只有 instance.cfg 里的
 *   ManagedPack 记录，没有索引文件（模组都在包里）。
 *
 * 版本信息来自 `mmc-pack.json` 的 components：`net.minecraft` 给 MC 版本，
 * `net.minecraftforge` / `net.neoforged` / `net.fabricmc.fabric-loader` / `org.quiltmc.quilt-loader`
 * 给 loader 名与版本。因此"只有实例目录、没有整合包清单"的包也能自动装（这是纯 zip 格式做不到的）。
 *
 * 只读 zip，不落盘。
 */
object PrismInstance {

    /** 实例元数据文件名。 */
    const val MMC_PACK = "mmc-pack.json"

    /** 启动器实例的标记文件（有它基本可以确定是实例导出，而不是普通整合包 zip）。 */
    const val INSTANCE_CFG = "instance.cfg"

    /** 游戏目录的两种历史命名：新启动器用 `minecraft/`，MultiMC/老版本用 `.minecraft/`。 */
    val GAME_DIRS = listOf("minecraft/", ".minecraft/")

    private const val MC_UID = "net.minecraft"

    /** mmc 组件 uid → loader 名（与 [LoaderDefaults] 支持的 loader 对齐）。 */
    private val LOADER_UIDS = mapOf(
            "net.minecraftforge" to "forge",
            "net.neoforged" to "neoforge",
            "net.neoforged.neoforge" to "neoforge",
            "net.fabricmc.fabric-loader" to "fabric",
            "org.quiltmc.quilt-loader" to "quilt",
            "com.mumfrey.liteloader" to "liteloader")

    /**
     * 识别到的实例信息。
     *
     * @param prefix 解压时要剥掉的前缀（如 `minecraft/`、`Instance/minecraft/`）
     * @param mmcPack mmc-pack.json 在包内的路径（如 `mmc-pack.json`、`Instance/mmc-pack.json`）
     * @param mcVersion / [loaderName] / [loaderVersion] mmc-pack.json 给出的版本，缺项为 null
     */
    data class Meta(
            val prefix: String,
            val mmcPack: String,
            val mcVersion: String? = null,
            val loaderName: String? = null,
            val loaderVersion: String? = null
    )

    /**
     * 读 zip 里的实例信息；不是启动器实例时返回 null。
     *
     * 先看根目录，再看"整包外面又套了一层目录"的情况（`Instance/minecraft/...` + `Instance/mmc-pack.json`）。
     *
     * @throws IOException zip 打不开时抛出，由调用方给出可读错误
     */
    @Throws(IOException::class)
    fun read(zip: File): Meta? = ZipFile(zip).use { zipFile -> read(zipFile) }

    /** [read] 的 ZipFile 版本（调用方已经打开了 zip 时复用，避免重复打开）。 */
    fun read(zipFile: ZipFile): Meta? {
        val names = zipFile.entries().asSequence().map { it.name }.toList()
        for (root in candidateRoots(names)) {
            val mmcEntry = root + MMC_PACK
            if (zipFile.getEntry(mmcEntry) == null) continue
            val gameDir = gameDirUnder(names, root)
                    ?: if (zipFile.getEntry(root + INSTANCE_CFG) != null) "" else continue
            val meta = parseComponents(zipFile, mmcEntry)
            return Meta(root + gameDir, mmcEntry, meta.mcVersion, meta.loaderName, meta.loaderVersion)
        }
        return null
    }

    /**
     * 游戏目录前缀（`minecraft/` / `.minecraft/`）；包内没有展开的游戏目录时返回 null。
     *
     * @param root 实例根目录（"" 或 `Instance/`）
     */
    fun gameDirUnder(names: List<String>, root: String): String? =
            GAME_DIRS.firstOrNull { dir -> names.any { it.startsWith(root + dir) } }

    /** 根目录 + 所有一层子目录（先根目录，优先取最外层）。 */
    private fun candidateRoots(names: List<String>): List<String> {
        val dirs = names.mapNotNull { name ->
            val slash = name.indexOf('/')
            if (slash > 0) name.substring(0, slash + 1) else null
        }.toSortedSet()
        return listOf("") + dirs
    }

    /** 解析 components：MC 版本 + 第一个已知 loader（多个时 warn，取靠前的）。 */
    private fun parseComponents(zipFile: ZipFile, entry: String): Meta {
        return try {
            zipFile.getInputStream(zipFile.getEntry(entry)).use { input ->
                val json = JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                val components = json.get("components")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: return Meta("", entry)

                var mcVersion: String? = null
                var loaderName: String? = null
                var loaderVersion: String? = null
                for (element in components) {
                    if (!element.isJsonObject) continue
                    val obj = element.asJsonObject
                    val uid = obj.get("uid")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
                    val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
                    if (uid == MC_UID) {
                        mcVersion = version
                        continue
                    }
                    val name = LOADER_UIDS[uid.lowercase()] ?: continue
                    if (loaderName != null) {
                        LOGGER.warn("$entry declares multiple loaders ($loaderName, $name), using the first one")
                        continue
                    }
                    loaderName = name
                    loaderVersion = version
                }
                loaderVersion = normalizeLoaderVersion(mcVersion, loaderVersion)
                Meta("", entry, mcVersion, loaderName, loaderVersion)
            }
        } catch (e: Exception) {
            LOGGER.warn("$entry could not be parsed (${e.message}); ignoring the launcher instance metadata")
            Meta("", entry)
        }
    }

    /**
     * mmc 里的 forge 版本有时带 MC 前缀（`1.12.2-14.23.5.2859`），安装器推导要裸版本号。
     */
    private fun normalizeLoaderVersion(mcVersion: String?, loaderVersion: String?): String? {
        val version = loaderVersion ?: return null
        val mc = mcVersion ?: return version
        return if (version.startsWith("$mc-")) version.removePrefix("$mc-") else version
    }

    /** 版本信息包装成 [ManifestVersions]，供包型在清单缺项时兜底。 */
    fun versions(zip: File): ManifestVersions? = read(zip)?.let {
        if (it.mcVersion == null && it.loaderVersion == null) null
        else ManifestVersions(it.mcVersion, it.loaderVersion, it.loaderName)
    }
}
