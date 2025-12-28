package app.gamenative.service.epic.manifest.test

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.epic.manifest.ManifestTestSerializer
import app.gamenative.service.epic.manifest.ManifestUtils
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented test for manifest parsing
 */
@RunWith(AndroidJUnit4::class)
class ManifestParseInstrumentedTest {

    private fun getContext(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun getManifestBytes(): ByteArray {
        // Read manifest from test assets
        val inputStream = getContext().assets.open("test-v3-manifest.json")
        return inputStream.readBytes()
    }

    @Test
    fun testManifestParsing() {
        // Get the manifest data from assets
        val manifestBytes = getManifestBytes()
        assertTrue("Manifest data should not be empty", manifestBytes.isNotEmpty())

        // Parse the manifest
        val manifest = ManifestUtils.loadFromBytes(manifestBytes)

        // Verify basic properties
        assertNotNull("Manifest should not be null", manifest)
        assertNotNull("Manifest meta should not be null", manifest.meta)

        // Check expected values from Python output
        println("🔍 Kotlin vs Python Parsing Comparison:")
        println("  Checking appName...")
        assertEquals("App name should match", "Quail", manifest.meta?.appName)
        println("    ✅ appName matches")

        println("  Checking buildVersion...")
        assertEquals(
            "Build version should match",
            "2.9.2-2874913+++Portal+Release-Live-Windows",
            manifest.meta?.buildVersion,
        )
        println("    ✅ buildVersion matches")

        println("  Checking manifest version...")
        assertEquals("Manifest version should be 12", 12, manifest.version)
        println("    ✅ version matches")

        // Check counts
        val chunkCount = manifest.chunkDataList?.elements?.size ?: 0
        val fileCount = manifest.fileManifestList?.elements?.size ?: 0

        println("  Checking chunk count...")
        assertEquals("Should have 246 chunks", 246, chunkCount)
        println("    ✅ chunk count matches")

        println("  Checking file count...")
        assertEquals("Should have 3430 files", 3430, fileCount)
        println("    ✅ file count matches")

        // Check sizes
        val downloadSize = ManifestUtils.getTotalDownloadSize(manifest)
        val installedSize = ManifestUtils.getTotalInstalledSize(manifest)

        println("  Checking download size...")
        assertEquals("Download size should match", 107838099L, downloadSize)
        println("    ✅ download size matches")

        println("  Checking installed size...")
        assertEquals("Installed size should match", 239239382L, installedSize)
        println("    ✅ installed size matches")

        println("✅ All assertions passed! Kotlin implementation matches Python!")
        println("  Download: ${ManifestUtils.formatBytes(downloadSize)}")
        println("  Installed: ${ManifestUtils.formatBytes(installedSize)}")
    }

    @Test
    fun testManifestJsonSerialization() {
        // Get the manifest data from assets
        val manifestBytes = getManifestBytes()

        // Parse the manifest
        val manifest = ManifestUtils.loadFromBytes(manifestBytes)

        // Serialize to JSON
        val summary = ManifestTestSerializer.createManifestSummary(manifest)

        // Verify JSON structure
        assertTrue("Should have version", summary.has("version"))
        assertTrue("Should have appName", summary.has("appName"))
        assertTrue("Should have buildVersion", summary.has("buildVersion"))
        assertTrue("Should have chunkCount", summary.has("chunkCount"))
        assertTrue("Should have fileCount", summary.has("fileCount"))
        assertTrue("Should have downloadSize", summary.has("downloadSize"))
        assertTrue("Should have installedSize", summary.has("installedSize"))
        assertTrue("Should have sampleFiles", summary.has("sampleFiles"))
        assertTrue("Should have sampleChunks", summary.has("sampleChunks"))

        println("✅ JSON serialization test passed!")
        println(summary.toString(2))
    }

    @Test
    fun testManifestFileDetails() {
        val manifestBytes = getManifestBytes()

        val manifest = ManifestUtils.loadFromBytes(manifestBytes)

        // Check first file matches Python output
        val firstFile = manifest.fileManifestList?.elements?.firstOrNull()
        assertNotNull("Should have files", firstFile)

        assertEquals(
            "First file should match",
            "Engine/Binaries/ThirdParty/CEF3/Win64/d3dcompiler_43.dll",
            firstFile?.filename,
        )
        assertEquals("File size should match", 2106216L, firstFile?.fileSize)
        assertEquals("Chunk parts count should match", 3, firstFile?.chunkParts?.size)

        // Verify hash (SHA1)
        val expectedHash = "98be17e1d324790a5b206e1ea1cc4e64fbe21240"
        val actualHash = firstFile?.hash?.joinToString("") { "%02x".format(it) }

        // Log for comparison with Python
        println("🔍 Kotlin vs Python Hash Comparison:")
        println("  Expected (Python): $expectedHash")
        println("  Actual (Kotlin):   $actualHash")

        if (expectedHash == actualHash) {
            println("  ✅ MATCH - Kotlin implementation matches Python!")
        } else {
            println("  ❌ MISMATCH - Implementations differ!")
        }

        assertEquals("File hash should match", expectedHash, actualHash)

        println("✅ File details test passed!")
        println("First file: ${firstFile?.filename}")
        println("  Size: ${firstFile?.fileSize}")
        println("  Hash: $actualHash")
        println("  Chunk parts: ${firstFile?.chunkParts?.size}")
    }

    @Test
    fun testManifestChunkDetails() {
        val manifestBytes = getManifestBytes()

        val manifest = ManifestUtils.loadFromBytes(manifestBytes)

        // Check first chunk matches Python output
        val firstChunk = manifest.chunkDataList?.elements?.firstOrNull()
        assertNotNull("Should have chunks", firstChunk)

        assertEquals(
            "First chunk GUID should match",
            "80cf8543-4a18c0dc-4bd48290-9f40df8d",
            firstChunk?.guidStr,
        )
        assertEquals("Chunk size should match", 406716L, firstChunk?.fileSize)
        assertEquals("Group number should match", 87, firstChunk?.groupNum)

        println("✅ Chunk details test passed!")
        println("First chunk: ${firstChunk?.guidStr}")
        println("  Size: ${firstChunk?.fileSize}")
        println("  Hash: ${firstChunk?.hash}")
        println("  Group: ${firstChunk?.groupNum}")
    }
}
