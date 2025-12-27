package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.db.dao.EpicGameDao
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

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

    private val REFRESH_BATCH_SIZE = 10

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // DarkSiders 2 example for grabbing the details. Requires the Namespace and the catalog ID.
    // https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/091d95ea332843498122beee1a786d71/bulk/items?id=8c04901974534bd0818f747952b0a19b&includeDLCDetails=true&includeMainGameDetails=true

    // WE should just query the asset list first to get a list of assets, then we can query for each game if possible.
    // ! TODO: Convert from grabbing everything, we can make our request since they're not difficult.
    data class EpicAssetList(
        val appName: String,
        val labelName: String,
        val buildVersion: String,
        val catalogItemId: String,
        val namespace: String,
        val assetId: String,
        val metadata: AssetMetadata?,
    )

    data class AssetMetadata(
        val installationPoolId: String,
        val update_type: String,
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
        val dependencies: List<String>?,
    )

    data class ParsedLibraryItem(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val sandboxType: String?,
        val country: String?,
    )

    data class LibraryItemsResponse(
        val responseMetadata: ResponseMetadata,
        val records: List<LibraryItem>?,
    )

    data class ResponseMetadata(
        val nextCursor: String?,
        val stateToken: String?,
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
        val path: String,
    )

    data class EpicCustomAttribute(
        val type: String,
        val value: String,
    )
    data class EpicReleaseInfo(
        val id: String,
        val appId: String,
        val platform: List<String>?,
        val dateAdded: String?,
        val releaseNote: String?,
        val versionTitle: String?,
    )

    data class EpicMainGameItem(
        val id: String,
        val namespace: String,
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
        val lastModifiedDate: String?, // "2025-03-06T07:37:16.597Z",
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
        val mainGameItem: EpicMainGameItem?,
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

            // Get a list of basic info for each game.
            val listResult = fetchLibrary(context)

            if (listResult.isFailure) {
                val error = listResult.exceptionOrNull()
                Timber.tag("Epic").e(error, "Failed to fetch games from Epic: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch Epic library"))
            }

            val games = listResult.getOrNull() ?: emptyList()
            Timber.tag("Epic").i("Successfully fetched ${games.size} games from Epic")

            if (games.isEmpty()) {
                Timber.tag("Epic").w("No games found in Epic library")
                return@withContext Result.success(0)
            }

            // ! Get the game information and store each one in batches
            val epicGames = mutableListOf<EpicGame>()
            for ((index, game) in games.withIndex()) {
                val result = fetchGameInfo(context, game)

                if (result.isSuccess) {
                    val epicGame = result.getOrNull()
                    if (epicGame != null) {
                        epicGames.add(epicGame)
                        Timber.tag("Epic").d("Refreshed Game: ${epicGame.title}")
                    }
                } else {
                    Timber.tag("Epic").w("Epic game ${game.appName} could not be fetched")
                }

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == games.size - 1) {
                    if (epicGames.isNotEmpty()) {
                        epicGameDao.upsertPreservingInstallStatus(epicGames)
                        Timber.tag("Epic").d("Batch inserted ${epicGames.size} games (processed ${index + 1}/${games.size})")
                        epicGames.clear()
                    }
                }
            }

            Timber.tag("Epic").i("Successfully refreshed Epic library")
            Result.success(epicGames.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic library")
            Result.failure(e)
        }
    }

    // We should only pull out the following:

    /**
     * Fetch user's Epic library
     *
     * Calls: GET https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true
     *
     * Returns list of library items with app names, namespaces, and catalog IDs
     */
    suspend fun fetchLibrary(context: Context): Result<List<ParsedLibraryItem>> = withContext(Dispatchers.IO) {
        // Grab the initial library amount. We need the following from each game:
        // namespace
        // appName
        // productId
        // catalogItemId
        // country
        // Initial list is a List<LibraryItem> - We then parse it into a <ParsedLibraryItem>
        try {
            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val gameList = mutableListOf<ParsedLibraryItem>()
            var cursor: String? = null

            // Fetch all pages of library items
            do {
                val url = buildString {
                    append("${EpicConstants.EPIC_LIBRARY_API_URL}?includeMetadata=true")
                    if (cursor != null) {
                        append("&cursor=$cursor")
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                    .get()
                    .build()

                Timber.tag("Epic").d("Fetching Epic library page: cursor=$cursor")

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "Unknown error"
                    Timber.tag("Epic").e("Library fetch failed: ${response.code} - $error")
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $error"))
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Timber.tag("Epic").e("Empty response body from library API")
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val json = JSONObject(body)
                val records = json.optJSONArray("records") ?: JSONArray()

                Timber.tag("Epic").d("Received ${records.length()} library items in this page")

                // Process records and fetch game info for each
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)

                    // Skip items without app name
                    if (!record.has("appName")) {
                        continue
                    }

                    val appName = record.getString("appName")
                    val namespace = record.getString("namespace")
                    val catalogItemId = record.getString("catalogItemId")
                    val sandboxType = record.optString("sandboxType", "")
                    val country = record.optString("country", "")

                    // Skip UE assets, private sandboxes, and broken entries
                    if (namespace == "ue" || sandboxType == "PRIVATE" || appName == "1") {
                        Timber.tag("Epic").d("Skipping $appName (namespace=$namespace, sandbox=$sandboxType)")
                        continue
                    }

                    // Add the basic game to the gameList.
                    val gameInfo = ParsedLibraryItem(appName, namespace, catalogItemId, sandboxType, country)
                    gameList.add(gameInfo)
                }
                // Get cursor for next page - stop if cursor is null or same as previous
                val metadata = json.optJSONObject("responseMetadata")
                val oldCursor = cursor
                cursor = metadata?.optString("nextCursor")?.takeIf { it.isNotEmpty() }
            } while (cursor != null && cursor != oldCursor)

            Timber.tag("Epic").i("Successfully fetched ${gameList.size} games from Epic library")
            Result.success(gameList)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to fetch Epic library")
            Result.failure(e)
        }
    }

    private suspend fun fetchGameInfo(
        context: Context,
        game: ParsedLibraryItem,
    ): Result<EpicGame> = withContext(Dispatchers.IO) {
        try {
            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val country = game.country ?: "US" // Do we really need this?

            // TODO: Investigate if we need &locale=en-US param.
            val url = "${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/${game.namespace}/bulk/items" +
                "?id=${game.catalogItemId}&includeDLCDetails=true&includeMainGameDetails=true" +
                "&country=$country"

            Timber.tag("Epic").d("fetching game info for ${game.appName} - url: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("Failed to fetch game info for ${game.catalogItemId}: ${response.code}")
                return@withContext Result.failure(Exception("Could not fetch game info: ${response.code}"))
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Could not fetch game info for ${game.catalogItemId}"))
            }

            val json = JSONObject(body)
            val gameData = json.optJSONObject(game.catalogItemId)

            if (gameData != null) {
                val epicGame = parseGameFromCatalog(gameData, game.appName)
                return@withContext Result.success(epicGame)
            } else {
                return@withContext Result.failure(Exception("Game data not found in response"))
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching game info for ${game.catalogItemId}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Parse Epic catalog JSON into EpicGame object
     *
     * Catalog structure:
     * {
     *   "id": "catalogItemId",
     *   "namespace": "namespace",
     *   "title": "Game Title",
     *   "description": "Description...",
     *   "keyImages": [...],
     *   "categories": [...],
     *   "developer": "Developer",
     *   "developerDisplayName": "Developer Display Name",
     *   "publisher": "Publisher",
     *   "publisherDisplayName": "Publisher Display Name",
     *   "releaseInfo": [...],
     *   "mainGameItem": { ... },  // Present for DLC
     *   ...
     * }
     */
    private fun parseGameFromCatalog(data: JSONObject, libraryAppName: String): EpicGame {
        val catalogItemId = data.getString("id")
        val namespace = data.getString("namespace")
        val title = data.getString("title")
        val description = data.optString("description", "")

        // Use the appName from library API (passed as parameter)
        // This is the real appName needed for downloads, not the catalog ID
        val appName = libraryAppName

        // Extract images - map to EpicGame's art fields
        val keyImages = data.optJSONArray("keyImages")
        var artCover = "" // DieselGameBoxTall - Tall cover art
        var artSquare = "" // DieselGameBox - Square box art
        var artLogo = "" // DieselGameBoxLogo - Logo image
        var artPortrait = "" // DieselStoreFrontWide - Wide banner

        if (keyImages != null) {
            for (i in 0 until keyImages.length()) {
                val img = keyImages.getJSONObject(i)
                val imgType = img.optString("type")
                val imgUrl = img.optString("url", "")

                when (imgType) {
                    "DieselGameBoxTall" -> artCover = imgUrl
                    "DieselGameBox" -> artSquare = imgUrl
                    "DieselGameBoxLogo" -> artLogo = imgUrl
                    "DieselStoreFrontWide" -> artPortrait = imgUrl
                    "Thumbnail" -> if (artSquare.isEmpty()) artSquare = imgUrl
                }
            }
        }

        // Check if this is DLC
        val isDLC = data.has("mainGameItem")
        val baseGameAppName = if (isDLC) {
            data.optJSONObject("mainGameItem")?.optString("id", "") ?: ""
        } else {
            ""
        }

        // Get developer/publisher
        val developer = data.optString("developerDisplayName", data.optString("developer", ""))
        val publisher = data.optString("publisherDisplayName", data.optString("publisher", ""))

        // Get categories to check for mods
        val categories = data.optJSONArray("categories")
        var isMod = false
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                if (cat.optString("path") == "mods") {
                    isMod = true
                    break
                }
            }
        }

        // Release date - convert to string format
        val releaseInfo = data.optJSONArray("releaseInfo")
        var releaseDate = ""
        if (releaseInfo != null && releaseInfo.length() > 0) {
            val release = releaseInfo.getJSONObject(0)
            releaseDate = release.optString("dateAdded", "")
        }

        // Parse genres/tags from categories
        val genresList = mutableListOf<String>()
        val tagsList = mutableListOf<String>()
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                val path = cat.optString("path", "")
                if (path.startsWith("games/")) {
                    genresList.add(path.removePrefix("games/"))
                } else if (path.isNotEmpty() && path != "mods") {
                    tagsList.add(path)
                }
            }
        }

        return EpicGame(
            id = catalogItemId,
            appName = appName,
            title = title,
            namespace = namespace,
            developer = developer,
            publisher = publisher,
            description = description,
            artCover = artCover,
            artSquare = artSquare,
            artLogo = artLogo,
            artPortrait = artPortrait,
            isDLC = isDLC,
            baseGameAppName = baseGameAppName,
            releaseDate = releaseDate,
            genres = genresList,
            tags = tagsList,
            isInstalled = false, // Will be updated from local database
            installPath = "",
            platform = "Windows",
            version = "",
            executable = "",
            installSize = 0,
            downloadSize = 0,
            canRunOffline = false, // Unknown from catalog API, will need manifest
            requiresOT = false,
            cloudSaveEnabled = false,
            saveFolder = "",
            thirdPartyManagedApp = "",
            isEAManaged = false,
            lastPlayed = 0,
            playTime = 0,
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

    suspend fun insertGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.insert(game)
        }
    }

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
     *
     * This function implements both Python (via legendary) and Kotlin parsing
     * and compares the results to validate the Kotlin implementation
     */
    suspend fun fetchInstallSize(context: Context, appName: String): Long = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").d("Fetching install size for $appName via manifest (comparing Python vs Kotlin)...")

            // ========== PYTHON IMPLEMENTATION (via legendary) ==========
            val pythonStartTime = System.currentTimeMillis()
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
            val pythonResult = EpicPythonBridge.executePythonCode(context, pythonCode)
            val pythonTime = System.currentTimeMillis() - pythonStartTime

            var pythonSize = 0L
            if (pythonResult.isSuccess) {
                val output = pythonResult.getOrNull() ?: ""
                val lines = output.trim().lines()
                if (lines.isNotEmpty()) {
                    val lastLine = lines.last()
                    try {
                        val json = JSONObject(lastLine.trim())
                        if (json.has("error")) {
                            Timber.tag("Epic").w("Python manifest fetch error: ${json.getString("error")}")
                        } else {
                            pythonSize = json.optLong("install_size", 0L)
                            Timber.tag("Epic").d("Python result: $pythonSize bytes in ${pythonTime}ms")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").w(e, "Failed to parse Python output")
                    }
                }
            } else {
                Timber.tag("Epic").w(pythonResult.exceptionOrNull(), "Python manifest fetch failed")
            }

            // ========== KOTLIN IMPLEMENTATION (using Epic API + Kotlin parser) ==========
            val kotlinStartTime = System.currentTimeMillis()
            var kotlinSize = 0L

            try {
                // Get the game info to get namespace and catalogItemId
                val game = getGameByAppName(appName)
                if (game == null) {
                    Timber.tag("Epic").w("Game not found in database: $appName")
                } else {
                    // Get credentials
                    val credentials = EpicAuthManager.getStoredCredentials(context)
                    if (credentials.isFailure) {
                        Timber.tag("Epic").w("No credentials for Kotlin manifest fetch")
                    } else {
                        val accessToken = credentials.getOrNull()?.accessToken
                        if (accessToken.isNullOrEmpty()) {
                            Timber.tag("Epic").w("No access token for Kotlin manifest fetch")
                        } else {
                            // Fetch manifest URL from Epic API
                            val manifestUrl = "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                                    "/Windows/namespace/${game.namespace}/catalogItem/${game.id}/app" +
                                    "/${game.appName}/label/Live"

                            Timber.tag("Epic").d("Fetching manifest from: $manifestUrl")

                            val request = Request.Builder()
                                .url(manifestUrl)
                                .header("Authorization", "Bearer $accessToken")
                                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                                .get()
                                .build()

                            val response = httpClient.newCall(request).execute()

                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrEmpty()) {
                                    val manifestJson = JSONObject(body)
                                    val elements = manifestJson.optJSONArray("elements")

                                    if (elements != null && elements.length() > 0) {
                                        val element = elements.getJSONObject(0)
                                        val manifests = element.optJSONArray("manifests")

                                        if (manifests != null && manifests.length() > 0) {
                                            // Download the actual manifest binary
                                            val manifestUri = manifests.getJSONObject(0).getString("uri")

                                            Timber.tag("Epic").d("Downloading manifest from: $manifestUri")

                                            val manifestRequest = Request.Builder()
                                                .url(manifestUri)
                                                .get()
                                                .build()

                                            val manifestResponse = httpClient.newCall(manifestRequest).execute()

                                            if (manifestResponse.isSuccessful) {
                                                val manifestBytes = manifestResponse.body?.bytes()

                                                if (manifestBytes != null) {
                                                    // Parse with Kotlin parser
                                                    val manifest = app.gamenative.service.epic.manifest.EpicManifest.readAll(manifestBytes)

                                                    // Calculate install size
                                                    kotlinSize = manifest.fileManifestList?.elements?.sumOf { it.fileSize } ?: 0L

                                                    val kotlinTime = System.currentTimeMillis() - kotlinStartTime
                                                    Timber.tag("Epic").d("Kotlin result: $kotlinSize bytes in ${kotlinTime}ms")
                                                } else {
                                                    Timber.tag("Epic").w("Empty manifest bytes from Kotlin download")
                                                }
                                            } else {
                                                Timber.tag("Epic").w("Failed to download manifest binary: ${manifestResponse.code}")
                                            }
                                        } else {
                                            Timber.tag("Epic").w("No manifests in API response")
                                        }
                                    } else {
                                        Timber.tag("Epic").w("No elements in manifest API response")
                                    }
                                } else {
                                    Timber.tag("Epic").w("Empty manifest API response")
                                }
                            } else {
                                Timber.tag("Epic").w("Manifest API request failed: ${response.code}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("Epic").w(e, "Kotlin manifest fetch failed")
            }

            // ========== COMPARISON ==========
            if (pythonSize > 0 && kotlinSize > 0) {
                val diff = kotlin.math.abs(pythonSize - kotlinSize)
                val percentDiff = (diff.toDouble() / pythonSize.toDouble()) * 100.0

                if (diff == 0L) {
                    Timber.tag("Epic").i("✓ MATCH: Python and Kotlin results are identical!")
                    Timber.tag("Epic").i("  Size: $pythonSize bytes (${pythonSize / 1_000_000_000.0} GB)")
                } else {
                    Timber.tag("Epic").w("⚠ MISMATCH: Python vs Kotlin differ by $diff bytes (${"%.2f".format(percentDiff)}%)")
                    Timber.tag("Epic").w("  Python: $pythonSize bytes (${pythonSize / 1_000_000_000.0} GB)")
                    Timber.tag("Epic").w("  Kotlin: $kotlinSize bytes (${kotlinSize / 1_000_000_000.0} GB)")
                }
                Timber.tag("Epic").d("Performance: Python ${pythonTime}ms vs Kotlin ${System.currentTimeMillis() - kotlinStartTime}ms")
            } else if (pythonSize > 0) {
                Timber.tag("Epic").i("Using Python result: $pythonSize bytes (Kotlin failed)")
                return@withContext pythonSize
            } else if (kotlinSize > 0) {
                Timber.tag("Epic").i("Using Kotlin result: $kotlinSize bytes (Python failed)")
                return@withContext kotlinSize
            } else {
                Timber.tag("Epic").e("Both Python and Kotlin manifest fetch failed")
                return@withContext 0L
            }

            // Prefer Kotlin result if both succeeded, otherwise use whichever worked
            return@withContext if (kotlinSize > 0) kotlinSize else pythonSize

        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching install size for $appName")
            0L
        }
    }
}
