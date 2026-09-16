package com.gte619n.healthfitness.data.sync

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Binds the Android [CollectionRegistry] to the shared cross-client sync
 * contract (docs/reference/sync-emitted-collections.txt): every collection the
 * backend delta reader can emit MUST resolve to a mirror table here, or a pulled
 * change is silently skipped (the bug that once dropped cross-device nutrition
 * entries and adherence). The backend side pins the fixture to its reader; this
 * side pins it to the registry — so a new backend collection can't ship without
 * the client learning to route it.
 */
class CollectionRegistryContractTest {

    @Test
    fun `registry routes every backend-emitted collection`() {
        val collections = sharedFixtureCollections()
        // Guard against a broken fixture path silently passing an empty loop.
        assert(collections.size >= 20) { "fixture looks empty (${collections.size} entries)" }
        for (collection in collections) {
            assertNotNull(
                "CollectionRegistry.tableFor(\"$collection\") is null — the backend " +
                    "emits this collection but the client would skip it. Add an alias " +
                    "in CollectionRegistry.",
                CollectionRegistry.tableFor(collection),
            )
        }
    }

    private fun sharedFixtureCollections(): List<String> {
        val file = locate("docs/reference/sync-emitted-collections.txt")
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** Locate the repo-shared fixture by walking up from the test working dir. */
    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val candidate = dir?.resolve(relative)
            if (candidate != null && candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("could not locate $relative from ${File("").absoluteFile}")
    }
}
