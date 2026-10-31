package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * mods/ 目录「客户端模组清理」的完成标记。
 *
 * 清理要在安装末尾跑，也要在「已经装好、直接启动」的服务器上跑一次：否则旧服务器只换主程序时，
 * 客户端模组仍旧留在盘里继续崩。每次启动都重新联网反查太浪费，所以这里记下上次清理时的
 * mods/ 指纹（文件名 + 大小 + 修改时间），只有目录变了才重新扫描。
 *
 * 标记文件写在安装目录下：安装路径 + ".serverstarter-clientmods"。
 */
object ClientModScanState {

    private val HEX = "0123456789abcdef".toCharArray()

    /** 当前 mods/ 的指纹：排序后的 name|size|mtime 列表做 SHA-1。目录不存在 -> "empty"。 */
    fun fingerprint(modsDir: File): String {
        val entries = modsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
                ?.map { it.name + "|" + it.length() + "|" + it.lastModified() }
                ?.sorted()
                ?: return "empty"
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(entries.joinToString("\n").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest.digest()) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    /** 读标记；不存在/空/读失败 -> null。 */
    fun read(marker: File): String? = try {
        if (marker.isFile) marker.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } else null
    } catch (e: IOException) {
        null
    }

    /** 写标记；失败只 warn（下次启动再扫一遍，不影响安装结果）。 */
    fun write(marker: File, value: String) {
        try {
            marker.writeText(value, Charsets.UTF_8)
        } catch (e: IOException) {
            LOGGER.warn("Unable to record the client mod scan marker (" + marker.absolutePath + "): " + e.message)
        }
    }
}
