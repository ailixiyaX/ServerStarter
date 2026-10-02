import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.util.ConfigKeyValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置键校验测试：未知键要被发现（含嵌套与对象列表），合法键、开放字段（formatSpecific）、
 * 字符串列表都不应误报，并给出就近的键名建议。
 */
class ConfigKeyValidatorTest {

    private val validConfig = """
_specver: 2
modpack:
  name: pack
  description: desc
install:
  mcVersion: 1.21.1
  loaderVersion: 21.1.249
  installerUrl: ""
  installerArguments: []
  downloadSource: mojang
  modpackUrl: "./.zip"
  modpackFormat: auto
  formatSpecific:
    ignoreProject:
      - 263420
  baseInstallPath: ~
  ignoreFiles: []
  additionalFiles:
    - url: https://example.com/a.jar
      destination: mods/a.jar
  localFiles:
    - from: ./x.jar
      to: mods/x.jar
  checkFolder: false
  installLoader: true
  connectTimeout: 30
  readTimeout: 30
launch:
  spongefix: false
  maxRam: 5G
  minRam: 2G
  startFile: ""
  startCommand:
    - nogui
  javaArgs:
    - "-XX:+UseG1GC"
  supportedJavaVersions:
    - "17"
  checkUrls:
    - https://example.com
  autoRestart: false
  crashLimit: 0
  crashTimer: 60min
  forcedJavaPath: ~
""".trimIndent()

    @Test
    fun `合法配置没有未知键`() {
        assertEquals(emptyList<String>(), ConfigKeyValidator.unknownKeys(validConfig, ConfigFile::class.java))
    }

    @Test
    fun `顶层未知键会被发现`() {
        val yaml = "modpackx:\n  name: a\ninstall:\n  modpackUrl: \"\"\n"

        assertEquals(listOf("modpackx"), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `嵌套未知键带层级路径`() {
        val yaml = """
install:
  modpackfromat: auto
  modpackUrl: "./.zip"
""".trimIndent()

        assertEquals(listOf("install.modpackfromat"), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `大小写不同也算未知键`() {
        val yaml = "launch:\n  maxram: 5G\n"

        assertEquals(listOf("launch.maxram"), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `formatSpecific 下的键不校验`() {
        val yaml = "install:\n  formatSpecific:\n    anythingGoes:\n      - 1\n    ignoreProject:\n      - 263420\n"

        assertEquals(emptyList<String>(), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `对象列表里的未知键会被发现`() {
        val yaml = """
install:
  additionalFiles:
    - url: https://example.com/a.jar
      destinaton: mods/a.jar
""".trimIndent()

        assertEquals(listOf("install.additionalFiles[0].destinaton"), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `字符串列表与缺失的键都不算未知键`() {
        val yaml = "launch:\n  startCommand:\n    - nogui\n    - x\n  javaArgs:\n    - a\n"

        assertEquals(emptyList<String>(), ConfigKeyValidator.unknownKeys(yaml, ConfigFile::class.java))
    }

    @Test
    fun `坏 yaml 不抛异常`() {
        assertEquals(emptyList<String>(), ConfigKeyValidator.unknownKeys("install: [unclosed", ConfigFile::class.java))
    }

    @Test
    fun `就近键名建议`() {
        assertEquals("modpackFormat", ConfigKeyValidator.suggestionFor("modpackfromat", listOf("modpackUrl", "modpackFormat")))
        assertEquals("maxRam", ConfigKeyValidator.suggestionFor("maxram", listOf("maxRam", "minRam", "ramDisk")))
        assertNull(ConfigKeyValidator.suggestionFor("completelyDifferent", listOf("maxRam", "minRam")))
    }

    @Test
    fun `告警返回未知键列表`() {
        val yaml = "install:\n  modpackfromat: auto\nlaunch:\n  maxram: 5G\n"

        val unknown = ConfigKeyValidator.warnUnknownKeys(yaml, ConfigFile::class.java)

        assertEquals(2, unknown.size)
        assertTrue(unknown.containsAll(listOf("install.modpackfromat", "launch.maxram")))
    }
}
