package app.gamenative.service.gog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * GOG Service - thin coordinator that delegates to specialized managers.
 *
 * Architecture:
 * - GOGPythonBridge: Low-level Python/GOGDL command execution
 * - GOGAuthManager: Authentication and account management
 * - GOGManager: Game library, downloads, and installation
 *
 * This service maintains backward compatibility through static accessors
 * while delegating all operations to the appropriate managers.
 */
@AndroidEntryPoint
class GOGService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.GOG_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.GOG_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: GOGService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        /**
         * Start the GOG service. Handles both first-time start and subsequent automatic syncs.
         * - First-time start: Always syncs (no throttle)
         * - Subsequent starts: Throttled to once per 15 minutes
         */
        fun start(context: Context) {
            // If already running, do nothing
            if (isRunning) {
                Timber.d("[GOGService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[GOGService] First-time start - starting service with initial sync")
                val intent = Intent(context, GOGService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: check throttle
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[GOGService] Starting service with automatic sync (throttle passed)")
                val intent = Intent(context, GOGService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.d("[GOGService] Skipping start - throttled (${remainingMinutes}min remaining)")
            }
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[GOGService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, GOGService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }


        fun initialize(context: Context): Boolean {
            return GOGPythonBridge.initialize(context)
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to GOGAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
            return GOGAuthManager.authenticateWithCode(context, authorizationCode)
        }


        fun hasStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.hasStoredCredentials(context)
        }


        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return GOGAuthManager.getStoredCredentials(context)
        }


        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return GOGAuthManager.validateCredentials(context)
        }


        fun clearStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.clearStoredCredentials(context)
        }

        /**
         * Logout from GOG - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.i("[GOGService] Logging out from GOG...")

                    // Get instance first before stopping the service
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.w("[GOGService] Service instance not available during logout")
                        return@withContext Result.failure(Exception("Service not running"))
                    }

                    // Clear stored credentials
                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.w("[GOGService] Failed to clear credentials during logout")
                    }

                    // Clear all GOG games from database
                    instance.gogManager.deleteAllGames()
                    Timber.i("[GOGService] All GOG games removed from database")

                    // Stop the service
                    stop()

                    Timber.i("[GOGService] Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService] Error during logout")
                    Result.failure(e)
                }
            }
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): GOGService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }


        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }


        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }


        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }


        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(gameId)
                Timber.d("Download cancelled for game: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameById(gameId)
            }
        }

        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameById(gameId)
                if (game?.isInstalled != true) {
                    return@runBlocking false
                }

                // Verify the installation is actually valid
                val (isValid, errorMessage) = getInstance()?.gogManager?.verifyInstallation(gameId)
                    ?: Pair(false, "Service not available")
                if (!isValid) {
                    Timber.w("Game $gameId marked as installed but verification failed: $errorMessage")
                }
                isValid
            }
        }


        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }


        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            return getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }


        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            return getInstance()?.gogManager?.getInstalledExe(context, libraryItem)
                ?: ""
        }


        fun getWineStartCommand(
            context: Context,
            libraryItem: LibraryItem,
            container: com.winlator.container.Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: com.winlator.core.envvars.EnvVars,
            guestProgramLauncherComponent: com.winlator.xenvironment.components.GuestProgramLauncherComponent
        ): String {
            return getInstance()?.gogManager?.getWineStartCommand(
                context, libraryItem, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent
            ) ?: "\"explorer.exe\""
        }


        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }


        fun downloadGame(context: Context, gameId: String, installPath: String): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            // Create DownloadInfo for progress tracking
            val downloadInfo = DownloadInfo(jobCount = 1)

            // Track in activeDownloads first
            instance.activeDownloads[gameId] = downloadInfo

            // Launch download in service scope so it runs independently
            instance.scope.launch {
                try {
                    Timber.d("[Download] Starting download for game $gameId")
                    val result = instance.gogManager.downloadGame(context, gameId, installPath, downloadInfo)

                    if (result.isFailure) {
                        Timber.e(result.exceptionOrNull(), "[Download] Failed for game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                    } else {
                        Timber.i("[Download] Completed successfully for game $gameId")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                } finally {
                    // Remove from activeDownloads for both success and failure
                    // so UI knows download is complete and to prevent stale entries
                    instance.activeDownloads.remove(gameId)
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }

            return Result.success(downloadInfo)
        }


        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Delete/uninstall a GOG game
         * Delegates to GOGManager.deleteGame
         */
        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            return getInstance()?.gogManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Sync GOG cloud saves for a game
         * @param context Android context
         * @param appId Game app ID (e.g., "gog_123456")
         * @param preferredAction Preferred sync action: "download", "upload", or "none"
         * @return true if sync succeeded, false otherwise
         */
        suspend fun syncCloudSaves(context: Context, appId: String, preferredAction: String = "none"): Boolean = withContext(Dispatchers.IO) {
            try {
                Timber.tag("GOG").d("[Cloud Saves] syncCloudSaves called for $appId with action: $preferredAction")
                val instance = getInstance()
                if (instance == null) {
                    Timber.tag("GOG").e("[Cloud Saves] Service instance not available")
                    return@withContext false
                }

                if (!GOGAuthManager.hasStoredCredentials(context)) {
                    Timber.tag("GOG").e("[Cloud Saves] Cannot sync saves: not authenticated")
                    return@withContext false
                }

                val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                Timber.tag("GOG").d("[Cloud Saves] Using auth config path: $authConfigPath")

                // Get game info
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                Timber.tag("GOG").d("[Cloud Saves] Extracted game ID: $gameId from appId: $appId")
                val game = instance.gogManager.getGameById(gameId.toString())

                if (game == null) {
                    Timber.tag("GOG").e("[Cloud Saves] Game not found for appId: $appId")
                    return@withContext false
                }
                Timber.tag("GOG").d("[Cloud Saves] Found game: ${game.title}")

                // Get save directory paths (Android runs games through Wine, so always Windows)
                Timber.tag("GOG").d("[Cloud Saves] Resolving save directory paths for $appId")
                val saveLocations = instance.gogManager.getSaveDirectoryPath(context, appId, game.title)

                if (saveLocations == null || saveLocations.isEmpty()) {
                    Timber.tag("GOG").w("[Cloud Saves] No save locations found for game $appId (cloud saves may not be enabled)")
                    return@withContext false
                }
                Timber.tag("GOG").i("[Cloud Saves] Found ${saveLocations.size} save location(s) for $appId")

                var allSucceeded = true

                // Sync each save location
                for ((index, location) in saveLocations.withIndex()) {
                    try {
                        Timber.tag("GOG").d("[Cloud Saves] Processing location ${index + 1}/${saveLocations.size}: '${location.name}'")
                        // Get stored timestamp for this location
                        val timestamp = instance.gogManager.getSyncTimestamp(appId, location.name)

                        Timber.tag("GOG").i("[Cloud Saves] Syncing '${location.name}' for game $gameId (path: ${location.location}, timestamp: $timestamp, action: $preferredAction)")

                        // Build command arguments (matching HeroicGamesLauncher format)
                        val commandArgs = mutableListOf(
                            "--auth-config-path", authConfigPath,
                            "save-sync",
                            location.location,
                            gameId.toString(),
                            "--os", "windows", // Android runs games through Wine
                            "--ts", timestamp,
                            "--name", location.name,
                            "--prefered-action", preferredAction
                        )
                        Timber.tag("GOG").d("[Cloud Saves] Executing Python command with args: ${commandArgs.joinToString(" ")}")

                        // Execute sync command
                        val result = GOGPythonBridge.executeCommand(*commandArgs.toTypedArray())

                        if (result.isSuccess) {
                            val output = result.getOrNull() ?: ""
                            Timber.tag("GOG").d("[Cloud Saves] Python command output: $output")
                            // Python save-sync returns timestamp on success, store it
                            val newTimestamp = output.trim()
                            if (newTimestamp.isNotEmpty() && newTimestamp != "0") {
                                instance.gogManager.setSyncTimestamp(appId, location.name, newTimestamp)
                                Timber.tag("GOG").d("[Cloud Saves] Updated timestamp for '${location.name}': $newTimestamp")
                            } else {
                                Timber.tag("GOG").w("[Cloud Saves] No valid timestamp returned (output: '$newTimestamp')")
                            }
                            Timber.tag("GOG").i("[Cloud Saves] Successfully synced save location '${location.name}' for game $gameId")
                        } else {
                            val error = result.exceptionOrNull()
                            Timber.tag("GOG").e(error, "[Cloud Saves] Failed to sync save location '${location.name}' for game $gameId")
                            allSucceeded = false
                        }
                    } catch (e: Exception) {
                        Timber.tag("GOG").e(e, "[Cloud Saves] Exception syncing save location '${location.name}' for game $gameId")
                        allSucceeded = false
                    }
                }

                if (allSucceeded) {
                    Timber.tag("GOG").i("[Cloud Saves] All save locations synced successfully for $appId")
                    return@withContext true
                } else {
                    Timber.tag("GOG").w("[Cloud Saves] Some save locations failed to sync for $appId")
                    return@withContext false
                }
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Failed to sync cloud saves for App ID: $appId")
                return@withContext false
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var gogManager: GOGManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    // GOGManager is injected by Hilt
    override fun onCreate() {
        super.onCreate()
        instance = this


        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[GOGService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("GOG Service running...")
        startForeground(2, notification) // Use different ID than SteamService (which uses 1)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[GOGService] Manual sync requested - bypassing throttle")
                true
            }
            ACTION_SYNC_LIBRARY -> {
                Timber.i("[GOGService] Automatic sync requested")
                true
            }
            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.d("[GOGService] Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || !backgroundSyncJob!!.isActive)) {
            Timber.i("[GOGService] Starting background library sync")
            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.d("[GOGService]: Starting background library sync")

                    val syncResult = gogManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.i("[GOGService]: Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService]: Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.d("[GOGService] Background sync already in progress, skipping")
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
