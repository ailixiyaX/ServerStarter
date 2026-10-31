package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.net.URLEncoder

/**
 * 安装末尾对 mods/ 里「没有平台判定」的 jar 补做 Modrinth 哈希反查。
 *
 * 背景：整合包 overrides（实例导出里的 `minecraft/mods/`、`flame/overrides/mods/` 等）直接带着 jar，
 * 这些文件不在 CF manifest 的 `files[]` 里 → 没有 CF 三态判定；而不少作者的 `mods.toml` 把 side 写成
 * BOTH、实际只在客户端可用（MaFgLib、Just Enough Characters 都是这样）。TOML 规则无法识别，服务器
 * 启动时就会崩在 `RuntimeDistCleaner: Attempted to load class net/minecraft/client/... for invalid dist
 * DEDICATED_SERVER`。
 *
 * 判定口径：Modrinth project 的 `server_side == "unsupported"` → [ApiVerdict.CLIENT_ONLY]；
 * 其余（required / optional / unknown / 缺失）不判定（保留）。二进制身份用 sha1 反查，与文件名无关。
 *
 * 网络/解析失败一律不判定（fail-safe：宁可漏删，也不因网络问题误删模组）。
 */
object ModrinthClientModScan {

    const val VERSION_FILES_URL = "https://api.modrinth.com/v2/version_files"
    private const val PROJECTS_URL = "https://api.modrinth.com/v2/projects?ids="
    /** `GET /v2/projects?ids=` 单次最多查询的项目数（保守分块，避免 URL 过长）。 */
    private const val PROJECT_CHUNK = 50

    /** `POST /v2/version_files` 请求体。 */
    fun versionFilesBody(hashes: Collection<String>): String {
        val array = JsonArray()
        hashes.forEach { array.add(it) }
        val body = com.google.gson.JsonObject()
        body.add("hashes", array)
        body.addProperty("algorithm", "sha1")
        return body.toString()
    }

    /** `GET /v2/projects?ids=` 的 URL（ids 为 URL 编码后的 JSON 数组）。 */
    fun projectsUrl(projectIds: Collection<String>): String {
        val array = JsonArray()
        projectIds.forEach { array.add(it) }
        return PROJECTS_URL + URLEncoder.encode(array.toString(), "UTF-8")
    }

    /** `POST /v2/version_files` 响应 → sha1(→小写) 到 project_id。结构异常项跳过。 */
    fun parseVersionFiles(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val obj = JsonParser.parseString(json)
        if (!obj.isJsonObject) return result
        for ((hash, value) in obj.asJsonObject.entrySet()) {
            if (!value.isJsonObject) continue
            val projectId = value.asJsonObject.get("project_id")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
            result[hash.lowercase()] = projectId
        }
        return result
    }

    /** `GET /v2/projects` 响应 → project_id 到 server_side（缺失/非字符串 → null）。 */
    fun parseProjectServerSides(json: String): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return result
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val serverSide = obj.get("server_side")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
            result[id] = serverSide
        }
        return result
    }

    /**
     * 一次反查的结果。
     *
     * @param verdicts 文件名 → [ApiVerdict.CLIENT_ONLY]（只含判定为客户端专用的）
     * @param complete 是否所有请求都成功（有网络/解析失败时为 false，调用方不应记「已扫描」标记）
     */
    data class ScanResult(val verdicts: Map<String, ApiVerdict>, val complete: Boolean)

    /** server_side 为 `unsupported` → 客户端专用。 */
    fun isClientOnly(serverSide: String?): Boolean = serverSide != null && serverSide.equals("unsupported", ignoreCase = true)

    /**
     * 扫描目录里的 jar，返回「平台判定为客户端专用」的文件名 → [ApiVerdict.CLIENT_ONLY]。
     *
     * @param known 已有平台判定（CF 三态）的文件名，不再重复查询
     * @param postJson HTTP POST JSON（[atm.bloodworkxgaming.serverstarter.InternetManager.postJson]）
     * @param get HTTP GET（[atm.bloodworkxgaming.serverstarter.InternetManager.get]）
     */
    fun scan(
            modsDir: File,
            known: Collection<String>,
            postJson: (String, String) -> String,
            get: (String) -> String
    ): ScanResult {
        val jars = modsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) && it.name !in known }
                ?: return ScanResult(emptyMap(), true)
        if (jars.isEmpty()) return ScanResult(emptyMap(), true)

        val sha1ByName = LinkedHashMap<String, String>()
        for (jar in jars) {
            sha1ByName[jar.name] = HashingUtil.sha1Hex(jar)
        }

        val versionFiles = try {
            postJson(VERSION_FILES_URL, versionFilesBody(sha1ByName.values.distinct()))
        } catch (e: Exception) {
            LOGGER.warn("Modrinth hash lookup failed, keeping mods untouched: " + e.message)
            return ScanResult(emptyMap(), false)
        }
        val hashToProject = try {
            parseVersionFiles(versionFiles)
        } catch (e: Exception) {
            LOGGER.warn("Unable to parse Modrinth hash lookup response, keeping mods untouched: " + e.message)
            return ScanResult(emptyMap(), false)
        }
        if (hashToProject.isEmpty()) {
            LOGGER.info("No installed mod matched Modrinth, skipping platform client-mod check.")
            return ScanResult(emptyMap(), true)
        }

        val serverSides = LinkedHashMap<String, String?>()
        var complete = true
        for (chunk in hashToProject.values.distinct().chunked(PROJECT_CHUNK)) {
            try {
                serverSides.putAll(parseProjectServerSides(get(projectsUrl(chunk))))
            } catch (e: Exception) {
                complete = false
                LOGGER.warn("Modrinth project lookup failed for " + chunk.size + " project(s), keeping them: " + e.message)
            }
        }

        val verdicts = LinkedHashMap<String, ApiVerdict>()
        for ((name, sha1) in sha1ByName) {
            val projectId = hashToProject[sha1.lowercase()] ?: continue
            if (!isClientOnly(serverSides[projectId])) continue
            verdicts[name] = ApiVerdict.CLIENT_ONLY
            LOGGER.info("Modrinth says project " + projectId + " is client-only: " + name)
        }
        return ScanResult(verdicts, complete)
    }
}
