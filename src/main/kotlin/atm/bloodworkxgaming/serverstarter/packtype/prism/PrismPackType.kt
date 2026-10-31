package atm.bloodworkxgaming.serverstarter.packtype.prism

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.PrismInstance
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import java.io.File
import java.io.IOException
import java.nio.file.PathMatcher

/**
 * Prism / MultiMC 启动器导出的实例（`instance.cfg` + `mmc-pack.json` + `minecraft/`）。
 *
 * 与 curse / modrinth 包的关键区别：**模组已经躺在包里**（实例导出就是整个游戏目录），
 * 没有可下载的清单，所以这里只做两件事：
 * 1. 把 `minecraft/`（老启动器是 `.minecraft/`）剥掉前缀解压到服务器目录；
 * 2. 从 `mmc-pack.json` 读版本（MC + loader），让安装器与启动参数能自动推导。
 *
 * 解压时跳过只对客户端有意义的文件（saves/、logs/、resourcepacks/、options.txt ...）。
 */
class PrismPackType(private val configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String): String = url

    /** 版本来自 `mmc-pack.json`（components 里的 net.minecraft / net.minecraftforge ...）。 */
    @Throws(IOException::class)
    override fun readManifestVersions(zip: File): ManifestVersions? = PrismInstance.versions(zip)

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        // 自动识别时前缀由探测器注入；显式配置 `modpackFormat: prism` 时自己看一眼（可能有 .minecraft/ 或外层目录）
        val prefix = overridesPrefixOverride
                ?: try {
                    PrismInstance.read(file)?.prefix
                } catch (e: IOException) {
                    LOGGER.warn("Could not read the launcher instance layout of ${file.name} (${e.message})")
                    null
                }
                ?: "minecraft/"

        ZipExtractor(
                basePath = basePath,
                oldFiles = oldFiles,
                pathMatchers = pathMatchers,
                manifestEntryName = null,
                manifestTargetName = null,
                overridesPrefix = prefix,
                moveModsFolderFirst = false,
                rethrowOnError = true,
                skipInstanceEntries = true
        ).extract(file)
    }

    override fun postProcessing() {
        LOGGER.info("Launcher instance export: its mods were already inside the archive, nothing to download.")
    }
}
