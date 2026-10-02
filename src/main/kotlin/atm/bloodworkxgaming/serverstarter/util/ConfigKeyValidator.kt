package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import org.yaml.snakeyaml.Yaml
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

/**
 * 配置键校验：snakeyaml 遇到不认识的键会直接失败，但报错是
 * `Unable to find property 'descripton' on class: ...ModpackConfig` —— 既不说这是 yaml 里的哪一层，
 * 也不说正确写法是什么。这里在加载前比对一次，把未知键连最近似的正确写法一起报出来。
 *
 * 规则：
 * - 键名与数据类字段严格区分大小写（`maxram` ≠ `maxRam`）；
 * - `Map<String, Any>` 字段（`formatSpecific`）下的键随包型而定，不校验；
 * - `List<String>` 之类的字符串列表不校验；`List<AdditionalFile>` 这类对象列表会逐项校验键名。
 */
object ConfigKeyValidator {

    /** 开放字段（`Map`）的标记：其下的键不做校验。 */
    private val OPEN = Any::class.java

    /** 返回未知键的路径（如 `install.modpackfromat`）。yaml 解析失败时返回空列表（交给正常流程报错）。 */
    fun unknownKeys(yamlText: String, rootClass: Class<*>): List<String> {
        val root = try {
            Yaml().load<Any?>(yamlText)
        } catch (e: Exception) {
            return emptyList()
        }
        val map = root as? Map<*, *> ?: return emptyList()

        val out = mutableListOf<String>()
        collect(map, rootClass, "", out)
        return out
    }

    /** 就近的已知键（编辑距离 ≤ 3 才算，大小写差异按 1 计），没有则返回 null。 */
    fun suggestionFor(unknownKey: String, knownKeys: Collection<String>): String? = knownKeys
            .map { it to editDistance(unknownKey, it) }
            .filter { it.second in 1..3 }
            .minByOrNull { it.second }
            ?.first

    /** 校验并告警；返回发现的未知键（便于测试与调用方继续处理）。 */
    fun warnUnknownKeys(yamlText: String, rootClass: Class<*>): List<String> {
        val unknown = unknownKeys(yamlText, rootClass)
        if (unknown.isEmpty()) return unknown

        LOGGER.warn("Config has ${unknown.size} key(s) the launcher does not know (loading will fail on them):")
        for (key in unknown) {
            val level = key.substringBeforeLast('.', "")
            val prefix = if (level.isEmpty()) "" else "$level."
            val suggestion = suggestionFor(key.substringAfterLast('.'), knownKeysOf(rootClass, level))
            LOGGER.warn("  - $key" + if (suggestion != null) " (did you mean '$prefix$suggestion'?)" else "")
        }
        return unknown
    }

    private fun collect(node: Map<*, *>, clazz: Class<*>, prefix: String, out: MutableList<String>) {
        val fields = clazz.declaredFields.associateBy { it.name }

        for ((rawKey, value) in node) {
            val key = rawKey?.toString() ?: continue
            val field = fields[key]
            if (field == null) {
                out += prefix + key
                continue
            }

            val itemClass = itemClassOf(field)
            if (value is Map<*, *> && itemClass != null && itemClass != OPEN) {
                collect(value, itemClass, "$prefix$key.", out)
            } else if (value is List<*> && itemClass != null && itemClass != OPEN) {
                value.forEachIndexed { index, item ->
                    if (item is Map<*, *>) collect(item, itemClass, "$prefix$key[$index].", out)
                }
            }
        }
    }

    /** 字段值的类：对象 → 该类；`Map` → [OPEN]；其它（字符串/数字/字符串列表）→ null（不校验）。 */
    private fun itemClassOf(field: Field): Class<*>? {
        val type = field.type
        val generic = field.genericType

        return when {
            Map::class.java.isAssignableFrom(type) -> OPEN
            List::class.java.isAssignableFrom(type) -> {
                val argument = (generic as? ParameterizedType)?.actualTypeArguments?.firstOrNull() as? Class<*>
                if (argument == null || argument == String::class.java || argument.isPrimitive) null else argument
            }
            type == String::class.java || type.isPrimitive || Number::class.java.isAssignableFrom(type) ||
                    type == Boolean::class.java -> null
            else -> type
        }
    }

    /** 取某层级（`install` / `launch` / 空串表示根）的已知键名。 */
    private fun knownKeysOf(rootClass: Class<*>, level: String): Collection<String> {
        if (level.isEmpty()) return rootClass.declaredFields.map { it.name }

        val path = level.trimEnd('.').split('.')
        var clazz: Class<*> = rootClass
        for (segment in path) {
            val field = clazz.declaredFields.firstOrNull { it.name == segment } ?: return emptyList()
            clazz = itemClassOf(field)?.takeIf { it != OPEN } ?: return emptyList()
        }
        return clazz.declaredFields.map { it.name }
    }

    /** 标准 Levenshtein 距离，用于「你是不是想写 xxx」提示。 */
    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
