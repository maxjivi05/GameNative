package app.gamenative.service.epic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Epic Games Service - thin coordinator that delegates to specialized managers.
 *
 * Architecture:
 * - EpicPythonBridge: Low-level Legendary CLI command execution
 * - EpicAuthManager: Authentication and account management
 * - EpicManager: Game library, downloads, and installation
 *
 * This service maintains backward compatibility through static accessors
 * while delegating all operations to the appropriate managers.
 */
@AndroidEntryPoint
class EpicService : Service() {

    companion object {
        private var instance: EpicService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null


        fun start(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, EpicService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        fun initialize(context: Context): Boolean {
            return EpicPythonBridge.initialize(context)
        }

        // ==========================================================================
        // AUTHENTICATION
        // TODO: Uncomment when EpicAuthManager is implemented
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
            // return EpicAuthManager.authenticateWithCode(context, authorizationCode)
            return Result.failure(Exception("Not implemented"))
        }

        fun hasStoredCredentials(context: Context): Boolean {
            // return EpicAuthManager.hasStoredCredentials(context)
            return false
        }

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
            // return EpicAuthManager.getStoredCredentials(context)
            return Result.failure(Exception("Not implemented"))
        }

        suspend fun validateCredentials(context: Context): Result<Boolean> {
            // return EpicAuthManager.validateCredentials(context)
            return Result.failure(Exception("Not implemented"))
        }

        fun clearStoredCredentials(context: Context): Boolean {
            // return EpicAuthManager.clearStoredCredentials(context)
            return false
        }

        suspend fun refreshAccessToken(context: Context): Result<EpicCredentials> {
            // return EpicAuthManager.refreshAccessToken(context)
            return Result.failure(Exception("Not implemented"))
        }

        suspend fun getUserInfo(context: Context): Result<Pair<String, String>> {
            // return EpicAuthManager.getUserInfo(context)
            return Result.failure(Exception("Not implemented"))
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

        fun getInstance(): EpicService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance EpicManager
        // ==========================================================================


        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }


        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }


        fun getDownloadInfo(appName: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(appName)
        }


        fun cleanupDownload(appName: String) {
            getInstance()?.activeDownloads?.remove(appName)
        }


        fun cancelDownload(appName: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(appName)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for Epic game: $appName")
                downloadInfo.cancel()
                instance.activeDownloads.remove(appName)
                Timber.d("Download cancelled for Epic game: $appName")
                true
            } else {
                Timber.w("No active download found for Epic game: $appName")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance EpicManager (Stubs)
        // ==========================================================================


        fun getEpicGameOf(appName: String): EpicGame? {
            // TODO: Implement when EpicManager is ready
            return null
        }


        suspend fun updateEpicGame(game: EpicGame) {
            // TODO: Implement when EpicManager is ready
        }


        fun isGameInstalled(appName: String): Boolean {
            // TODO: Implement when EpicManager is ready
            return false
        }


        fun getInstallPath(appName: String): String? {
            // TODO: Implement when EpicManager is ready
            return null
        }


        fun verifyInstallation(appName: String): Pair<Boolean, String?> {
            // TODO: Implement when EpicManager is ready
            return Pair(false, "Not implemented")
        }


        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            // TODO: Implement when EpicManager is ready
            return ""
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
            // TODO: Implement when EpicManager is ready
            return "\"explorer.exe\""
        }


        suspend fun refreshLibrary(context: Context): Result<Int> {
            // TODO: Implement when EpicManager is ready
            return Result.failure(Exception("Not implemented"))
        }


        fun downloadGame(context: Context, appName: String, installPath: String): Result<DownloadInfo?> {
            // TODO: Implement when EpicManager is ready
            return Result.failure(Exception("Not implemented"))
        }


        suspend fun refreshSingleGame(appName: String, context: Context): Result<EpicGame?> {
            // TODO: Implement when EpicManager is ready
            return Result.failure(Exception("Not implemented"))
        }


        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            // TODO: Implement when EpicManager is ready
            return Result.failure(Exception("Not implemented"))
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    // TODO: Uncomment when EpicManager is implemented
    // @Inject
    // lateinit var epicManager: EpicManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by Epic app name
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("EpicService.onStartCommand()")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("Epic Games Service running...")
        startForeground(3, notification) // Use different ID than Steam (1) and GOG (2)

        // Start background library sync automatically when service starts
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.d("[EpicService]: Starting background library sync")

                // TODO: Implement when EpicManager is ready
                // val syncResult = epicManager.startBackgroundSync(applicationContext)
                // if (syncResult.isFailure) {
                //     Timber.w("[EpicService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                // } else {
                //     Timber.i("[EpicService]: Background library sync started successfully")
                // }

                Timber.i("[EpicService]: Background sync stub - will implement when EpicManager is ready")
            } catch (e: Exception) {
                Timber.e(e, "[EpicService]: Exception starting background sync")
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
