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
        private var instance: GOGService? = null

        // Sync tracking variables
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

        /**
         * Initialize the GOG service with Chaquopy Python
         * Delegates to GOGPythonBridge
         */
        fun initialize(context: Context): Boolean {
            return GOGPythonBridge.initialize(context)
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to GOGAuthManager
        // ==========================================================================

        /**
         * Authenticate with GOG using authorization code
         */
        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
            return GOGAuthManager.authenticateWithCode(context, authorizationCode)
        }

        /**
         * Check if user has stored credentials
         */
        fun hasStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.hasStoredCredentials(context)
        }

        /**
         * Get user credentials - automatically handles token refresh if needed
         */
        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return GOGAuthManager.getStoredCredentials(context)
        }

        /**
         * Validate credentials - automatically refreshes tokens if they're expired
         */
        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return GOGAuthManager.validateCredentials(context)
        }

        /**
         * Clear stored credentials
         */
        fun clearStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.clearStoredCredentials(context)
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

        /**
         * Check if any download is currently active
         */
        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        /**
         * Get the currently downloading game ID
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
         * Clean up active download when game is deleted
         */
        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
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

        /**
         * Get GOG game info by game ID (synchronously for UI)
         */
        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameById(gameId)
            }
        }

        /**
         * Update GOG game in database
         */
        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        /**
         * Insert or update GOG game in database
         */
        suspend fun insertOrUpdateGOGGame(game: GOGGame) {
            val instance = getInstance()
            if (instance == null) {
                Timber.e("GOGService instance is null, cannot insert game")
                return
            }
            Timber.d("Inserting game: id=${game.id}, isInstalled=${game.isInstalled}")
            instance.gogManager.insertGame(game)
        }

        /**
         * Check if a GOG game is installed (synchronous for UI)
         */
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

        /**
         * Get install path for a GOG game (synchronous for UI)
         */
        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }

        /**
         * Verify that a GOG game installation is valid and complete
         */
        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            return getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }

        /**
         * Get the primary executable path for a GOG game
         */
        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            return getInstance()?.gogManager?.getInstalledExe(context, libraryItem)
                ?: ""
        }

        /**
         * Get Wine start command for launching a GOG game
         */
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

        /**
         * Sync GOG library with database
         */
        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Download a GOG game with full progress tracking
         * Launches download in service scope so it runs independently
         */
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
                    } else {
                        Timber.i("[Download] Completed successfully for game $gameId")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setProgress(-1.0f)
                } finally {
                    // Keep in activeDownloads so UI can check status
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}")
                }
            }

            return Result.success(downloadInfo)
        }

        /**
         * Refresh a single game's metadata from GOG API
         */
        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))
        }
    }

    // ==========================================================================
    // Instance members
    // ==========================================================================

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
        Timber.d("GOGService.onStartCommand()")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("GOG Service running...")
        startForeground(2, notification) // Use different ID than SteamService (which uses 1)

        // Start background library sync automatically when service starts
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.d("[GOGService]: Starting background library sync")

                val syncResult = gogManager.startBackgroundSync(applicationContext)
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
