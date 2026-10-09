package com.guanyi.mirra.platform.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source-rule contract; compiled resource and manifest checks live in androidTest. */
class AndroidBackupPolicyTest {
    @Test
    fun manifestDisablesAutomaticAndLegacyBackupAndBindsModernRules() {
        val application = application()
        assertEquals("System backup must be explicitly disabled", "false", application.android("allowBackup"))
        assertEquals("Legacy and debugger full backup must be disabled as a whole", "false", application.android("fullBackupContent"))
        assertTrue("API 31+ devices need dataExtractionRules", application.android("dataExtractionRules").startsWith("@xml/"))
        assertFalse("A custom BackupAgent must not bypass the declarative policy", application.hasAttributeNS(ANDROID_NAMESPACE, "backupAgent"))
    }

    @Test
    fun legacyFullBackupIsDisabledRatherThanDefaultingToAllDomains() {
        // AOSP represents literal false as -1 and disables the whole legacy backup/restore
        // scheme. A positive XML resource ID would enable unsupported debugger destination 2.
        val enabled = application().android("fullBackupContent").toBooleanStrictOrNull()
        assertEquals("Legacy backup must deny every domain, without an XML-resource fallback", false, enabled)
    }

    @Test
    fun modernRulesExplicitlyExcludeCloudDeviceAndCrossPlatformTransfers() {
        val rules = ruleResource("dataExtractionRules")
        assertEquals("data-extraction-rules", rules.tagName)
        val modes = rules.children()
        assertEquals(
            "Omitting a mode leaves its data at the platform default",
            setOf("cloud-backup", "device-transfer", "cross-platform-transfer"),
            modes.map { it.tagName }.toSet(),
        )
        assertEquals("One policy section for each supported mode", 3, modes.size)
        modes.forEach { mode ->
            if (mode.tagName == "cross-platform-transfer") {
                assertEquals("ios", mode.getAttribute("platform"))
                assertTrue("No peer app identity is invented for a deny-only policy", mode.children().none { it.tagName == "platform-specific-params" })
            }
            assertDenyAll(mode)
        }
    }

    private fun assertDenyAll(section: Element) {
        val rules = section.children()
        assertTrue("${section.tagName} must contain only exclusions", rules.all { it.tagName == "exclude" })
        assertEquals("${section.tagName} must cover every documented storage domain", DOMAINS, rules.map { it.getAttribute("domain") }.toSet())
        assertEquals("No duplicate or missing domain rules", DOMAINS.size, rules.size)
        rules.forEach { rule ->
            assertTrue("Exclude the complete ${rule.getAttribute("domain")} directory recursively", rule.getAttribute("path") in setOf(".", "./"))
        }
        // Literal paths independently cover current business resources and a future nested file.
        // These are policy evaluation samples, not filesystem fixtures or transport execution.
        val samples = mapOf(
            "root" to listOf("no_backup/full-restore/restore.journal", "no_backup/full-backup-work/temp.zip", "cache/pending.jpg", "app_runtime/owner", "future/private.bin"),
            "file" to listOf("datastore/mirra_preferences.preferences_pb", "images/image.jpg", "images/.pending.tmp", "future/nested/file"),
            "database" to listOf("mirra.db", "mirra.db-wal", "mirra.db-shm", "mirra.db-journal"),
            "sharedpref" to listOf("runtime.xml"),
            "external" to listOf("temporary-export.csv", "images/photo.jpg"),
            "device_root" to listOf("no_backup/ownership", "future/private.bin"),
            "device_file" to listOf("datastore/preferences_pb", "runtime/owner"),
            "device_database" to listOf("mirra.db", "mirra.db-wal", "mirra.db-shm", "mirra.db-journal"),
            "device_sharedpref" to listOf("runtime.xml"),
        )
        samples.forEach { (domain, paths) ->
            paths.forEach { path ->
                assertTrue("${section.tagName} must exclude $domain/$path", rules.any { it.getAttribute("domain") == domain && excludes(it.getAttribute("path"), path) })
            }
        }
    }

    private fun excludes(exclusion: String, path: String): Boolean {
        val relative = exclusion.removePrefix("./").removeSuffix("/")
        return relative.isEmpty() || relative == "." || path == relative || path.startsWith("$relative/")
    }

    private fun application(): Element = parse(mainFile("AndroidManifest.xml")).children().single { it.tagName == "application" }

    private fun ruleResource(attribute: String): Element {
        val reference = application().android(attribute)
        assertTrue("Manifest $attribute must reference an XML rule resource", reference.startsWith("@xml/"))
        val source = mainFile("res/xml/${reference.removePrefix("@xml/")}.xml")
        assertTrue("Referenced backup rule must exist: ${source.path}", source.isFile)
        return parse(source)
    }

    private fun mainFile(path: String): File {
        val module = File("src/main/$path")
        return if (module.isFile) module else File("app/src/main/$path")
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(file).documentElement

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val DOMAINS = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
