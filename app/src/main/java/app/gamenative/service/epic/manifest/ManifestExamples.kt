package app.gamenative.service.epic.manifest

import java.io.File

/**
 * Example usage and integration with Epic Games manifest parsing
 */
object ManifestExamples {

    /**
     * Example 1: Download and parse a manifest from URL
     */
    suspend fun downloadAndParseManifest(
        manifestUrl: String,
        expectedHash: String
    ): Result<EpicManifest> {
        return try {
            // Download manifest (you'd use your HTTP client here)
            // val manifestBytes = httpClient.get(manifestUrl).readBytes()

            // For example purposes, assuming you have the bytes
            val manifestBytes = ByteArray(0) // Replace with actual download

            // Verify hash
            if (!ManifestUtils.verifyManifestHash(manifestBytes, expectedHash)) {
                return Result.failure(Exception("Manifest hash mismatch!"))
            }

            // Parse manifest
            val manifest = ManifestUtils.loadFromBytes(manifestBytes)

            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Example 2: Parse manifest from API response
     */
    fun parseManifestFromApiResponse(manifestApiResponse: ManifestApiResponse): EpicManifest {
        // Get first manifest URL (try each CDN until one works)
        val manifestElement = manifestApiResponse.elements.first()
        val manifestUrl = manifestElement.manifests.first().buildUrl()

        // Download and parse (in real implementation)
        // val manifestBytes = downloadFromUrl(manifestUrl)
        // return ManifestUtils.loadFromBytes(manifestBytes)

        throw NotImplementedError("Implement with actual HTTP client")
    }

    /**
     * Example 3: Get download information for a game
     */
    fun getGameDownloadInfo(manifest: EpicManifest) {
        println("=== Game Information ===")
        println("App Name: ${manifest.meta?.appName}")
        println("Build Version: ${manifest.meta?.buildVersion}")
        println("Launch Executable: ${manifest.meta?.launchExe}")
        println("Launch Command: ${manifest.meta?.launchCommand}")
        println()

        println("=== Download Information ===")
        val downloadSize = ManifestUtils.getTotalDownloadSize(manifest)
        val installedSize = ManifestUtils.getTotalInstalledSize(manifest)
        val chunkCount = manifest.chunkDataList?.elements?.size ?: 0
        val fileCount = manifest.fileManifestList?.elements?.size ?: 0

        println("Download Size: ${ManifestUtils.formatBytes(downloadSize)}")
        println("Installed Size: ${ManifestUtils.formatBytes(installedSize)}")
        println("Total Chunks: $chunkCount")
        println("Total Files: $fileCount")
        println()

        // List executable files
        println("=== Executables ===")
        ManifestUtils.getExecutableFiles(manifest).forEach { file ->
            println("  ${file.filename} (${ManifestUtils.formatBytes(file.fileSize)})")
        }
    }

    /**
     * Example 4: Create a selective download plan
     */
    fun createSelectiveDownload(manifest: EpicManifest) {
        // Only download specific files or patterns
        val plan = DownloadPlanBuilder(manifest)
            .addPattern(Regex(".*\\.exe$"))  // All executables
            .addPattern(Regex(".*\\.dll$"))  // All DLLs
            .addFile("Game/Config/DefaultGame.ini")  // Specific config file
            .build()

        println(plan)

        // You can now use this plan to download only the selected files
        plan.files.forEach { fileInfo ->
            println("File: ${fileInfo.file.filename}")
            println("  Requires ${fileInfo.chunks.size} chunks")
            println("  Download: ${ManifestUtils.formatBytes(fileInfo.downloadSize)}")
            println("  Installed: ${ManifestUtils.formatBytes(fileInfo.installedSize)}")
        }
    }

    /**
     * Example 5: Compare two manifests for updates
     */
    fun checkForUpdates(oldManifestFile: File, newManifestFile: File) {
        val oldManifest = ManifestUtils.loadFromFile(oldManifestFile)
        val newManifest = ManifestUtils.loadFromFile(newManifestFile)

        val comparison = ManifestUtils.compareManifests(oldManifest, newManifest)

        println(comparison)

        if (comparison.hasChanges) {
            // Get only the chunks we need to download for the update
            val deltaChunks = ManifestUtils.getDeltaChunks(oldManifest, newManifest)
            val deltaSize = deltaChunks.sumOf { it.fileSize }

            println("Update Download Size: ${ManifestUtils.formatBytes(deltaSize)}")
            println("Update will download ${deltaChunks.size} chunks")
        } else {
            println("No updates needed - manifests are identical")
        }
    }

    /**
     * Example 6: Build chunk download URLs
     */
    fun buildChunkUrls(manifest: EpicManifest, baseUrls: List<String>) {
        val chunks = ManifestUtils.getRequiredChunks(manifest).take(5) // First 5 chunks

        println("=== Example Chunk URLs ===")
        chunks.forEach { chunk ->
            baseUrls.firstOrNull()?.let { baseUrl ->
                val url = ManifestUtils.buildChunkUrl(baseUrl, chunk, manifest)
                println("${chunk.guidStr}:")
                println("  URL: $url")
                println("  Size: ${ManifestUtils.formatBytes(chunk.fileSize)}")
                println("  Hash: ${chunk.hash}")
            }
        }
    }

    /**
     * Example 7: Download a game file (conceptual)
     */
    fun downloadGameFile(manifest: EpicManifest, filename: String, baseUrl: String): ByteArray {
        // Find the file in manifest
        val fileManifest = ManifestUtils.findFile(manifest, filename)
            ?: throw IllegalArgumentException("File not found: $filename")

        // Allocate buffer for the complete file
        val fileBuffer = ByteArray(fileManifest.fileSize.toInt())

        // Download and assemble each chunk part
        fileManifest.chunkParts.forEach { part ->
            // Get chunk info
            val chunk = manifest.chunkDataList?.getChunkByGuid(part.guidStr)
                ?: throw IllegalStateException("Chunk not found: ${part.guidStr}")

            // Build chunk URL
            val chunkUrl = ManifestUtils.buildChunkUrl(baseUrl, chunk, manifest)

            // Download chunk (pseudo-code)
            // val chunkData = httpClient.get(chunkUrl).readBytes()

            // Decompress chunk if needed
            // val decompressedChunk = if (chunk.fileSize < chunk.windowSize) {
            //     decompress(chunkData)
            // } else {
            //     chunkData
            // }

            // Copy the relevant part to the file buffer
            // System.arraycopy(
            //     decompressedChunk, part.offset,
            //     fileBuffer, part.fileOffset,
            //     part.size
            // )
        }

        return fileBuffer
    }

    /**
     * Example 8: Verify file integrity after download
     */
    fun verifyFileIntegrity(downloadedData: ByteArray, fileManifest: FileManifest): Boolean {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val computedHash = sha1.digest(downloadedData)
        return computedHash.contentEquals(fileManifest.hash)
    }

    /**
     * Example 9: Working with install tags
     */
    fun installWithTags(manifest: EpicManifest, tags: List<String>) {
        println("=== Installing with tags: ${tags.joinToString(", ")} ===")

        val files = ManifestUtils.getFilesWithTags(manifest, tags)
        val totalSize = files.sumOf { it.fileSize }

        println("Files to install: ${files.size}")
        println("Total size: ${ManifestUtils.formatBytes(totalSize)}")

        files.take(10).forEach { file ->
            println("  ${file.filename}")
        }

        if (files.size > 10) {
            println("  ... and ${files.size - 10} more")
        }
    }
}

/**
 * Data classes for API responses
 */
data class ManifestApiResponse(
    val elements: List<ManifestElement>
)

data class ManifestElement(
    val appName: String,
    val labelName: String,
    val buildVersion: String,
    val hash: String,
    val manifests: List<ManifestUri>
)

data class ManifestUri(
    val uri: String,
    val queryParams: List<QueryParam> = emptyList()
) {
    fun buildUrl(): String {
        if (queryParams.isEmpty()) return uri

        val params = queryParams.joinToString("&") { "${it.name}=${it.value}" }
        return "$uri?$params"
    }
}

data class QueryParam(
    val name: String,
    val value: String
)

/**
 * Integration with existing Epic service
 */
class EpicManifestIntegration {

    /**
     * Download manifest from Epic API response
     */
    suspend fun getManifest(
        namespace: String,
        catalogItemId: String,
        appName: String,
        platform: String = "Windows",
        label: String = "Live"
    ): Result<EpicManifest> {
        return try {
            // 1. Get manifest URLs from Epic API
            // val apiResponse = epicService.getGameManifest(namespace, catalogItemId, appName, platform, label)

            // 2. Try each manifest URL until one works
            // for (manifestInfo in apiResponse.elements[0].manifests) {
            //     try {
            //         val url = manifestInfo.buildUrl()
            //         val manifestBytes = httpClient.get(url).readBytes()
            //
            //         // Verify hash
            //         val expectedHash = apiResponse.elements[0].hash
            //         if (ManifestUtils.verifyManifestHash(manifestBytes, expectedHash)) {
            //             val manifest = ManifestUtils.loadFromBytes(manifestBytes)
            //             return Result.success(manifest)
            //         }
            //     } catch (e: Exception) {
            //         // Try next URL
            //         continue
            //     }
            // }

            Result.failure(Exception("Failed to download manifest from any CDN"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
