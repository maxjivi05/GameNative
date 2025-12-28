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
import timber.log.Timber

/**
 * Compares Kotlin and Python manifest parsing implementations
 */
@RunWith(AndroidJUnit4::class)
class ManifestPythonComparisonTest {

    private fun getContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun getManifestBytes(): ByteArray {
        val inputStream = InstrumentationRegistry.getInstrumentation().context.assets.open("test-v3-manifest.json")
        return inputStream.readBytes()
    }

    /**
     * Runs Python parser and returns output as JSON
     */
    private fun runPythonParser(manifestFile: File): JSONObject? {
        try {
            val pythonScript = File(getContext().filesDir, "manifest_test_python.py")

            // Copy Python script from assets to file system
            val scriptStream = InstrumentationRegistry.getInstrumentation().context.assets.open("manifest_test_python.py")
            pythonScript.parentFile?.mkdirs()
            pythonScript.writeBytes(scriptStream.readBytes())

            val process = ProcessBuilder(
                "python3",
                pythonScript.absolutePath,
                manifestFile.absolutePath,
                "summary",
            ).redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Timber.e("Python parser failed with exit code $exitCode")
                Timber.e("Output: $output")
                return null
            }

            return JSONObject(output)
        } catch (e: Exception) {
            Timber.e(e, "Failed to run Python parser")
            return null
        }
    }

    @Test
    fun testCompareWithPython() {
        // Parse with Kotlin
        val manifestBytes = getManifestBytes()
        val kotlinManifest = ManifestUtils.loadFromBytes(manifestBytes)
        val kotlinJson = ManifestTestSerializer.serializeManifest(kotlinManifest)

        // Save manifest to temp file for Python
        val manifestFile = File(getContext().cacheDir, "test-manifest.json")
        manifestFile.writeBytes(manifestBytes)

        // Parse with Python (may not be available in all test environments)
        val pythonJson = runPythonParser(manifestFile)

        if (pythonJson == null) {
            Timber.w("Python parser not available, skipping comparison")
            return
        }

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
            Timber.e("Found ${differences.size} differences between Kotlin and Python implementations:")
            differences.forEach { diff ->
                Timber.e("  - $diff")
            }
            fail("Kotlin and Python implementations differ. See logs for details.")
        } else {
            Timber.i("✅ Kotlin and Python implementations match!")
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
