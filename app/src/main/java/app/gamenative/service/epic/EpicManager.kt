package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.db.dao.EpicGameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EpicManager handles Epic Games library management
 *
 * Responsibilities:
 * - Fetch game library from Epic via Legendary CLI
 * - Parse game metadata from JSON
 * - Update Room database
 * - Detect existing installations
 *
 * Uses legendary CLI commands:
 * - `legendary list --third-party --json` - Full library with metadata
 * - `legendary info <app_name> --json` - Detailed game info
 */
@Singleton
class EpicManager @Inject constructor(
    private val epicGameDao: EpicGameDao,
) {

    /**
     * Refresh the entire library (called manually by user or after login)
     * Fetches all games from Epic via Legendary and updates the database
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with Epic")
                return@withContext Result.failure(Exception("Not authenticated with Epic"))
            }

            Timber.tag("Epic").i("Refreshing Epic library from Epic API...")

            // Fetch games from Epic via Legendary Python backend
            val listResult = listGames(context)

            if (listResult.isFailure) {
                val error = listResult.exceptionOrNull()
                Timber.e(error, "Failed to fetch games from Epic: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch Epic library"))
            }

            val games = listResult.getOrNull() ?: emptyList()
            Timber.tag("Epic").i("Successfully fetched ${games.size} games from Epic")

            if (games.isEmpty()) {
                Timber.w("No games found in Epic library")
                return@withContext Result.success(0)
            }

            // Update database using upsert to preserve install status
            Timber.d("Upserting ${games.size} games to database...")
            epicGameDao.upsertPreservingInstallStatus(games)

            Timber.tag("Epic").i("Successfully refreshed Epic library with ${games.size} games")
            Result.success(games.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic library")
            Result.failure(e)
        }
    }

    /**
     * Fetch the user's Epic library (list of owned games)
     * Returns a list of EpicGame objects with basic metadata
     *
     * Uses: legendary list --third-party --json
     */
    private suspend fun listGames(context: Context): Result<List<EpicGame>> {
        return try {
            Timber.d("Fetching Epic library via Legendary...")

            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.e("Cannot list games: not authenticated")
                return Result.failure(Exception("Not authenticated. Please log in first."))
            }

            // Execute legendary list command with JSON output
            // --third-party includes EA/Ubisoft games
            val result = EpicPythonBridge.executeCommand("list", "--third-party", "--json")

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.e(error, "Failed to fetch Epic library: ${error?.message}")
                return Result.failure(error ?: Exception("Failed to fetch Epic library"))
            }

            val output = result.getOrNull() ?: ""
            parseGamesFromJson(output)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error while fetching Epic library")
            Result.failure(e)
        }
    }

    /**
     * Parse Legendary list output JSON into EpicGame objects
     *
     * Legendary list --json output format:
     * [
     *   {
     *     "app_name": "Fortnite",
     *     "app_title": "Fortnite",
     *     "app_version": "++Fortnite+Release-19.10-CL-19188334-Windows",
     *     "asset_infos": {...},
     *     "base_urls": [...],
     *     "catalog_item_id": "4fe75bbc5a674f4f9b356b5c90567da5",
     *     "can_run_offline": true,
     *     "cloud_save_enabled": true,
     *     "developer": "Epic Games",
     *     "is_dlc": false,
     *     "metadata": {
     *       "description": "...",
     *       "keyImages": [
     *         {"type": "DieselGameBoxTall", "url": "https://..."},
     *         {"type": "DieselGameBoxLogo", "url": "https://..."}
     *       ],
     *       "releaseInfo": [{"platform": ["Windows"], "dateAdded": "2017-07-25T17:39:54.663Z"}]
     *     },
     *     "namespace": "fn",
     *     "publisher": "Epic Games",
     *     "requires_ot": false,
     *     "third_party_managed_app": null
     *   }
     * ]
     */
    private fun parseGamesFromJson(output: String): Result<List<EpicGame>> {
        return try {
            val gamesArray = JSONArray(output.trim())
            val games = mutableListOf<EpicGame>()

            for (i in 0 until gamesArray.length()) {
                try {
                    val gameObj = gamesArray.getJSONObject(i)
                    games.add(parseGameObject(gameObj, i))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse game at index $i, skipping")
                }
            }

            Timber.i("Successfully parsed ${games.size} games from Epic library")
            Result.success(games)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Epic library JSON")
            Result.failure(Exception("Failed to parse Epic library: ${e.message}", e))
        }
    }

    /**
     * Parse individual game JSON object into EpicGame
     */
    // ! TODO: Remove the index stuff once we've seen the payload properly.
    private fun parseGameObject(gameObj: JSONObject, index: Int = -1): EpicGame {
        val appName = gameObj.optString("app_name", "")
        val title = gameObj.optString("app_title", "Unknown Game")
        val catalogItemId = gameObj.optString("catalog_item_id", "")

        // Log full game JSON for first 3 games only to reduce noise
        if (index < 3) {
            Timber.tag("Epic").d("Parsing game #$index: $title ($appName)")
            Timber.tag("Epic").d("Full game JSON keys: ${gameObj.keys().asSequence().toList()}")
            Timber.tag("Epic").d("Full game JSON:\n${gameObj.toString(2)}")
        }

        // Use catalog_item_id as the primary key (Epic's unique ID)
        // Fall back to app_name if catalog_item_id is missing
        val id = catalogItemId.ifEmpty { appName }

        // Parse metadata object for images, description, etc.
        val metadata = gameObj.optJSONObject("metadata")
        if (index < 3 && metadata != null) {
            Timber.tag("Epic").d("Metadata keys for $title: ${metadata.keys().asSequence().toList()}")
            Timber.tag("Epic").d("Metadata JSON:\n${metadata.toString(2)}")
        }

        val keyImages = metadata?.optJSONArray("keyImages")
        if (index < 3 && keyImages != null) {
            Timber.tag("Epic").d("keyImages for $title (${keyImages.length()} images):")
            for (i in 0 until keyImages.length()) {
                val img = keyImages.optJSONObject(i)
                Timber.tag("Epic").d("  - type: ${img?.optString("type")}, url: ${img?.optString("url")}")
            }
        }

        // Extract art URLs from keyImages array
        val artCover = extractImageUrl(keyImages, "DieselGameBoxTall")
        val artSquare = extractImageUrl(keyImages, "DieselGameBox")
        val artLogo = extractImageUrl(keyImages, "DieselGameBoxLogo")
        val artPortrait = extractImageUrl(keyImages, "DieselStoreFrontWide")

        val releaseInfo = metadata?.optJSONArray("releaseInfo")
        val releaseDate = if (releaseInfo != null && releaseInfo.length() > 0) {
            releaseInfo.getJSONObject(0).optString("dateAdded", "")
        } else {
            ""
        }

        // Parse genres/tags
        val genresList = parseJsonArray(metadata?.optJSONArray("genres"))
        val tagsList = parseJsonArray(metadata?.optJSONArray("tags"))

        return EpicGame(
            id = id,
            appName = appName,
            title = title,
            namespace = gameObj.optString("namespace", ""),
            developer = gameObj.optString("developer", ""),
            publisher = gameObj.optString("publisher", ""),
            isInstalled = false, // Will be updated by installation detection
            installPath = "",
            platform = "Windows",
            version = gameObj.optString("app_version", ""),
            executable = "",
            installSize = 0L,
            downloadSize = 0L,
            artCover = artCover,
            artSquare = artSquare,
            artLogo = artLogo,
            artPortrait = artPortrait,
            canRunOffline = gameObj.optBoolean("can_run_offline", true),
            requiresOT = gameObj.optBoolean("requires_ot", false),
            cloudSaveEnabled = gameObj.optBoolean("cloud_save_enabled", false),
            saveFolder = gameObj.optString("save_folder", ""),
            thirdPartyManagedApp = gameObj.optString("third_party_managed_app", ""),
            isEAManaged = gameObj.optString("third_party_managed_app", "").lowercase() == "origin",
            isDLC = gameObj.optBoolean("is_dlc", false),
            baseGameAppName = gameObj.optString("base_app_name", ""),
            description = metadata?.optString("description", "") ?: "",
            releaseDate = releaseDate,
            genres = genresList,
            tags = tagsList,
            lastPlayed = 0L,
            playTime = 0L,
        )
    }

    /**
     * Extract image URL from keyImages array by type
     */
    private fun extractImageUrl(keyImages: JSONArray?, imageType: String): String {
        if (keyImages == null) return ""

        for (i in 0 until keyImages.length()) {
            val image = keyImages.optJSONObject(i) ?: continue
            if (image.optString("type") == imageType) {
                return image.optString("url", "")
            }
        }
        return ""
    }

    /**
     * Parse JSON array into list of strings
     */
    private fun parseJsonArray(jsonArray: JSONArray?): List<String> {
        val result = mutableListOf<String>()
        if (jsonArray != null) {
            for (j in 0 until jsonArray.length()) {
                val item = jsonArray.opt(j)
                when (item) {
                    is String -> result.add(item)
                    is JSONObject -> result.add(item.optString("name", item.toString()))
                    else -> result.add(item.toString())
                }
            }
        }
        return result
    }

    /**
     * Get a single game by ID
     */
    suspend fun getGameById(gameId: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by ID: $gameId")
                null
            }
        }
    }

    /**
     * Get a single game by app name (Legendary identifier)
     */
    suspend fun getGameByAppName(appName: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getByAppName(appName)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by app name: $appName")
                null
            }
        }
    }

    /**
     * Insert or update an Epic game in database
     */
    suspend fun insertGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.insert(game)
        }
    }

    /**
     * Update an Epic game in database
     */
    suspend fun updateGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.update(game)
        }
    }

    /**
     * Start background sync (called after login)
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("Epic").i("Starting Epic library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("Epic").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync Epic library in background")
            Result.failure(e)
        }
    }
}
