package app.gamenative.service.epic.manifest.test

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.epic.manifest.ManifestTestSerializer
import app.gamenative.service.epic.manifest.ManifestUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * Compares Kotlin and Python manifest parsing implementations
 */
@RunWith(AndroidJUnit4::class)
class ManifestPythonComparisonTest {

    private fun getContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun getManifestBytes(assetName: String): ByteArray {
        val inputStream = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        return inputStream.readBytes()
    }

    private fun getExpectedJson(assetName: String): JSONObject {
        val inputStream = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        val expectedText = inputStream.bufferedReader().use { it.readText() }
        return JSONObject(expectedText)
    }

    @Test
    fun testCompareWithPython() {
        val testManifests = listOf(
            "test-manifest.json" to "test-manifest.expected.json",
            "test-v3-manifest.json" to "test-v3-manifest.expected.json",
            "binary-control-file.manifest" to "binary-control-file.expected.json"
        )

        testManifests.forEach { (manifestAsset, expectedAsset) ->
            Timber.i("Comparing manifest: $manifestAsset")

            // Parse with Kotlin
            val manifestBytes = getManifestBytes(manifestAsset)
            val kotlinManifest = ManifestUtils.loadFromBytes(manifestBytes)
            val kotlinJson = ManifestTestSerializer.serializeManifest(kotlinManifest)

            val pythonJson = getExpectedJson(expectedAsset)

            // Compare basic properties
            val differences = mutableListOf<String>()

            compareField(kotlinJson, pythonJson, "version", differences)
            compareField(kotlinJson.getJSONObject("meta"), pythonJson.getJSONObject("meta"), "appName", differences)
            compareField(kotlinJson.getJSONObject("meta"), pythonJson.getJSONObject("meta"), "buildVersion", differences)
            compareField(kotlinJson.getJSONObject("chunkDataList"), pythonJson.getJSONObject("chunkDataList"), "count", differences)
            compareField(kotlinJson.getJSONObject("fileManifestList"), pythonJson.getJSONObject("fileManifestList"), "count", differences)

            // Compare file hashes for first few files
            val kotlinFiles = kotlinJson.getJSONObject("fileManifestList").getJSONArray("files")
            val pythonFiles = pythonJson.getJSONObject("fileManifestList").getJSONArray("files")

            for (i in 0 until minOf(10, kotlinFiles.length(), pythonFiles.length())) {
                val kotlinFile = kotlinFiles.getJSONObject(i)
                val pythonFile = pythonFiles.getJSONObject(i)

                val kotlinFilename = kotlinFile.getString("filename")
                val pythonFilename = pythonFile.getString("filename")

                if (kotlinFilename != pythonFilename) {
                    differences.add("File $i: filename differs - Kotlin: '$kotlinFilename', Python: '$pythonFilename'")
                }

                val kotlinHash = kotlinFile.getString("hash")
                val pythonHash = pythonFile.getString("hash")

                if (kotlinHash != pythonHash) {
                    differences.add("File $i ($kotlinFilename): hash differs - Kotlin: '$kotlinHash', Python: '$pythonHash'")
                }
            }

            // Log differences
            if (differences.isNotEmpty()) {
                Timber.e("Found ${differences.size} differences between Kotlin and Python implementations for $manifestAsset:")
                differences.forEach { diff ->
                    Timber.e("  - $diff")
                }
                fail("Kotlin and Python implementations differ for $manifestAsset. See logs for details.")
            } else {
                Timber.i("✅ Kotlin and Python implementations match for $manifestAsset!")
            }
        }
    }

    private fun compareField(kotlinObj: JSONObject, pythonObj: JSONObject, field: String, differences: MutableList<String>) {
        try {
            val kotlinValue = kotlinObj.get(field)
            val pythonValue = pythonObj.get(field)

            if (kotlinValue != pythonValue) {
                differences.add("Field '$field': Kotlin='$kotlinValue', Python='$pythonValue'")
            }
        } catch (e: Exception) {
            differences.add("Field '$field': Error comparing - ${e.message}")
        }
    }
}
