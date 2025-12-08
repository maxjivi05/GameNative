package app.gamenative.service.gog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.room.Room
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.DATABASE_NAME
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.util.function.Function

/**
 * Data class to hold metadata extracted from GOG GamesDB
 */
private data class GameMetadata(
    val developer: String = "Unknown Developer",
    val publisher: String = "Unknown Publisher",
    val title: String? = null,
    val description: String? = null
)

/**
 * Data class to hold size information from gogdl info command
 */
data class GameSizeInfo(
    val downloadSize: Long,
    val diskSize: Long
)

/**
 * Progress callback that Python code can invoke to report download progress
 */
class ProgressCallback(private val downloadInfo: DownloadInfo) {
    @JvmOverloads
    fun update(percent: Float = 0f, downloadedMB: Float = 0f, totalMB: Float = 0f, downloadSpeedMBps: Float = 0f, eta: String = "") {
        try {
            val progress = (percent / 100.0f).coerceIn(0.0f, 1.0f)
            downloadInfo.setProgress(progress)

            if (percent > 0f) {
                Timber.d("Download progress: %.1f%% (%.1f/%.1f MB) Speed: %.2f MB/s ETA: %s",
                    percent, downloadedMB, totalMB, downloadSpeedMBps, eta)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error updating download progress")
        }
    }
}

class GOGService : Service() {

    companion object {
        private var instance: GOGService? = null
        private var appContext: Context? = null
        private var isInitialized = false
        private var httpClient: OkHttpClient? = null
        private var python: Python? = null

        // Constants
        private const val GOG_CLIENT_ID = "46899977096215655"

        // Add sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, GOGService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        fun setHttpClient(client: OkHttpClient) {
            httpClient = client
        }

        /**
         * Initialize the GOG service with Chaquopy Python
         */
        fun initialize(context: Context): Boolean {
            if (isInitialized) return true

            try {
                // Store the application context
                appContext = context.applicationContext

                Timber.i("Initializing GOG service with Chaquopy...")

                // Initialize Python if not already started
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context))
                }
                python = Python.getInstance()

                isInitialized = true
                Timber.i("GOG service initialized successfully with Chaquopy")

