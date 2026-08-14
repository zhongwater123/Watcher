package com.example.watcher.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDomainBoundaryTest {
    private val projectRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }
    private val sourceRoot = File(projectRoot, "app/src/main/java/com/example/watcher")
    private val fitnessAgentRoot = File(sourceRoot, "data/fitness/agent")
    private val councilAgentRoot = File(sourceRoot, "data/council/agent")

    @Test
    fun legacyDataAgentPackageIsEmpty() {
        val legacyRoot = File(sourceRoot, "data/agent")
        assertFalse(
            "data/agent must not contain Kotlin sources",
            legacyRoot.walkTopDown().any { it.isFile && it.extension == "kt" }
        )
    }

    @Test
    fun businessAgentDomainsDoNotImportEachOtherOrAgentFramework() {
        fitnessAgentRoot.kotlinFiles().forEach { file ->
            val source = file.readText()
            assertFalse(file.path, source.contains("com.example.watcher.data.council"))
            assertFalse(file.path, source.contains("com.example.watcher.agentframework"))
        }
        councilAgentRoot.kotlinFiles().forEach { file ->
            val source = file.readText()
            assertFalse(file.path, source.contains("com.example.watcher.data.fitness"))
            assertFalse(file.path, source.contains("com.example.watcher.agentframework"))
        }
    }

    @Test
    fun businessAgentFilesAndTopLevelTypesCarryDomainPrefixes() {
        assertDomainPrefixes(fitnessAgentRoot, listOf("Fitness", "LegacyFitness"))
        assertDomainPrefixes(councilAgentRoot, listOf("Council"))
    }

    private fun assertDomainPrefixes(root: File, prefixes: List<String>) {
        val typePattern = Regex(
            """^(?:data\s+|sealed\s+|enum\s+)?(?:class|interface|object)\s+([A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        root.kotlinFiles().forEach { file ->
            assertTrue(
                "Agent file must carry a domain prefix: ${file.path}",
                prefixes.any { prefix -> file.nameWithoutExtension.startsWith(prefix) }
            )
            typePattern.findAll(file.readText()).forEach { match ->
                val typeName = match.groupValues[1]
                assertTrue(
                    "Top-level type must carry a domain prefix: ${file.path}:$typeName",
                    prefixes.any { prefix -> typeName.startsWith(prefix) }
                )
            }
        }
    }

    private fun File.kotlinFiles(): Sequence<File> {
        assertTrue("Missing source directory: $path", isDirectory)
        return walkTopDown().filter { it.isFile && it.extension == "kt" }
    }
}
