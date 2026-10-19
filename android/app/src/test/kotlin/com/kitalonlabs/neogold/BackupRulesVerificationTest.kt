package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TC-PRIV-03: No backup or device transfer (NFR-13, NG-057, T17).
 *
 * Verifies that:
 * 1. AndroidManifest has android:allowBackup="false"
 * 2. backup_rules.xml excludes all storage domains (root, file, database, sharedpref, external)
 * 3. data_extraction_rules.xml excludes all storage domains for both cloud-backup and device-transfer
 * 4. No domains or paths are included in backups
 */
class BackupRulesVerificationTest {

    private val projectRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "app").exists() && !File(dir, "src").exists()) {
            dir = dir.parentFile
        }
        if (File(dir, "src").exists()) dir else File(dir, "app")
    }

    private val manifestFile: File by lazy {
        File(projectRoot, "src/main/AndroidManifest.xml")
    }

    private val backupRulesFile: File by lazy {
        File(projectRoot, "src/main/res/xml/backup_rules.xml")
    }

    private val dataExtractionRulesFile: File by lazy {
        File(projectRoot, "src/main/res/xml/data_extraction_rules.xml")
    }

    @Test
    fun manifestDisablesBackupAndReferencesRules_TC_PRIV_03() {
        assertThat(manifestFile.exists()).isTrue()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val applicationNodes = doc.getElementsByTagName("application")
        assertThat(applicationNodes.length).isEqualTo(1)

        val appElement = applicationNodes.item(0) as Element
        val allowBackup = appElement.getAttribute("android:allowBackup")
        val fullBackupContent = appElement.getAttribute("android:fullBackupContent")
        val dataExtractionRules = appElement.getAttribute("android:dataExtractionRules")

        assertThat(allowBackup).isEqualTo("false")
        assertThat(fullBackupContent).isEqualTo("@xml/backup_rules")
        assertThat(dataExtractionRules).isEqualTo("@xml/data_extraction_rules")
    }

    @Test
    fun backupRulesXmlExcludesAllDomains_TC_PRIV_03() {
        assertThat(backupRulesFile.exists()).isTrue()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(backupRulesFile)
        val includeNodes = doc.getElementsByTagName("include")
        assertThat(includeNodes.length).isEqualTo(0)

        val excludeNodes = doc.getElementsByTagName("exclude")
        val excludedDomains = mutableSetOf<String>()
        for (i in 0 until excludeNodes.length) {
            val element = excludeNodes.item(i) as Element
            excludedDomains.add(element.getAttribute("domain"))
        }

        assertThat(excludedDomains).containsExactly("root", "file", "database", "sharedpref", "external")
    }

    @Test
    fun dataExtractionRulesXmlExcludesAllDomainsForCloudAndDeviceTransfer_TC_PRIV_03() {
        assertThat(dataExtractionRulesFile.exists()).isTrue()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dataExtractionRulesFile)
        val includeNodes = doc.getElementsByTagName("include")
        assertThat(includeNodes.length).isEqualTo(0)

        // Cloud backup section
        val cloudBackupNodes = doc.getElementsByTagName("cloud-backup")
        assertThat(cloudBackupNodes.length).isEqualTo(1)
        val cloudBackupElem = cloudBackupNodes.item(0) as Element
        val cloudExcludes = cloudBackupElem.getElementsByTagName("exclude")
        val cloudDomains = mutableSetOf<String>()
        for (i in 0 until cloudExcludes.length) {
            val el = cloudExcludes.item(i) as Element
            cloudDomains.add(el.getAttribute("domain"))
        }
        assertThat(cloudDomains).containsExactly("root", "file", "database", "sharedpref", "external")

        // Device transfer section
        val deviceTransferNodes = doc.getElementsByTagName("device-transfer")
        assertThat(deviceTransferNodes.length).isEqualTo(1)
        val transferElem = deviceTransferNodes.item(0) as Element
        val transferExcludes = transferElem.getElementsByTagName("exclude")
        val transferDomains = mutableSetOf<String>()
        for (i in 0 until transferExcludes.length) {
            val el = transferExcludes.item(i) as Element
            transferDomains.add(el.getAttribute("domain"))
        }
        assertThat(transferDomains).containsExactly("root", "file", "database", "sharedpref", "external")
    }
}
