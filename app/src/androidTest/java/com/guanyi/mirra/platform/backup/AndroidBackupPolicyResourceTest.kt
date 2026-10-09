package com.guanyi.mirra.platform.backup

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Reads the installed APK's binary manifest and compiled XML resources without opening business
 * stores or invoking any backup transport. Passing these checks proves policy packaging and
 * recursive domain exclusions; it does not prove cloud/D2D/iOS/OEM transport behavior.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBackupPolicyResourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedManifestOptsOutAndResolvesCompiledExtractionRules() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("System backup flag must be cleared in the installed package", 0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue("No custom agent may bypass the declarative policy", info.backupAgentName.isNullOrEmpty())
        val manifest = manifestApplication()
        assertFalse("Compiled manifest must explicitly opt out", manifest.allowBackup)
        assertFalse("Compiled manifest must disable the complete legacy scheme", manifest.legacyEnabled)
        assertEquals("Legacy denial must be boolean, not a positive XML resource ID", 0, manifest.legacyResource)
        assertResource(manifest.modern, "data-extraction-rules")
    }

    @Test
    fun installedLegacyPolicyDisablesAllDomainsAndUnsupportedDebuggerDestination() {
        val manifest = manifestApplication()
        assertFalse("All legacy domains must be disabled as one scheme", manifest.legacyEnabled)
        assertEquals("Unsupported debugger destination must not fall back to an enabled XML ID", 0, manifest.legacyResource)
    }

    @Test
    fun installedModernRulesDoNotDefaultAnyKnownTransferModeToAllData() {
        val resource = readRules(manifestApplication().modern)
        assertEquals("data-extraction-rules", resource.root)
        assertEquals(setOf("cloud-backup", "device-transfer", "cross-platform-transfer"), resource.modes)
        assertEquals("Cross-platform denial must address the documented platform", "ios", resource.crossPlatform)
        assertFalse("The policy must not register an invented iOS peer", resource.hasPlatformSpecificParams)
        resource.modes.forEach { mode -> assertDenyAll(resource.rules.filter { it.mode == mode }, mode) }
    }

    private fun assertDenyAll(rules: List<Rule>, mode: String) {
        assertEquals("$mode must exclude every documented storage domain", DOMAINS, rules.map { it.domain }.toSet())
        assertEquals("$mode must not lose or duplicate exclusions", DOMAINS.size, rules.size)
        assertTrue("$mode must contain no includes", rules.all { it.kind == "exclude" })
        rules.forEach { rule -> assertTrue("$mode/${rule.domain} must exclude the entire domain recursively", rule.path in setOf(".", "./")) }
    }

    private fun assertResource(resource: Int, expectedRoot: String) {
        assertTrue("Compiled manifest must bind an XML resource", resource != 0)
        assertEquals("xml", context.resources.getResourceTypeName(resource))
        assertEquals(expectedRoot, readRules(resource).root)
    }

    private fun manifestApplication(): ManifestRules {
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    assertTrue("allowBackup must be explicitly present", (0 until parser.attributeCount).any { parser.getAttributeName(it) == "allowBackup" })
                    return ManifestRules(
                        parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "allowBackup", true),
                        parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "fullBackupContent", true),
                        parser.getAttributeResourceValue(ANDROID_NAMESPACE, "fullBackupContent", 0),
                        parser.getAttributeResourceValue(ANDROID_NAMESPACE, "dataExtractionRules", 0),
                    )
                }
                parser.next()
            }
        }
        error("Installed manifest has no application element")
    }

    private fun readRules(resource: Int): CompiledRules {
        assertTrue("Expected rule resource is not packaged", resource != 0)
        var root = ""
        var mode = "legacy"
        var crossPlatform: String? = null
        var hasPlatformSpecificParams = false
        val modes = mutableSetOf<String>()
        val rules = mutableListOf<Rule>()
        context.resources.getXml(resource).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.depth) {
                        1 -> root = parser.name
                        2 -> if (root == "data-extraction-rules") {
                            mode = parser.name
                            assertTrue("Duplicate mode is ambiguous: $mode", modes.add(mode))
                            if (mode == "cross-platform-transfer") crossPlatform = parser.getAttributeValue(null, "platform")
                        }
                    }
                    when (parser.name) {
                        "include", "exclude" -> rules += Rule(mode, parser.name, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
                        "platform-specific-params" -> hasPlatformSpecificParams = true
                    }
                }
                parser.next()
            }
        }
        return CompiledRules(root, modes, rules, crossPlatform, hasPlatformSpecificParams)
    }

    private data class ManifestRules(val allowBackup: Boolean, val legacyEnabled: Boolean, val legacyResource: Int, val modern: Int)
    private data class Rule(val mode: String, val kind: String, val domain: String?, val path: String?)
    private data class CompiledRules(
        val root: String,
        val modes: Set<String>,
        val rules: List<Rule>,
        val crossPlatform: String?,
        val hasPlatformSpecificParams: Boolean,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val DOMAINS = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