                return isInitialized
            } catch (e: Exception) {
                Timber.e(e, "Exception during GOG service initialization")
                return false
            }
        }

        /**
         * Execute GOGDL command using Chaquopy
         */
        suspend fun executeCommand(vararg args: String): Result<String> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.d("executeCommand called with args: ${args.joinToString(" ")}")

                    if (!Python.isStarted()) {
                        Timber.e("Python is not started! Cannot execute GOGDL command")
                        return@withContext Result.failure(Exception("Python environment not initialized"))
                    }

                    val python = Python.getInstance()
                    Timber.d("Python instance obtained successfully")

                    val sys = python.getModule("sys")
                    val io = python.getModule("io")
                    val originalArgv = sys.get("argv")

                    try {
                        // Now import our Android-compatible GOGDL CLI module
                        Timber.d("Importing gogdl.cli module...")
                        val gogdlCli = python.getModule("gogdl.cli")
                        Timber.d("gogdl.cli module imported successfully")

                        // Set up arguments for argparse
                        val argsList = listOf("gogdl") + args.toList()
                        Timber.d("Setting GOGDL arguments for argparse: ${args.joinToString(" ")}")
                        // Convert to Python list to avoid jarray issues
                        val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                        sys.put("argv", pythonList)
                        Timber.d("sys.argv set to: $argsList")

                        // Capture stdout
                        val stdoutCapture = io.callAttr("StringIO")
                        val originalStdout = sys.get("stdout")
                        sys.put("stdout", stdoutCapture)
                        Timber.d("stdout capture configured")

                        // Execute the main function
                        Timber.d("Calling gogdl.cli.main()...")
                        gogdlCli.callAttr("main")
                        Timber.d("gogdl.cli.main() completed")

                        // Get the captured output
                        val output = stdoutCapture.callAttr("getvalue").toString()
                        Timber.d("GOGDL raw output (length: ${output.length}): $output")

                        // Restore original stdout
                        sys.put("stdout", originalStdout)

                        if (output.isNotEmpty()) {
                            Timber.d("Returning success with output")
                            Result.success(output)
                        } else {
                            Timber.w("GOGDL execution completed but output is empty")
                            Result.success("GOGDL execution completed")
                        }

                    } catch (e: Exception) {
                        Timber.e(e, "GOGDL execution exception: ${e.javaClass.simpleName} - ${e.message}")
                        Timber.e("Exception stack trace: ${e.stackTraceToString()}")
                        Result.failure(Exception("GOGDL execution failed: ${e.message}", e))
                    } finally {
                        // Restore original sys.argv
                        sys.put("argv", originalArgv)
                        Timber.d("sys.argv restored")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to execute GOGDL command: ${args.joinToString(" ")}")
                    Timber.e("Outer exception stack trace: ${e.stackTraceToString()}")
                    Result.failure(Exception("GOGDL execution failed: ${e.message}", e))
                }
            }
        }

        /**
         * Read and parse auth credentials from file
         */
        private fun readAuthCredentials(authConfigPath: String): Result<Pair<String, String>> {
            return try {
                val authFile = File(authConfigPath)
                Timber.d("Checking auth file at: ${authFile.absolutePath}")
                Timber.d("Auth file exists: ${authFile.exists()}")

                if (!authFile.exists()) {
                    return Result.failure(Exception("No authentication found. Please log in first."))
                }

                val authContent = authFile.readText()
                Timber.d("Auth file content: $authContent")

                val authJson = JSONObject(authContent)

                // GOGDL stores credentials nested under client ID
                val credentialsJson = if (authJson.has(GOG_CLIENT_ID)) {
                    authJson.getJSONObject(GOG_CLIENT_ID)
                } else {
                    // Fallback: try to read from root level
                    authJson
                }

                val accessToken = credentialsJson.optString("access_token", "")
                val userId = credentialsJson.optString("user_id", "")

                Timber.d("Parsed access_token: ${if (accessToken.isNotEmpty()) "${accessToken.take(20)}..." else "EMPTY"}")
                Timber.d("Parsed user_id: $userId")

                if (accessToken.isEmpty() || userId.isEmpty()) {
                    Timber.e("Auth data validation failed - accessToken empty: ${accessToken.isEmpty()}, userId empty: ${userId.isEmpty()}")
                    return Result.failure(Exception("Invalid authentication data. Please log in again."))
                }

                Result.success(Pair(accessToken, userId))
            } catch (e: Exception) {
                Timber.e(e, "Failed to read auth credentials")
                Result.failure(e)
            }
        }

        /**
         * Parse full GOGCredentials from auth file
         */
        private fun parseFullCredentials(authConfigPath: String): GOGCredentials {
            return try {
                val authFile = File(authConfigPath)
                if (authFile.exists()) {
                    val authContent = authFile.readText()
                    val authJson = JSONObject(authContent)

                    // GOGDL stores credentials nested under client ID
                    val credentialsJson = if (authJson.has(GOG_CLIENT_ID)) {
                        authJson.getJSONObject(GOG_CLIENT_ID)
                    } else {
                        // Fallback: try to read from root level
                        authJson
                    }

                    GOGCredentials(
                        accessToken = credentialsJson.optString("access_token", ""),
                        refreshToken = credentialsJson.optString("refresh_token", ""),
                        userId = credentialsJson.optString("user_id", ""),
                        username = credentialsJson.optString("username", "GOG User"),
                    )
                } else {
                    // Return dummy credentials for successful auth
                    GOGCredentials(
                        accessToken = "authenticated_${System.currentTimeMillis()}",
                        refreshToken = "refresh_${System.currentTimeMillis()}",
                        userId = "user_123",
                        username = "GOG User",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse auth result")
                // Return dummy credentials as fallback
                GOGCredentials(
                    accessToken = "fallback_token",
                    refreshToken = "fallback_refresh",
                    userId = "fallback_user",
                    username = "GOG User",
                )
            }
        }

        /**
         * Create GOGCredentials from JSON output
         */
        private fun createCredentialsFromJson(outputJson: JSONObject): GOGCredentials {
            return GOGCredentials(
                accessToken = outputJson.optString("access_token", ""),
                refreshToken = outputJson.optString("refresh_token", ""),
                userId = outputJson.optString("user_id", ""),
                username = "GOG User", // We don't have username in the token response
            )
        }

        /**
         * Authenticate with GOG using authorization code from OAuth2 flow
         * Users must visit GOG login page, authenticate, and copy the authorization code
         */
        suspend fun authenticateWithCode(authConfigPath: String, authorizationCode: String): Result<GOGCredentials> {
            return try {
                Timber.i("Starting GOG authentication with authorization code...")

                // Extract the actual authorization code from URL if needed
                val actualCode = if (authorizationCode.startsWith("http")) {
                    // Extract code parameter from URL
                    val codeParam = authorizationCode.substringAfter("code=", "")
                    if (codeParam.isEmpty()) {
                        return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
                    }
                    // Remove any additional parameters after the code
                    val cleanCode = codeParam.substringBefore("&")
                    Timber.d("Extracted authorization code from URL: ${cleanCode.take(20)}...")
                    cleanCode
                } else {
                    authorizationCode
                }

                // Create auth config directory
                val authFile = File(authConfigPath)
                val authDir = authFile.parentFile
                if (authDir != null && !authDir.exists()) {
                    authDir.mkdirs()
                    Timber.d("Created auth config directory: ${authDir.absolutePath}")
                }

                // Execute GOGDL auth command with the authorization code
                Timber.d("Authenticating with auth config path: $authConfigPath, code: ${actualCode.take(10)}...")
                Timber.d("Full auth command: --auth-config-path $authConfigPath auth --code ${actualCode.take(20)}...")

                val result = executeCommand("--auth-config-path", authConfigPath, "auth", "--code", actualCode)

                Timber.d("GOGDL executeCommand result: isSuccess=${result.isSuccess}, exception=${result.exceptionOrNull()?.message}")

                if (result.isSuccess) {
                    val gogdlOutput = result.getOrNull() ?: ""
                    Timber.i("GOGDL command completed, checking authentication result...")
                    Timber.d("GOGDL output for auth: $gogdlOutput")

                    // First, check if GOGDL output indicates success
                    try {
                        Timber.d("Attempting to parse GOGDL output as JSON (length: ${gogdlOutput.length})")
                        val outputJson = JSONObject(gogdlOutput.trim())
                        Timber.d("Successfully parsed JSON, keys: ${outputJson.keys().asSequence().toList()}")

                        // Check if the response indicates an error
                        if (outputJson.has("error") && outputJson.getBoolean("error")) {
                            val errorMsg = outputJson.optString("error_description", "Authentication failed")
                            val errorDetails = outputJson.optString("message", "No details available")
                            Timber.e("GOG authentication failed: $errorMsg - Details: $errorDetails")
                            Timber.e("Full error JSON: $outputJson")
                            return Result.failure(Exception("GOG authentication failed: $errorMsg"))
                        }

                        // Check if we have the required fields for successful auth
                        val accessToken = outputJson.optString("access_token", "")
                        val userId = outputJson.optString("user_id", "")

                        if (accessToken.isEmpty() || userId.isEmpty()) {
                            Timber.e("GOG authentication incomplete: missing access_token or user_id in output")
                            return Result.failure(Exception("Authentication incomplete: missing required data"))
                        }

                        // GOGDL output looks good, now check if auth file was created
                        val authFile = File(authConfigPath)
                        if (authFile.exists()) {
                            // Parse authentication result from file
                            val authData = parseFullCredentials(authConfigPath)
                            Timber.i("GOG authentication successful for user: ${authData.username}")
                            Result.success(authData)
                        } else {
                            Timber.w("GOGDL returned success but no auth file created, using output data")
                            // Create credentials from GOGDL output
                            val credentials = createCredentialsFromJson(outputJson)
                            Result.success(credentials)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse GOGDL output")
                        // Fallback: check if auth file exists
                        val authFile = File(authConfigPath)
                        if (authFile.exists()) {
                            try {
                                val authData = parseFullCredentials(authConfigPath)
                                Timber.i("GOG authentication successful (fallback) for user: ${authData.username}")
                                Result.success(authData)
                            } catch (ex: Exception) {
                                Timber.e(ex, "Failed to parse auth file")
                                Result.failure(Exception("Failed to parse authentication result: ${ex.message}"))
                            }
                        } else {
                            Timber.e("GOG authentication failed: no auth file created and failed to parse output")
                            Result.failure(Exception("Authentication failed: no credentials available"))
                        }
                    }
                } else {
                    val error = result.exceptionOrNull()
                    val errorMsg = error?.message ?: "Unknown authentication error"
                    Timber.e(error, "GOG authentication command failed: $errorMsg")
                    Timber.e("Full error details: ${error?.stackTraceToString()}")
                    Result.failure(Exception("Authentication failed: $errorMsg", error))
                }
            } catch (e: Exception) {
                Timber.e(e, "GOG authentication exception: ${e.message}")
                Timber.e("Exception stack trace: ${e.stackTraceToString()}")
                Result.failure(Exception("Authentication exception: ${e.message}", e))
            }
        }

        // Enhanced hasActiveOperations to track background sync
        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        // Add methods to control sync state
        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): GOGService? = instance

        /**
         * Check if any download is currently active
         */
        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        /**
         * Get the currently downloading game ID (for error messages)
         */
        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        /**
         * Get download info for a specific game
         */
        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }

        /**
         * Get GOG game info by game ID (synchronously for UI)
         * Similar to SteamService.getAppInfoOf()
         */
        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogLibraryManager?.getGameById(gameId)
            }
        }

        /**
         * Update GOG game in database
         */
        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogLibraryManager?.updateGame(game)
        }

        /**
         * Insert or update GOG game in database (uses REPLACE strategy)
         */
        suspend fun insertOrUpdateGOGGame(game: GOGGame) {
            val instance = getInstance()
            if (instance == null) {
                timber.log.Timber.e("GOGService instance is null, cannot insert game")
                return
            }
            timber.log.Timber.d("Inserting game: id=${game.id}, isInstalled=${game.isInstalled}, installPath=${game.installPath}")
            instance.gogLibraryManager.insertGame(game)
            timber.log.Timber.d("Insert completed for game: ${game.id}")
        }

        /**
         * Check if a GOG game is installed (synchronous for UI)
         */
        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogLibraryManager?.getGameById(gameId)?.isInstalled == true
            }
        }

        /**
         * Get install path for a GOG game (synchronous for UI)
         */
        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogLibraryManager?.getGameById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }

        /**
         * Clean up active download when game is deleted
         */
        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }

        /**
         * Check if user is authenticated by testing GOGDL command
         */
        fun hasStoredCredentials(context: Context): Boolean {
            val authFile = File(context.filesDir, "gog_auth.json")
            return authFile.exists()
        }

        /**
         * Get user credentials by calling GOGDL auth command (without --code)
         * This will automatically handle token refresh if needed
         */
        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return try {
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    return Result.failure(Exception("No stored credentials found"))
                }

                // Use GOGDL to get credentials - this will handle token refresh automatically
                val result = executeCommand("--auth-config-path", authConfigPath, "auth")

                if (result.isSuccess) {
                    val output = result.getOrNull() ?: ""
                    Timber.d("GOGDL credentials output: $output")

                    try {
                        val credentialsJson = JSONObject(output.trim())

                        // Check if there's an error
                        if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                            val errorMsg = credentialsJson.optString("message", "Authentication failed")
                            Timber.e("GOGDL credentials failed: $errorMsg")
                            return Result.failure(Exception("Authentication failed: $errorMsg"))
                        }

                        // Extract credentials from GOGDL response
                        val accessToken = credentialsJson.optString("access_token", "")
                        val refreshToken = credentialsJson.optString("refresh_token", "")
                        val username = credentialsJson.optString("username", "GOG User")
                        val userId = credentialsJson.optString("user_id", "")

                        val credentials = GOGCredentials(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            username = username,
                            userId = userId,
                        )

                        Timber.d("Got credentials for user: $username")
                        Result.success(credentials)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse GOGDL credentials response")
                        Result.failure(e)
                    }
                } else {
                    Timber.e("GOGDL credentials command failed")
                    Result.failure(Exception("Failed to get credentials from GOG"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get stored credentials via GOGDL")
                Result.failure(e)
            }
        }

        /**
         * Validate credentials by calling GOGDL auth command (without --code)
         * This will automatically refresh tokens if they're expired
         */
        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return try {
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    Timber.d("No stored credentials found for validation")
                    return Result.success(false)
                }

                Timber.d("Starting credentials validation with GOGDL")

                // Use GOGDL to get credentials - this will handle token refresh automatically
                val result = executeCommand("--auth-config-path", authConfigPath, "auth")

                if (!result.isSuccess) {
                    val error = result.exceptionOrNull()
                    Timber.e("Credentials validation failed - command failed: ${error?.message}")
                    return Result.success(false)
                }

                val output = result.getOrNull() ?: ""
                Timber.d("GOGDL validation output: $output")

                try {
                    val credentialsJson = JSONObject(output.trim())

                    // Check if there's an error
                    if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                        val errorDesc = credentialsJson.optString("message", "Unknown error")
                        Timber.e("Credentials validation failed: $errorDesc")
                        return Result.success(false)
                    }

                    Timber.d("Credentials validation successful")
                    return Result.success(true)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse validation response: $output")
                    return Result.success(false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to validate credentials")
                return Result.failure(e)
            }
        }

        fun clearStoredCredentials(context: Context): Boolean {
            return try {
                val authFile = File(context.filesDir, "gog_auth.json")
                if (authFile.exists()) {
                    authFile.delete()
                } else {
                    true
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear GOG credentials")
                false
            }
        }

        /**
         * Fetch the user's GOG library (list of owned games)
         * Returns a list of GOGGame objects with basic metadata
         */
        suspend fun listGames(context: Context): Result<List<GOGGame>> {
            return try {
                Timber.i("Fetching GOG library via GOGDL...")
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    Timber.e("Cannot list games: not authenticated")
                    return Result.failure(Exception("Not authenticated. Please log in first."))
                }

                // Execute gogdl list command - auth-config-path must come BEFORE the command
                val result = executeCommand("--auth-config-path", authConfigPath, "list", "--pretty")

                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    Timber.e(error, "Failed to fetch GOG library: ${error?.message}")
                    return Result.failure(error ?: Exception("Failed to fetch GOG library"))
                }

                val output = result.getOrNull() ?: ""
                Timber.d("GOGDL list output length: ${output.length}")
                Timber.d("GOGDL list output preview: ${output.take(500)}")

                // Parse the JSON output
                try {
                    // GOGDL list returns a JSON array of games
                    val gamesArray = org.json.JSONArray(output.trim())
                    val games = mutableListOf<GOGGame>()

                    Timber.d("Found ${gamesArray.length()} games in GOG library")

                    for (i in 0 until gamesArray.length()) {
                        try {
                            val gameObj = gamesArray.getJSONObject(i)

                            // Parse genres array if present
                            val genresList = mutableListOf<String>()
                            if (gameObj.has("genres")) {
                                val genresArray = gameObj.optJSONArray("genres")
                                if (genresArray != null) {
                                    for (j in 0 until genresArray.length()) {
                                        genresList.add(genresArray.getString(j))
                                    }
                                }
                            }

                            // Parse languages array if present
                            val languagesList = mutableListOf<String>()
                            if (gameObj.has("languages")) {
                                val languagesArray = gameObj.optJSONArray("languages")
                                if (languagesArray != null) {
                                    for (j in 0 until languagesArray.length()) {
                                        languagesList.add(languagesArray.getString(j))
                                    }
                                }
                            }

                            val game = GOGGame(
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

                            // Debug: Log the raw developer/publisher data from API
                            if (i == 0) {  // Only log first game to avoid spam
                                Timber.tag("GOG").d("=== DEBUG: First game API response ===")
                                Timber.tag("GOG").d("Game: ${game.title} (${game.id})")
                                Timber.tag("GOG").d("Developer field: ${gameObj.optString("developer", "EMPTY")}")
                                Timber.tag("GOG").d("Publisher field: ${gameObj.optString("publisher", "EMPTY")}")
                                Timber.tag("GOG").d("_debug_developers_raw: ${gameObj.opt("_debug_developers_raw")}")
                                Timber.tag("GOG").d("_debug_publisher_raw: ${gameObj.opt("_debug_publisher_raw")}")
                                Timber.tag("GOG").d("Full game object keys: ${gameObj.keys().asSequence().toList()}")
                                Timber.tag("GOG").d("=====================================")
                            }

                            games.add(game)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to parse game at index $i, skipping")
                        }
                    }

                    Timber.i("Successfully parsed ${games.size} games from GOG library")
                    Result.success(games)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse GOG library JSON: $output")
                    Result.failure(Exception("Failed to parse GOG library: ${e.message}", e))
                }
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error while fetching GOG library")
                Result.failure(e)
            }
        }

        /**
         * Fetch a single game's metadata from GOG API and insert it into the database
         * Used when a game is downloaded but not in the database
         */
        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return try {
                Timber.i("Fetching single game data for gameId: $gameId")
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    return Result.failure(Exception("Not authenticated"))
                }

                // Execute gogdl list command and find this specific game
                val result = executeCommand("--auth-config-path", authConfigPath, "list", "--pretty")

                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Failed to fetch game data"))
                }

                val output = result.getOrNull() ?: ""
                val gamesArray = org.json.JSONArray(output.trim())

                // Find the game with matching ID
                for (i in 0 until gamesArray.length()) {
                    val gameObj = gamesArray.getJSONObject(i)
                    if (gameObj.optString("id", "") == gameId) {
                        // Parse genres
                        val genresList = mutableListOf<String>()
                        gameObj.optJSONArray("genres")?.let { genresArray ->
                            for (j in 0 until genresArray.length()) {
                                genresList.add(genresArray.getString(j))
                            }
                        }

                        // Parse languages
                        val languagesList = mutableListOf<String>()
                        gameObj.optJSONArray("languages")?.let { languagesArray ->
                            for (j in 0 until languagesArray.length()) {
                                languagesList.add(languagesArray.getString(j))
                            }
                        }

                        val game = GOGGame(
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

                        // Insert into database
                        getInstance()?.gogLibraryManager?.let { manager ->
                            withContext(Dispatchers.IO) {
                                manager.insertGame(game)
                            }
                        }

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

        /**
         * Download a GOG game with full progress tracking via GOGDL log parsing
         */
        suspend fun downloadGame(gameId: String, installPath: String, authConfigPath: String): Result<DownloadInfo?> {
            return try {
                Timber.i("Starting GOGDL download with progress parsing for game $gameId")

                val installDir = File(installPath)
                if (!installDir.exists()) {
                    installDir.mkdirs()
                }

                // Create DownloadInfo for progress tracking
                val downloadInfo = DownloadInfo(jobCount = 1)

                // Track this download in the active downloads map
                getInstance()?.activeDownloads?.put(gameId, downloadInfo)

                // Start GOGDL download with progress parsing
                val downloadJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Create support directory for redistributables (like Heroic does)
                        val supportDir = File(installDir.parentFile, "gog-support")
                        supportDir.mkdirs()

                        val result = executeCommandWithCallback(
                            downloadInfo,
                            "--auth-config-path", authConfigPath,
                            "download", ContainerUtils.extractGameIdFromContainerId(gameId).toString(),
                            "--platform", "windows",
                            "--path", installPath,
                            "--support", supportDir.absolutePath,
                            "--skip-dlcs",
                            "--lang", "en-US",
                            "--max-workers", "1",
                        )

                        if (result.isSuccess) {
                            // Check if the download was actually cancelled
                            if (downloadInfo.getProgress() < 0.0f) {
                                Timber.i("GOGDL download was cancelled by user")
                            } else {
                                downloadInfo.setProgress(1.0f) // Mark as complete
                                Timber.i("GOGDL download completed successfully")
                            }
                        } else {
                            downloadInfo.setProgress(-1.0f) // Mark as failed
                            Timber.e("GOGDL download failed: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: CancellationException) {
                        Timber.i("GOGDL download cancelled by user")
                        downloadInfo.setProgress(-1.0f) // Mark as cancelled
                    } catch (e: Exception) {
                        Timber.e(e, "GOGDL download failed")
                        downloadInfo.setProgress(-1.0f) // Mark as failed
                    } finally {
                        // Clean up the download from active downloads
                        getInstance()?.activeDownloads?.remove(gameId)
                        Timber.d("Cleaned up download for game: $gameId")
                    }
                }

                // Store the job in DownloadInfo so it can be cancelled
                downloadInfo.setDownloadJob(downloadJob)

                Result.success(downloadInfo)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start GOG game download")
                Result.failure(e)
            }
        }

        /**
         * Execute GOGDL command with progress callback
         */
        private suspend fun executeCommandWithCallback(downloadInfo: DownloadInfo, vararg args: String): Result<String> {
            return withContext(Dispatchers.IO) {
                try {
                    val python = Python.getInstance()
                    val sys = python.getModule("sys")
                    val originalArgv = sys.get("argv")

                    try {
                        // Create progress callback that Python can invoke
                        val progressCallback = ProgressCallback(downloadInfo)
                        // Get the gogdl module and set up callback
                        val gogdlModule = python.getModule("gogdl")

                        // Try to set progress callback if gogdl supports it
                        try {
                            gogdlModule.put("_progress_callback", progressCallback)
                            Timber.d("Progress callback registered with GOGDL")
                        } catch (e: Exception) {
                            Timber.w(e, "Could not register progress callback, will use estimation")
                        }

                        val gogdlCli = python.getModule("gogdl.cli")

                        // Set up arguments for argparse
                        val argsList = listOf("gogdl") + args.toList()
                        Timber.d("Setting GOGDL arguments: ${args.joinToString(" ")}")
                        val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                        sys.put("argv", pythonList)

                        // Check for cancellation before starting
                        ensureActive()

                        // Start a simple progress estimator in case callback doesn't work
                        val estimatorJob = CoroutineScope(Dispatchers.IO).launch {
                            estimateProgress(downloadInfo)
                        }

                        try {
                            // Execute the main function
                            gogdlCli.callAttr("main")
                            Timber.i("GOGDL execution completed successfully")
                            Result.success("Download completed")
                        } finally {
                            estimatorJob.cancel()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "GOGDL execution failed: ${e.message}")
                        Result.failure(e)
                    } finally {
                        sys.put("argv", originalArgv)
                    }
                } catch (e: CancellationException) {
                    Timber.i("GOGDL command cancelled")
                    throw e // Re-throw to propagate cancellation
                } catch (e: Exception) {
                    Timber.e(e, "Failed to execute GOGDL command: ${args.joinToString(" ")}")
                    Result.failure(e)
                }
            }
        }

        /**
         * Estimate progress when callback isn't available
         * Shows gradual progress to indicate activity
         */
        private suspend fun estimateProgress(downloadInfo: DownloadInfo) {
            try {
                var lastProgress = 0.0f
                val startTime = System.currentTimeMillis()

                while (downloadInfo.getProgress() < 1.0f && downloadInfo.getProgress() >= 0.0f) {
                    delay(3000L) // Update every 3 seconds

                    val elapsed = System.currentTimeMillis() - startTime
                    val estimatedProgress = when {
                        elapsed < 5000 -> 0.05f
                        elapsed < 15000 -> 0.15f
                        elapsed < 30000 -> 0.30f
                        elapsed < 60000 -> 0.50f
                        elapsed < 120000 -> 0.70f
                        elapsed < 180000 -> 0.85f
                        else -> 0.95f
                    }.coerceAtLeast(lastProgress)

                    // Only update if progress hasn't been set by callback
                    if (downloadInfo.getProgress() <= lastProgress + 0.01f) {
                        downloadInfo.setProgress(estimatedProgress)
                        lastProgress = estimatedProgress
                        Timber.d("Estimated progress: %.1f%%", estimatedProgress * 100)
                    } else {
                        // Callback is working, update our tracking
                        lastProgress = downloadInfo.getProgress()
                    }
                }
            } catch (e: CancellationException) {
                Timber.d("Progress estimation cancelled")
            } catch (e: Exception) {
                Timber.w(e, "Error in progress estimation")
            }
        }

        /**
         * Sync GOG cloud saves for a game (stub)
         * TODO: Implement cloud save sync
         */
        suspend fun syncCloudSaves(gameId: String, savePath: String, authConfigPath: String, timestamp: Float = 0.0f): Result<Unit> {
            return Result.success(Unit)
        }

        /**
         * Get download and install size information using gogdl info command (stub)
         * TODO: Implement size info fetching
         */
        suspend fun getGameSizeInfo(gameId: String): GameSizeInfo? = withContext(Dispatchers.IO) {
            try {
                val authConfigPath = "/data/data/app.gamenative/files/gog_config.json"

                Timber.d("Getting size info for GOG game: $gameId")

                // TODO: Use executeCommand to get game size info
                // For now, return null
                Timber.w("GOG size info not fully implemented yet")
                null
            } catch (e: Exception) {
                Timber.w(e, "Failed to get size info for game $gameId")
                null
            }
        }

        /**
         * Cancel an active download for a specific game
         */
        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                Timber.d("Cancelled download job for game: $gameId")

                // Clean up immediately
                instance.activeDownloads.remove(gameId)
                Timber.d("Removed game from active downloads: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }
    }

    // Add these for foreground service support
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var gogLibraryManager: GOGLibraryManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize GOGLibraryManager with database DAO
        val database = Room.databaseBuilder(
            applicationContext,
            PluviaDatabase::class.java,
            DATABASE_NAME
        ).build()
        gogLibraryManager = GOGLibraryManager(database.gogGameDao())

        Timber.d("GOGService.onCreate() - instance and gogLibraryManager initialized")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("GOGService.onStartCommand() - gogLibraryManager initialized: ${::gogLibraryManager.isInitialized}")
        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("GOG Service running...")
        startForeground(2, notification) // Use different ID than SteamService (which uses 1)

        // Start background library sync automatically when service starts with tracking
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.d("[GOGService]: Starting background library sync")

                val syncResult = gogLibraryManager.startBackgroundSync(applicationContext)
                if (syncResult.isFailure) {
                    Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                } else {
                    Timber.i("[GOGService]: Background library sync started successfully")
                }
            } catch (e: Exception) {
                Timber.e(e, "[GOGService]: Exception starting background sync")
            } finally {
                setSyncInProgress(false)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
