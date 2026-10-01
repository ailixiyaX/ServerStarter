package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.InitException
import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import java.io.File
import java.io.IOException

/**
 * 整合包文件的获取（下载或本地定位），与具体包型无关。
 *
 * 从 `AbstractZipbasedPackType` 抽出来的原因：自动识别格式时必须**先拿到包、再看内容**，
 * 而取包逻辑本身又不能依赖包型（原来 `obtainPack()` 会调用各包型的 `cleanUrl`）。
 *
 * 规则：
 * - `./.zip`：扫描进程 CWD 下最新的 `*.zip` / `*.mrpack`（mrpack 同样是 zip 容器，之前只认 `.zip`）；
 * - `file://xxx`：按本地路径打开；
 * - 其它：按 URL 下载到 `basePath/modpack-download.<后缀>`，[cleanUrl] 由具体包型注入
 *   （curse 会补 `/download`）。若下到的文件不是 zip 容器（例如 CF 项目页返回了 HTML），
 *   会再试一次项目页直链，仍不是 zip 就报出可读错误，而不是等到解压时才炸。
 */
object PackObtainer {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)   // "PK\u0003\u0004"

    private val PACK_EXTENSIONS = listOf(".zip", ".mrpack")

    private val SAFE_SUFFIX = Regex("[A-Za-z0-9]{1,8}")

    /** 目录直接子级里最新的整合包文件（`*.zip` / `*.mrpack`，大小写不敏感）；没有返回 null。 */
    fun newestPackIn(dir: File): File? = dir.listFiles { file ->
        file.isFile && PACK_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }
    }?.maxByOrNull { it.lastModified() }

    /** 是否是 zip 容器（只看魔数，不解析内容）。 */
    fun isZipFile(file: File): Boolean {
        if (!file.isFile || file.length() < ZIP_MAGIC.size) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(ZIP_MAGIC.size)
                input.read(head) == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * CurseForge 项目页地址 → 直链下载地址（`.../files/123` → `.../files/123/download`）。
     * curse 包型的 `cleanUrl` 与自动识别模式共用这一份实现。
     */
    fun cursePageDownloadUrl(url: String): String =
            if (url.contains("curseforge.com") && !url.endsWith("/download")) "$url/download" else url

    /**
     * 定位（或下载）整合包文件。
     *
     * @param cleanUrl 具体包型的 URL 规整（默认原样）；curse 传 [cursePageDownloadUrl]
     */
    @Throws(IOException::class)
    fun obtain(
            modpackUrl: String,
            basePath: String,
            internetManager: InternetManager,
            cleanUrl: (String) -> String = { it }
    ): File {
        return when {
            modpackUrl == "./.zip" -> {
                val cwd = File("").absoluteFile
                val newest = newestPackIn(cwd)
                        ?: throw InitException("未在 ${cwd.absolutePath} 找到整合包 zip / mrpack，请放置 *.zip 或 *.mrpack 后重试")
                LOGGER.info("Using local modpack file: " + newest.absolutePath)
                newest
            }

            modpackUrl.startsWith("file://") -> File(modpackUrl.substring(7))

            else -> download(modpackUrl, basePath, internetManager, cleanUrl)
        }
    }

    @Throws(IOException::class)
    private fun download(
            modpackUrl: String,
            basePath: String,
            internetManager: InternetManager,
            cleanUrl: (String) -> String
    ): File {
        val url = cleanUrl(modpackUrl)
        val file = downloadTo(internetManager, url, basePath)
        if (isZipFile(file)) return file

        // 有些地址（CurseForge 项目页）返回的是 HTML；再试一次项目页直链
        val directUrl = cursePageDownloadUrl(url)
        if (directUrl != url) {
            LOGGER.warn("Downloaded file is not a zip, retrying with the direct download link: $directUrl")
            val retried = downloadTo(internetManager, directUrl, basePath)
            if (isZipFile(retried)) return retried
        }

        throw InitException("下载到的文件不是 zip，请检查 modpackUrl 是否为直链（当前：$url）")
    }

    @Throws(IOException::class)
    private fun downloadTo(internetManager: InternetManager, url: String, basePath: String): File {
        LOGGER.info("Attempting to download modpack Zip.")
        val suffix = url.substringAfterLast('/').substringBefore('?').substringAfterLast('.', "zip")
                .takeIf { SAFE_SUFFIX.matches(it) } ?: "zip"
        val to = File(basePath + "modpack-download.$suffix")

        try {
            internetManager.downloadToFile(url, to, ByteProgressReporter())
            LOGGER.info("Downloaded the modpack file to " + to.absolutePath)
            return to
        } catch (e: IOException) {
            LOGGER.error("Pack could not be downloaded")
            throw e
        }
    }
}
