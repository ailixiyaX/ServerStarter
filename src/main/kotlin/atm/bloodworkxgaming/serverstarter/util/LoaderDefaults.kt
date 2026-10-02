package atm.bloodworkxgaming.serverstarter.util

/**
 * loader 相关默认值的推导：当 `installerUrl` / `installerArguments` 留空时，按整合包 manifest 给出的
 * loader 名与版本推导出安装器地址与参数。
 *
 * 这两项原来必须手填，而且随 loader 变化：Forge 的 maven 路径带 MC 版本、NeoForge 不带、Fabric 走
 * meta 接口、Forge 1.13 之前还是另一台 maven 主机。抄错往往要等安装阶段才暴露。
 */
object LoaderDefaults {

    /** loader 身份：名字（`forge` / `neoforge` / `fabric`）+ 裸版本号。 */
    data class LoaderId(val name: String, val version: String)

    /** 支持自动推导的 loader（quilt 的安装路径与 fabric 不同，暂不推导）。 */
    private val KNOWN_LOADERS = listOf("forge", "neoforge", "fabric")

    /** 归一化 loader 名：`Fabric-Loader` → `fabric`；未支持的名字返回 null。 */
    fun normalizeName(loaderName: String?): String? {
        val normalized = loaderName?.trim()?.lowercase()?.substringBefore('-').orEmpty()
        return if (normalized in KNOWN_LOADERS) normalized else null
    }

    /**
     * 组合 loader 身份。
     *
     * [loaderName] 一般来自 manifest，[loaderVersion] 是裸版本号；名字缺失时会尝试把版本串当作
     * `neoforge-21.1.249` 这样的完整串来拆（yaml 里常这么写）。任一无法确定则返回 null。
     */
    fun resolveLoader(loaderName: String?, loaderVersion: String?): LoaderId? {
        val raw = loaderVersion?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("NONE", ignoreCase = true)) return null

        val parts = raw.split('-').filter { it.isNotBlank() }
        val name = normalizeName(loaderName) ?: run {
            if (parts.size < 2) return null
            normalizeName(parts.first()) ?: return null
        }

        return LoaderId(name, parts.last())
    }

    /**
     * 安装器地址，与配置里的 `installerUrl` 同格式；不认识 loader 时返回 null（由调用方提示手填）。
     */
    fun installerUrl(loader: LoaderId, mcVersion: String): String? = when (loader.name) {
        "neoforge" ->
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/${loader.version}" +
                    "/neoforge-${loader.version}-installer.jar"

        "forge" -> {
            val full = "$mcVersion-${loader.version}"
            val base = if (isLegacyForge(mcVersion)) "https://files.minecraftforge.net/maven"
            else "https://maven.minecraftforge.net"
            "$base/net/minecraftforge/forge/$full/forge-$full-installer.jar"
        }

        // Fabric 的这一项是 meta 接口前缀，installFabric 会再拼 /{installerVersion}/server/jar
        "fabric" -> "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/${loader.version}"

        else -> null
    }

    /** 传给安装器的参数：Forge / NeoForge 需要 `--installServer`，Fabric 直接下可执行 jar。 */
    fun installerArguments(loader: LoaderId): List<String> = when (loader.name) {
        "forge", "neoforge" -> listOf("--installServer")
        else -> emptyList()
    }

    /** Forge 1.13 之前用旧 maven 主机（与示例配置保持一致）。 */
    private fun isLegacyForge(mcVersion: String): Boolean {
        val minor = mcVersion.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return minor < 13
    }
}
