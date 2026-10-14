package dev.wckdboy.autobot.core.models

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The iOS app reads the curated catalog from `shared/catalog/catalog.json`. This keeps that file
 * identical to [Catalog]; run with `UPDATE_SHARED_CATALOG=1` to regenerate it after a change.
 */
class SharedCatalogTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    @Test
    fun sharedCatalogMatchesTheKotlinCatalog() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "shared/catalog/catalog.json") }
            .first { it.parentFile.parentFile.resolve("build.gradle.kts").isFile }
        val expected = json.encodeToString(ListSerializer(ModelPlan.serializer()), Catalog.all) + "\n"
        if (System.getenv("UPDATE_SHARED_CATALOG") == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        assertEquals("shared/catalog/catalog.json is stale; rerun with UPDATE_SHARED_CATALOG=1", expected, file.readText())
    }
}
