package app.gamenative.service.gog

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.GameSource
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SyncResult
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Data class to hold size information from gogdl info command
 */
data class GameSizeInfo(
    val downloadSize: Long,
    val diskSize: Long
)

/**
 * Unified manager for GOG game and library operations.
 *
 * Responsibilities:
 * - Database CRUD for GOG games
 * - Library syncing from GOG API
 * - Game downloads and installation
 * - Installation verification
 * - Executable discovery
 * - Wine launch commands
 * - File system operations
 *
 * Uses GOGPythonBridge for all GOGDL command execution.
 * Uses GOGAuthManager for authentication checks.
 */
@Singleton
class GOGManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
) {

    // Simple cache for download sizes
    private val downloadSizeCache = mutableMapOf<String, String>()

    // ==========================================================================
    // DATABASE OPERATIONS
    // ==========================================================================

    /**
     * Get a GOG game by ID from database
     */
    suspend fun getGameById(gameId: String): GOGGame? {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get GOG game by ID: $gameId")
                null
            }
        }
    }

    /**
     * Insert or update a GOG game in database
     * Uses REPLACE strategy, so will update if exists
     */
    suspend fun insertGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.insert(game)
        }
    }

    /**
     * Update a GOG game in database
     */
    suspend fun updateGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.update(game)
        }
    }

    /**
     * Get all GOG games as a Flow
     */
    fun getAllGames(): Flow<List<GOGGame>> {
        return gogGameDao.getAll()
    }

    // ==========================================================================
    // LIBRARY SYNCING
    // ==========================================================================

    /**
     * Start background library sync
     * Progressively fetches and updates the GOG library in the background
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("GOG").i("Starting GOG library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("GOG").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync GOG library in background")
            Result.failure(e)
        }
    }

    /**
     * Refresh the entire library (called manually by user)
     * Fetches all games from GOG API and updates the database
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with GOG")
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            Timber.tag("GOG").i("Refreshing GOG library from GOG API...")

            // Fetch games from GOG via GOGDL Python backend
            val listResult = listGames(context)

            if (listResult.isFailure) {
                val error = listResult.exceptionOrNull()
                Timber.e(error, "Failed to fetch games from GOG: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch GOG library"))
            }

            val games = listResult.getOrNull() ?: emptyList()
            Timber.tag("GOG").i("Successfully fetched ${games.size} games from GOG")

            if (games.isEmpty()) {
                Timber.w("No games found in GOG library")
                return@withContext Result.success(0)
            }

            // Update database using upsert to preserve install status
            Timber.d("Upserting ${games.size} games to database...")
            gogGameDao.upsertPreservingInstallStatus(games)

            Timber.tag("GOG").i("Successfully refreshed GOG library with ${games.size} games")
            Result.success(games.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh GOG library")
            Result.failure(e)
        }
    }

    /**
     * Fetch the user's GOG library (list of owned games)
     * Returns a list of GOGGame objects with basic metadata
     */
    private suspend fun listGames(context: Context): Result<List<GOGGame>> {
        return try {
            Timber.i("Fetching GOG library via GOGDL...")
            val authConfigPath = GOGAuthManager.getAuthConfigPath(context)

            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.e("Cannot list games: not authenticated")
                return Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "list", "--pretty")

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.e(error, "Failed to fetch GOG library: ${error?.message}")
                return Result.failure(error ?: Exception("Failed to fetch GOG library"))
            }

            val output = result.getOrNull() ?: ""
            parseGamesFromJson(output)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error while fetching GOG library")
            Result.failure(e)
        }
    }

    /**
     * Parse games from GOGDL JSON output
     */
    private fun parseGamesFromJson(output: String): Result<List<GOGGame>> {
        return try {
            val gamesArray = org.json.JSONArray(output.trim())
            val games = mutableListOf<GOGGame>()

            for (i in 0 until gamesArray.length()) {
                try {
                    val gameObj = gamesArray.getJSONObject(i)
                    games.add(parseGameObject(gameObj))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse game at index $i, skipping")
                }
            }

            Timber.i("Successfully parsed ${games.size} games from GOG library")
            Result.success(games)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse GOG library JSON")
            Result.failure(Exception("Failed to parse GOG library: ${e.message}", e))
        }
    }

    /**
     * Parse a single game object from JSON
     */
    private fun parseGameObject(gameObj: JSONObject): GOGGame {
        val genresList = parseJsonArray(gameObj.optJSONArray("genres"))
        val languagesList = parseJsonArray(gameObj.optJSONArray("languages"))

        return GOGGame(
            id = gameObj.optString("id", ""),
            title = gameObj.optString("title", "Unknown Game"),
            slug = gameObj.optString("slug", ""),
            imageUrl = gameObj.optString("imageUrl", ""),
            iconUrl = gameObj.optString("iconUrl", ""),
            description = gameObj.optString("description", ""),
            releaseDate = gameObj.optString("releaseDate", ""),
            developer = gameObj.optString("developer", ""),
            publisher = gameObj.optString("publisher", ""),
            genres = genresList,
            languages = languagesList,
            downloadSize = gameObj.optLong("downloadSize", 0L),
            installSize = 0L,
            isInstalled = false,
            installPath = "",
            lastPlayed = 0L,
            playTime = 0L,
        )
    }

    /**
     * Parse a JSON array into a list of strings
     */
    private fun parseJsonArray(jsonArray: org.json.JSONArray?): List<String> {
        val result = mutableListOf<String>()
        if (jsonArray != null) {
            for (j in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(j))
            }
        }
        return result
    }

    /**
     * Fetch a single game's metadata from GOG API and insert it into the database
     */
    suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
        return try {
            Timber.i("Fetching single game data for gameId: $gameId")
            val authConfigPath = GOGAuthManager.getAuthConfigPath(context)

            if (!GOGAuthManager.hasStoredCredentials(context)) {
                return Result.failure(Exception("Not authenticated"))
            }

            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "list", "--pretty")

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Failed to fetch game data"))
            }

            val output = result.getOrNull() ?: ""
            val gamesArray = org.json.JSONArray(output.trim())

            // Find the game with matching ID
            for (i in 0 until gamesArray.length()) {
                val gameObj = gamesArray.getJSONObject(i)
                if (gameObj.optString("id", "") == gameId) {
                    val game = parseGameObject(gameObj)
                    insertGame(game)
                    Timber.i("Successfully fetched and inserted game: ${game.title}")
                    return Result.success(game)
                }
            }

            Timber.w("Game $gameId not found in GOG library")
            Result.success(null)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching single game data for $gameId")
            Result.failure(e)
        }
    }

    // ==========================================================================
    // DOWNLOAD & INSTALLATION
    // ==========================================================================

    /**
     * Download a GOG game with full progress tracking
     */
    suspend fun downloadGame(context: Context, gameId: String, installPath: String, downloadInfo: DownloadInfo): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.i("[Download] Starting GOGDL download for game $gameId to $installPath")

                val installDir = File(installPath)
                if (!installDir.exists()) {
                    Timber.d("[Download] Creating install directory: $installPath")
                    installDir.mkdirs()
                }

                // Create support directory for redistributables
                val supportDir = File(installDir.parentFile, "gog-support")
                if (!supportDir.exists()) {
                    Timber.d("[Download] Creating support directory: ${supportDir.absolutePath}")
                    supportDir.mkdirs()
                }

                val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                val numericGameId = ContainerUtils.extractGameIdFromContainerId(gameId).toString()

                Timber.d("[Download] Calling GOGPythonBridge with gameId=$numericGameId, authConfig=$authConfigPath")

                // Initialize progress
                downloadInfo.setProgress(0.0f)

                val result = GOGPythonBridge.executeCommandWithCallback(
                    downloadInfo,
                    "--auth-config-path", authConfigPath,
                    "download", numericGameId,
                    "--platform", "windows",
                    "--path", installPath,
                    "--support", supportDir.absolutePath,
                    "--skip-dlcs",
                    "--lang", "en-US",
                    "--max-workers", "1",
                )

                if (result.isSuccess) {
                    downloadInfo.setProgress(1.0f)
                    Timber.i("[Download] GOGDL download completed successfully for game $gameId")
                    Result.success(Unit)
                } else {
                    downloadInfo.setProgress(-1.0f)
                    val error = result.exceptionOrNull()
                    Timber.e(error, "[Download] GOGDL download failed for game $gameId")
                    Result.failure(error ?: Exception("Download failed"))
                }
            } catch (e: Exception) {
                Timber.e(e, "[Download] Exception during download for game $gameId")
                downloadInfo.setProgress(-1.0f)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a GOG game
     */
    fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        try {
            val gameId = libraryItem.gameId.toString()
            val installPath = getGameInstallPath(context, gameId, libraryItem.name)
            val installDir = File(installPath)

            // Delete the manifest file
            val manifestPath = File(context.filesDir, "manifests/$gameId")
            if (manifestPath.exists()) {
                manifestPath.delete()
                Timber.i("Deleted manifest file for game $gameId")
            }

            if (installDir.exists()) {
                val success = installDir.deleteRecursively()
                if (success) {
                    Timber.i("Successfully deleted game directory: $installPath")

                    // Remove all markers
                    val appDirPath = getAppDirPath(libraryItem.appId)
                    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    // Update database
                    val game = runBlocking { getGameById(gameId) }
                    if (game != null) {
                        val updatedGame = game.copy(isInstalled = false, installPath = "")
                        runBlocking { gogGameDao.update(updatedGame) }
                    }

                    return Result.success(Unit)
                } else {
                    return Result.failure(Exception("Failed to delete game directory"))
                }
            } else {
                Timber.w("GOG game directory doesn't exist: $installPath")
                // Clean up markers anyway
                val appDirPath = getAppDirPath(libraryItem.appId)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                // Update database
                val game = runBlocking { getGameById(gameId) }
                if (game != null) {
                    val updatedGame = game.copy(isInstalled = false, installPath = "")
                    runBlocking { gogGameDao.update(updatedGame) }
                }

                return Result.success(Unit)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete GOG game ${libraryItem.gameId}")
            return Result.failure(e)
        }
    }

    // ==========================================================================
    // INSTALLATION STATUS & VERIFICATION
    // ==========================================================================

    /**
     * Check if a GOG game is installed
     */
    fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        try {
            val appDirPath = getAppDirPath(libraryItem.appId)

            // Use marker-based approach
            val isDownloadComplete = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            val isInstalled = isDownloadComplete && !isDownloadInProgress

            // Update database if status changed
            val gameId = libraryItem.gameId.toString()
            val game = runBlocking { getGameById(gameId) }
            if (game != null && isInstalled != game.isInstalled) {
                val installPath = if (isInstalled) getGameInstallPath(context, gameId, libraryItem.name) else ""
                val updatedGame = game.copy(isInstalled = isInstalled, installPath = installPath)
                runBlocking { gogGameDao.update(updatedGame) }
            }

            return isInstalled
        } catch (e: Exception) {
            Timber.e(e, "Error checking if GOG game is installed")
            return false
        }
    }

    /**
     * Verify that a GOG game installation is valid and complete
     */
    fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
        val game = runBlocking { getGameById(gameId) }
        val installPath = game?.installPath

        if (installPath == null || !game.isInstalled) {
            return Pair(false, "Game not marked as installed in database")
        }

        val installDir = File(installPath)
        if (!installDir.exists()) {
            return Pair(false, "Install directory not found: $installPath")
        }

        if (!installDir.isDirectory) {
            return Pair(false, "Install path is not a directory")
        }

        val contents = installDir.listFiles()
        if (contents == null || contents.isEmpty()) {
            return Pair(false, "Install directory is empty")
        }

        Timber.i("Installation verified for game $gameId at $installPath")
        return Pair(true, null)
    }

    /**
     * Check if game has a partial download
     */
    fun hasPartialDownload(libraryItem: LibraryItem): Boolean {
        try {
            val appDirPath = getAppDirPath(libraryItem.appId)

            val isDownloadInProgress = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            val isDownloadComplete = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            if (isDownloadInProgress) {
                return true
            }

            if (!isDownloadComplete) {
                val installPath = GOGConstants.getGameInstallPath(libraryItem.name)
                val installDir = File(installPath)
                return installDir.exists() && installDir.listFiles()?.isNotEmpty() == true
            }

            return false
        } catch (e: Exception) {
            Timber.w(e, "Error checking partial download status")
            return false
        }
    }

    // ==========================================================================
    // EXECUTABLE DISCOVERY & LAUNCH
    // ==========================================================================

    /**
     * Get the executable path for an installed GOG game
     */
    suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId.toString()
        try {
            val game = getGameById(gameId) ?: return@withContext ""
            val installPath = getGameInstallPath(context, game.id, game.title)

            // Try V2 structure first (game_$gameId subdirectory)
            val v2GameDir = File(installPath, "game_$gameId")
            if (v2GameDir.exists()) {
                return@withContext getGameExecutable(installPath, v2GameDir)
            }

            // Try V1 structure
            val installDirFile = File(installPath)
            val subdirs = installDirFile.listFiles()?.filter {
                it.isDirectory && it.name != "saves"
            } ?: emptyList()

            if (subdirs.isNotEmpty()) {
                return@withContext getGameExecutable(installPath, subdirs.first())
            }

            ""
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for GOG game $gameId")
            ""
        }
    }

    private fun getGameExecutable(installPath: String, gameDir: File): String {
        val mainExe = getMainExecutableFromGOGInfo(gameDir, installPath)
        if (mainExe.isNotEmpty()) {
            Timber.i("Found GOG game executable from info file: $mainExe")
            return mainExe
        }
        Timber.e("Failed to find executable from GOG info file in: ${gameDir.absolutePath}")
        return ""
    }

    private fun getMainExecutableFromGOGInfo(gameDir: File, installPath: String): String {
        val infoFile = gameDir.listFiles()?.find {
            it.isFile && it.name.startsWith("goggame-") && it.name.endsWith(".info")
        } ?: throw Exception("GOG info file not found")

        val content = infoFile.readText()
        val jsonObject = JSONObject(content)

        if (!jsonObject.has("playTasks")) {
            throw Exception("playTasks array not found in info file")
        }

        val playTasks = jsonObject.getJSONArray("playTasks")
        for (i in 0 until playTasks.length()) {
            val task = playTasks.getJSONObject(i)
            if (task.has("isPrimary") && task.getBoolean("isPrimary")) {
                val executablePath = task.getString("path")
                val actualExeFile = gameDir.listFiles()?.find {
                    it.name.equals(executablePath, ignoreCase = true)
                }
                if (actualExeFile != null && actualExeFile.exists()) {
                    return "${gameDir.name}/${actualExeFile.name}"
                }
                break
            }
        }
        return ""
    }

    /**
     * Get Wine start command for launching a game
     */
    fun getWineStartCommand(
        context: Context,
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId)

        // Verify installation
        val (isValid, errorMessage) = verifyInstallation(gameId.toString())
        if (!isValid) {
            Timber.e("Installation verification failed: $errorMessage")
            return "\"explorer.exe\""
        }

        val game = runBlocking { getGameById(gameId.toString()) }
        if (game == null) {
            Timber.e("Game not found for ID: $gameId")
            return "\"explorer.exe\""
        }

        val gameInstallPath = getGameInstallPath(context, gameId.toString(), game.title)
        val gameDir = File(gameInstallPath)

        if (!gameDir.exists()) {
            Timber.e("Game directory does not exist: $gameInstallPath")
            return "\"explorer.exe\""
        }

        val executablePath = runBlocking { getInstalledExe(context, libraryItem) }
        if (executablePath.isEmpty()) {
            Timber.w("No executable found, opening file manager")
            return "\"explorer.exe\""
        }

        // Map game directory
        val gogDriveLetter = ContainerUtils.ensureGOGGameDirectoryMapped(context, container, gameInstallPath)
        if (gogDriveLetter == null) {
            Timber.e("Failed to map GOG game directory")
            return "\"explorer.exe\""
        }

        val gameInstallDir = File(gameInstallPath)
        val execFile = File(gameInstallPath, executablePath)
        val relativePath = execFile.relativeTo(gameInstallDir).path.replace('/', '\\')
        val windowsPath = "$gogDriveLetter:\\$relativePath"

        // Set working directory
        val execWorkingDir = execFile.parentFile
        if (execWorkingDir != null) {
            guestProgramLauncherComponent.workingDir = execWorkingDir
            envVars.put("WINEPATH", "$gogDriveLetter:\\")
        } else {
            guestProgramLauncherComponent.workingDir = gameDir
        }

        Timber.i("GOG Wine command: \"$windowsPath\"")
        return "\"$windowsPath\""
    }

    /**
     * Launch game with save sync (stub - cloud saves not implemented)
     */
    suspend fun launchGameWithSaveSync(
        context: Context,
        libraryItem: LibraryItem,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo = withContext(Dispatchers.IO) {
        try {
            Timber.i("GOG game launch for ${libraryItem.name} (cloud save sync disabled)")
            // TODO: Implement GOG cloud save sync
            PostSyncInfo(SyncResult.Success)
        } catch (e: Exception) {
            Timber.e(e, "GOG game launch exception")
            PostSyncInfo(SyncResult.UnknownFail)
        }
    }

    // ==========================================================================
    // FILE SYSTEM & PATHS
    // ==========================================================================

    /**
     * Get app directory path for a game
     */
    fun getAppDirPath(appId: String): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        val game = runBlocking { getGameById(gameId.toString()) }

        if (game != null) {
            return GOGConstants.getGameInstallPath(game.title)
        }

        Timber.w("Could not find game for appId $appId")
        return GOGConstants.defaultGOGGamesPath
    }

    /**
     * Get install path for a specific GOG game
     */
    fun getGameInstallPath(context: Context, gameId: String, gameTitle: String): String {
        return GOGConstants.getGameInstallPath(gameTitle)
    }

    /**
     * Get disk size of installed game
     */
    suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val installPath = getGameInstallPath(context, libraryItem.appId, libraryItem.name)
        val folderSize = StorageUtils.getFolderSize(installPath)
        StorageUtils.formatBinarySize(folderSize)
    }

    /**
     * Get download size for a game
     */
    suspend fun getDownloadSize(libraryItem: LibraryItem): String {
        val gameId = libraryItem.gameId.toString()

        // Return cached result if available
        downloadSizeCache[gameId]?.let { return it }

        // TODO: Implement via GOGPythonBridge
        val formattedSize = "Unknown"
        downloadSizeCache[gameId] = formattedSize
        return formattedSize
    }

    /**
     * Get cached download size if available
     */
    fun getCachedDownloadSize(gameId: String): String? {
        return downloadSizeCache[gameId]
    }

    // ==========================================================================
    // UTILITY & CONVERSION
    // ==========================================================================

    /**
     * Create a LibraryItem from GOG game data
     */
    fun createLibraryItem(appId: String, gameId: String, context: Context): LibraryItem {
        val gogGame = runBlocking { getGameById(gameId) }
        return LibraryItem(
            appId = appId,
            name = gogGame?.title ?: "Unknown GOG Game",
            iconHash = gogGame?.iconUrl ?: "",
            gameSource = GameSource.GOG,
        )
    }

    /**
     * Get store URL for game
     */
    fun getStoreUrl(libraryItem: LibraryItem): Uri {
        val gogGame = runBlocking { getGameById(libraryItem.gameId.toString()) }
        val slug = gogGame?.slug ?: ""
        return "https://www.gog.com/en/game/$slug".toUri()
    }

    /**
     * Convert GOGGame to SteamApp format for UI compatibility
     */
    fun convertToSteamApp(gogGame: GOGGame): SteamApp {
        val releaseTimestamp = parseReleaseDate(gogGame.releaseDate)
        val appId = gogGame.id.toIntOrNull() ?: gogGame.id.hashCode()

        return SteamApp(
            id = appId,
            name = gogGame.title,
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
            releaseDate = releaseTimestamp,
            developer = gogGame.developer.takeIf { it.isNotEmpty() } ?: "Unknown Developer",
            publisher = gogGame.publisher.takeIf { it.isNotEmpty() } ?: "Unknown Publisher",
            controllerSupport = ControllerSupport.none,
            logoHash = "",
            iconHash = "",
            clientIconHash = "",
            installDir = gogGame.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim(),
        )
    }

    private fun parseReleaseDate(releaseDate: String): Long {
        if (releaseDate.isEmpty()) return 0L

        val formats = arrayOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MMM dd, yyyy", Locale.US),
        )

        for (format in formats) {
            try {
                return format.parse(releaseDate)?.time ?: 0L
            } catch (e: Exception) {
                // Try next format
            }
        }

        return 0L
    }

    /**
     * Check if game is valid to download
     */
    fun isValidToDownload(library: LibraryItem): Boolean {
        return true // GOG games are always downloadable if owned
    }

    /**
     * Check if update is pending for a game (stub)
     */
    suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean {
        return false // Not implemented yet
    }

    /**
     * Run before launch (no-op for GOG games)
     */
    fun runBeforeLaunch(context: Context, libraryItem: LibraryItem) {
        // Don't run anything before launch for GOG games
    }
}
