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
 *
 * TODO: Download Game, Uninstall Game, Ensure we can track Progress via STDOUT parsing
 * TODO: Launching games using the different execution params that we store.
 *
 * | Install | `legendary install <APPNAME> --base-path <PATH> --platform Windows` | Progress output |
 * | Launch | `legendary launch <APPNAME> --offline --skip-version-check` | Launch output |
 * TODO: We should see if we need to put any disclaimers around online games not being supported and THEY BETTER NOT TRY FORTNITE.
 */


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

    // DarkSiders 2 example for grabbing the details. Requires the Namespace and the catalog ID.
    // https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/091d95ea332843498122beee1a786d71/bulk/items?id=8c04901974534bd0818f747952b0a19b&includeDLCDetails=true&includeMainGameDetails=true


    // WE should just query the asset list first to get a list of assets, then we can query for each game if possible.
    //! TODO: Convert from grabbing everything, we can make our request since they're not difficult.
    data class EpicAssetList(
       val appName: String,
       val labelName: String,
       val buildVersion: String,
       val catalogItemId: String,
       val namespace: String,
       val assetId: String,
       val metadata: AssetMetadata?
    )

    data class AssetMetadata(
        val installationPoolId: String,
        val update_type: String
    )

    data class LibraryItem(
        val namespace: String, // We also need this to construct the URL for grabbing the game information....
        val catalogItemId: String?, // Catalogue Item ID is the one we use to grab the game details -> It's the most important ID.
        val appName: String,
        val country: String?,
        val platform: List<String>?,
        val productId: String,
        val sandboxName: String,
        val sandboxType: String,
        val recordType: String?,
        val acquisitionDate: String?,
        val dependencies: List<String>?
    )

    data class LibraryItemsResponse(
        val responseMetadata: ResponseMetadata,
        val records: List<LibraryItem>?
    )

    data class ResponseMetadata(
        val nextCursor: String?,
        val stateToken: String?
    )

    // Usually consists of DieselGameBox and DieselGameBoxTall that we can use. We should use DieselGameBoxtall for capsule & the other for everything else.
    data class EpicKeyImage(
        val type: String,
        val url: String, // Full URL of the game art.
        val md5: String?,
        val width: Int?,
        val height: Int?,
        val size: Int?,
        val uploadedDate: String?, // "2019-12-19T21:54:10.003Z"
    )

    data class EpicCategory(
        val path: String
    )

    data class EpicCustomAttribute(
        val type: String,
        val value: String
    )
    data class EpicReleaseInfo(
        val id: String,
        val appId: String,
        val platform: List<String>?,
        val dateAdded: String?,
        val releaseNote: String?,
        val versionTitle: String?
    )

    data class EpicMainGameItem(
        val id: String,
        val namespace: String
    )

    data class GameInfoResponse(
        val id: String,
        val title: String,
        val description: String,
        val keyImages: List<EpicKeyImage>,
        val categories: List<EpicCategory>,
        val namespace: String,
        val status: String?,
        val creationDate: String?, // "2025-03-04T08:39:07.841Z",
        val lastModifiedDate: String?, //"2025-03-06T07:37:16.597Z",
        val customAttributes: EpicCustomAttribute?,
        val entitlementName: String?,
        val entitlementType: String?,
        val itemType: String?,
        val releaseInfo: EpicReleaseInfo,
        val developer: String,
        val developerId: String?,
        val eulaIds: List<String>?,
        val endOfSupport: Boolean?,
        val mainGameItemList: List<String>?,
        val ageGatings: Map<String, Int>?,
        val applicationId: String?,
        val baseAppName: String?,
        val baseProductId: String?,
        val mainGameItem: EpicMainGameItem?
    )


    // GET LIBRARY ITEMS
    // https://library-service.live.use1a.on.epicgames.com/library/api/public/items


    // Get manifest DARKSIDERS 2 EXAMPLE : https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace/091d95ea332843498122beee1a786d71/catalogItem/8c04901974534bd0818f747952b0a19b/app/Hoki/label/Live




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

    /**
     * Fetch install size for a game by downloading its manifest
     * Manifest is small (~500KB-1MB) and contains all file metadata
     * Returns size in bytes, or 0 if failed
     */
    suspend fun fetchInstallSize(context: Context, appName: String): Long = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").d("Fetching install size for $appName via manifest...")

            val pythonCode = """
import json
from legendary.core import LegendaryCore

try:
    print(json.dumps({"status": "initializing"}))
    core = LegendaryCore()

    # Authenticate with stored credentials
    print(json.dumps({"status": "authenticating"}))
    if not core.login():
        print(json.dumps({"error": "Authentication failed"}))
    else:
        print(json.dumps({"status": "getting_game_metadata"}))

        game = core.get_game('$appName')
        if not game:
            print(json.dumps({"error": "Game not found"}))
        else:
            print(json.dumps({"status": "downloading_manifest", "game_title": game.app_title}))

            # Download manifest (small file with metadata)
            manifest_data, _ = core.get_cdn_manifest(game, platform='Windows')
            print(json.dumps({"status": "parsing_manifest"}))

            manifest = core.load_manifest(manifest_data)
            print(json.dumps({"status": "calculating_size"}))

            # Sum all file sizes from manifest
            install_size = sum(fm.file_size for fm in manifest.file_manifest_list.elements)

            print(json.dumps({"status": "complete", "install_size": install_size}))
except Exception as e:
    import traceback
    print(json.dumps({"error": str(e), "traceback": traceback.format_exc()}))
"""

            Timber.tag("Epic").d("Executing Python code to fetch manifest...")
            val result = EpicPythonBridge.executePythonCode(context, pythonCode)
            Timber.tag("Epic").d("Python execution completed: success=${result.isSuccess}")

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                Timber.tag("Epic").d("Python output: $output")

                // Parse last line of output (final status)
                val lines = output.trim().lines()
                if (lines.isEmpty()) {
                    Timber.e("Empty output from manifest fetch")
                    return@withContext 0L
                }

                val lastLine = lines.last()
                Timber.tag("Epic").d("Parsing final output line: $lastLine")
                val json = JSONObject(lastLine.trim())

                if (json.has("error")) {
                    val error = json.getString("error")
                    val traceback = json.optString("traceback", "No traceback")
                    Timber.e("Failed to fetch install size: $error\nTraceback: $traceback")
                    return@withContext 0L
                }

                val installSize = json.optLong("install_size", 0L)
                Timber.tag("Epic").i("Fetched install size for $appName: $installSize bytes (${installSize / 1_000_000_000.0} GB)")
                return@withContext installSize
            } else {
                Timber.e(result.exceptionOrNull(), "Failed to execute manifest fetch for $appName")
                return@withContext 0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching install size for $appName")
            0L
        }
    }
}
