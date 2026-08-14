package com.example.watcher.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    @Test
    fun cleartextConfigAllowsRuntimePrivateDeviceIps() {
        val baseConfig = networkSecurityFile().let { file ->
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("base-config")
                .item(0)
        }

        assertTrue(
            "Android network-security-config cannot express private IP CIDR ranges, so runtime device IP cleartext relies on app-level local-host validation.",
            baseConfig?.attributes?.getNamedItem("cleartextTrafficPermitted")?.nodeValue == "true"
        )
    }

    @Test
    fun cleartextConfigDoesNotUseUnsupportedCidrDomains() {
        val domains = cleartextDomains()

        assertFalse(
            "network-security-config domain entries are host/domain names, not CIDR ranges.",
            domains.any { "/" in it }
        )
    }

    private fun cleartextDomains(): List<String> {
        val file = networkSecurityFile()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val domains = document.getElementsByTagName("domain")
        return (0 until domains.length)
            .map { domains.item(it).textContent.trim() }
            .filter(String::isNotBlank)
    }

    private fun networkSecurityFile(): File =
        listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml")
        ).firstOrNull(File::isFile)
            ?: error("network_security_config.xml was not found from test working directory ${File(".").absolutePath}")
}
