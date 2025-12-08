package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GOGGame
import app.gamenative.data.LibraryItem
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * GOG-specific implementation of BaseAppScreen
 * Handles GOG games with integration to the Python gogdl backend
 */
class GOGAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "GOGAppScreen"

        // Shared state for uninstall dialog - list of appIds that should show the dialog
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        fun showUninstallDialog(appId: String) {
            Timber.tag(TAG).d("showUninstallDialog: appId=$appId")
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
                Timber.tag(TAG).d("Added to uninstall dialog list: $appId")
            }
        }

        fun hideUninstallDialog(appId: String) {
            Timber.tag(TAG).d("hideUninstallDialog: appId=$appId")
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean {
            val result = uninstallDialogAppIds.contains(appId)
            Timber.tag(TAG).d("shouldShowUninstallDialog: appId=$appId, result=$result")
            return result
        }

        // Shared state for install dialog - list of appIds that should show the dialog
        private val installDialogAppIds = mutableStateListOf<String>()

        fun showInstallDialog(appId: String) {
            Timber.tag(TAG).d("showInstallDialog: appId=$appId")
            if (!installDialogAppIds.contains(appId)) {
                installDialogAppIds.add(appId)
                Timber.tag(TAG).d("Added to install dialog list: $appId")
            }
        }

        fun hideInstallDialog(appId: String) {
            Timber.tag(TAG).d("hideInstallDialog: appId=$appId")
            installDialogAppIds.remove(appId)
        }

        fun shouldShowInstallDialog(appId: String): Boolean {
            val result = installDialogAppIds.contains(appId)
            Timber.tag(TAG).d("shouldShowInstallDialog: appId=$appId, result=$result")
            return result
        }
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        Timber.tag(TAG).d("getGameDisplayInfo: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // For GOG games, appId is already the numeric game ID (no prefix)
        val gameId = libraryItem.appId
        val gogGame = remember(gameId) {
            val game = GOGService.getGOGGameOf(gameId)
            if (game != null) {
                Timber.tag(TAG).d("""
                    |=== GOG Game Object ===
                    |Game ID: $gameId
                    |Title: ${game.title}
                    |Developer: ${game.developer}
                    |Publisher: ${game.publisher}
                    |Release Date: ${game.releaseDate}
                    |Description: ${game.description.take(100)}...
                    |Icon URL: ${game.iconUrl}
                    |Image URL: ${game.imageUrl}
                    |Install Path: ${game.installPath}
                    |Is Installed: ${game.isInstalled}
                    |Download Size: ${game.downloadSize} bytes (${game.downloadSize / 1_000_000_000.0} GB)
                    |Install Size: ${game.installSize} bytes (${game.installSize / 1_000_000_000.0} GB)
                    |Genres: ${game.genres.joinToString(", ")}
                    |Languages: ${game.languages.joinToString(", ")}
                    |Play Time: ${game.playTime} seconds
                    |Last Played: ${game.lastPlayed}
                    |Type: ${game.type}
                    |======================
                """.trimMargin())
            } else {
                Timber.tag(TAG).w("""
                    |GOG game not found in database for gameId=$gameId
                    |This usually means the game was added as a container but GOG library hasn't synced yet.
                    |The game will use fallback data from the LibraryItem until GOG library is refreshed.
                """.trimMargin())
            }
            game
        }

        val game = gogGame
        val displayInfo = GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            iconUrl = game?.iconUrl ?: libraryItem.iconHash,
            heroImageUrl = game?.imageUrl ?: game?.iconUrl ?: libraryItem.iconHash,
            gameId = libraryItem.gameId,  // Use gameId property which handles conversion
            appId = libraryItem.appId,
            releaseDate = 0L, // GOG uses string release dates, would need parsing
            developer = game?.developer ?: "Unknown"
        )
        Timber.tag(TAG).d("Returning GameDisplayInfo: name=${displayInfo.name}, iconUrl=${displayInfo.iconUrl}, heroImageUrl=${displayInfo.heroImageUrl}, developer=${displayInfo.developer}")
        return displayInfo
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isInstalled: checking appId=${libraryItem.appId}")
        return try {
            // For GOG games, appId is already the numeric game ID
            val installed = GOGService.isGameInstalled(libraryItem.appId)
            Timber.tag(TAG).d("isInstalled: appId=${libraryItem.appId}, result=$installed")
            installed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check install status for ${libraryItem.appId}")
            false
        }
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isValidToDownload: checking appId=${libraryItem.appId}")
        // GOG games can be downloaded if not already installed or downloading
        val installed = isInstalled(context, libraryItem)
        val downloading = isDownloading(context, libraryItem)
        val valid = !installed && !downloading
        Timber.tag(TAG).d("isValidToDownload: appId=${libraryItem.appId}, installed=$installed, downloading=$downloading, valid=$valid")
        return valid
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isDownloading: checking appId=${libraryItem.appId}")
        // Check if there's an active download for this GOG game
        // For GOG games, appId is already the numeric game ID
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val progress = downloadInfo?.getProgress() ?: 0f
        val downloading = downloadInfo != null && progress in 0f..0.99f
        Timber.tag(TAG).d("isDownloading: appId=${libraryItem.appId}, hasDownloadInfo=${downloadInfo != null}, progress=$progress, result=$downloading")
        return downloading
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        // For GOG games, appId is already the numeric game ID
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val progress = downloadInfo?.getProgress() ?: 0f
        Timber.tag(TAG).d("getDownloadProgress: appId=${libraryItem.appId}, progress=$progress")
        return progress
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // For GOG games, appId is already the numeric game ID
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        Timber.tag(TAG).d("onDownloadInstallClick: appId=${libraryItem.appId}, isDownloading=$isDownloading, installed=$installed")

        if (isDownloading) {
            // Cancel ongoing download
            Timber.tag(TAG).i("Cancelling GOG download for: ${libraryItem.appId}")
            downloadInfo.cancel()
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("GOG game already installed, launching: ${libraryItem.appId}")
            onClickPlay(false)
        } else {
            // Show install confirmation dialog
            Timber.tag(TAG).i("Showing install confirmation dialog for: ${libraryItem.appId}")
            showInstallDialog(libraryItem.appId)
        }
    }

    /**
     * Perform the actual download after confirmation
     */
    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        // For GOG games, appId is already the numeric game ID
        val gameId = libraryItem.appId
        Timber.i("Starting GOG game download: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get auth config path
                val authConfigPath = "${context.filesDir}/gog_auth.json"
                val authFile = File(authConfigPath)
                if (!authFile.exists()) {
                    Timber.e("GOG authentication file not found. User needs to login first.")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Please login to GOG first in Settings",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // Determine install path (use same pattern as Steam)
                val downloadDir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
                val gogGamesDir = File(downloadDir, "GOGGames")
                gogGamesDir.mkdirs() // Ensure directory exists
                val installDir = File(gogGamesDir, gameId)
                val installPath = installDir.absolutePath

                Timber.d("Downloading GOG game to: $installPath")

                // Start download
                val result = GOGService.downloadGame(gameId, installPath, authConfigPath)

                if (result.isSuccess) {
                    val info = result.getOrNull()
                    Timber.i("GOG download started successfully for: $gameId")

                    // Emit download started event to update UI state immediately
                    Timber.tag(TAG).d("[EVENT] Emitting DownloadStatusChanged: appId=${libraryItem.gameId} (from appId=${libraryItem.appId}), isDownloading=true")
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged(libraryItem.gameId, true)
                    )
                    Timber.tag(TAG).d("[EVENT] Emitted DownloadStatusChanged event")

                    // Monitor download completion
                    info?.let { downloadInfo ->
                        // Wait for download to complete
                        while (downloadInfo.getProgress() in 0f..0.99f) {
                            kotlinx.coroutines.delay(1000)
                        }

                        val finalProgress = downloadInfo.getProgress()
                        Timber.i("GOG download final progress: $finalProgress for game: $gameId")
                        if (finalProgress >= 1.0f) {
                            // Download completed successfully
                            Timber.i("GOG download completed: $gameId")

                            // Update or create database entry
                            Timber.d("Attempting to fetch game from database for gameId: $gameId")
                            var game = GOGService.getGOGGameOf(gameId)
                            Timber.d("Fetched game from database: game=${game?.title}, isInstalled=${game?.isInstalled}, installPath=${game?.installPath}")

                            if (game != null) {
                                // Game exists in database - update install status
                                Timber.d("Updating existing game install status: isInstalled=true, installPath=$installPath")
                                GOGService.updateGOGGame(
                                    game.copy(
                                        isInstalled = true,
                                        installPath = installPath
                                    )
                                )
                                Timber.i("Updated GOG game install status in database for ${game.title}")
                            } else {
                                // Game not in database - fetch from API and insert
                                Timber.w("Game not found in database, fetching from GOG API for gameId: $gameId")
                                try {
                                    val result = GOGService.refreshSingleGame(gameId, context)
                                    if (result.isSuccess) {
                                        game = result.getOrNull()
                                        if (game != null) {
                                            // Insert/update the newly fetched game with install info using REPLACE strategy
                                            val updatedGame = game.copy(
                                                isInstalled = true,
                                                installPath = installPath
                                            )
                                            Timber.d("About to insert game with: isInstalled=true, installPath=$installPath")

                                            // Wait for database write to complete
                                            withContext(Dispatchers.IO) {
                                                GOGService.insertOrUpdateGOGGame(updatedGame)
                                            }

                                            Timber.i("Fetched and inserted GOG game ${game.title} with install status")
                                            Timber.d("Game install status in memory: isInstalled=${updatedGame.isInstalled}, installPath=${updatedGame.installPath}")

                                            // Verify database write
                                            val verifyGame = GOGService.getGOGGameOf(gameId)
                                            Timber.d("Verification read from database: isInstalled=${verifyGame?.isInstalled}, installPath=${verifyGame?.installPath}")
                                        } else {
                                            Timber.w("Failed to fetch game data from GOG API for gameId: $gameId")
                                        }
                                    } else {
                                        Timber.e(result.exceptionOrNull(), "Error fetching game from GOG API: $gameId")
                                    }
                                } catch (e: Exception) {
                                    Timber.e(e, "Exception fetching game from GOG API: $gameId")
                                }
                            }

                            // Emit download stopped event
                            Timber.tag(TAG).d("[EVENT] Emitting DownloadStatusChanged: appId=${libraryItem.gameId}, isDownloading=false")
                            app.gamenative.PluviaApp.events.emitJava(
                                app.gamenative.events.AndroidEvent.DownloadStatusChanged(libraryItem.gameId, false)
                            )

                            // Trigger library refresh for install status
                            Timber.tag(TAG).d("[EVENT] Emitting LibraryInstallStatusChanged: appId=${libraryItem.gameId}")
                            app.gamenative.PluviaApp.events.emitJava(
                                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId)
                            )
                            Timber.tag(TAG).d("[EVENT] All completion events emitted")
                        } else {
                            Timber.w("GOG download did not complete successfully: $finalProgress")
                            // Emit download stopped event even if failed/cancelled
                            app.gamenative.PluviaApp.events.emitJava(
                                app.gamenative.events.AndroidEvent.DownloadStatusChanged(libraryItem.gameId, false)
                            )
                        }
                    }
                } else {
                    Timber.e(result.exceptionOrNull(), "Failed to start GOG download")
                    // Emit download stopped event if download failed to start
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged(libraryItem.gameId, false)
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to start download: ${result.exceptionOrNull()?.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during GOG download")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Download error: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onPauseResumeClick: appId=${libraryItem.appId}")
        // For GOG games, appId is already the numeric game ID
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        Timber.tag(TAG).d("onPauseResumeClick: appId=${libraryItem.appId}, isDownloading=$isDownloading")

        if (isDownloading) {
            // Cancel/pause download
            Timber.tag(TAG).i("Pausing GOG download: ${libraryItem.appId}")
            downloadInfo.cancel()
        } else {
            // Resume download (restart from beginning for now)
            Timber.tag(TAG).i("Resuming GOG download: ${libraryItem.appId}")
            onDownloadInstallClick(context, libraryItem) {}
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")
        // For GOG games, appId is already the numeric game ID
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = isInstalled(context, libraryItem)
        Timber.tag(TAG).d("onDeleteDownloadClick: appId=${libraryItem.appId}, isDownloading=$isDownloading, isInstalled=$isInstalled")

        if (isDownloading) {
            // Cancel download immediately if currently downloading
            Timber.tag(TAG).i("Cancelling active download for GOG game: ${libraryItem.appId}")
            downloadInfo.cancel()
            android.widget.Toast.makeText(
                context,
                "Download cancelled",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (isInstalled) {
            // Show uninstall confirmation dialog
            Timber.tag(TAG).i("Showing uninstall dialog for: ${libraryItem.appId}")
            showUninstallDialog(libraryItem.appId)
        }
    }

    /**
     * Perform the actual uninstall of a GOG game
     */
    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        Timber.i("Uninstalling GOG game: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // For GOG games, appId is already the numeric game ID
                val gameId = libraryItem.appId

                // Get install path from GOGService
                val game = GOGService.getGOGGameOf(gameId)

                if (game != null && game.installPath.isNotEmpty()) {
                    val installDir = File(game.installPath)
                    if (installDir.exists()) {
                        Timber.d("Deleting game files from: ${game.installPath}")
                        val deleted = installDir.deleteRecursively()
                        if (deleted) {
                            Timber.i("Successfully deleted game files")
                        } else {
                            Timber.w("Failed to delete some game files")
                        }
                    }

                    // Update database via GOGService - mark as not installed
                    GOGService.updateGOGGame(
                        game.copy(
                            isInstalled = false,
                            installPath = ""
                        )
                    )
                    Timber.d("Updated database: game marked as not installed")

                    // Delete container
                    withContext(Dispatchers.Main) {
                        ContainerUtils.deleteContainer(context, libraryItem.appId)
                    }

                    // Trigger library refresh
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId)
                    )

                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Game uninstalled successfully",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Timber.w("Game not found in database or no install path")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Game not found",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uninstalling GOG game")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Failed to uninstall game: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onUpdateClick: appId=${libraryItem.appId}")
        // TODO: Implement update for GOG games
        // Check GOG for newer version and download if available
        Timber.tag(TAG).d("Update clicked for GOG game: ${libraryItem.appId}")
    }

    override fun getExportFileExtension(): String {
        Timber.tag(TAG).d("getExportFileExtension: returning 'tzst'")
        // GOG containers use the same export format as other Wine containers
        return "tzst"
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            // For GOG games, appId is already the numeric game ID
            val path = GOGService.getInstallPath(libraryItem.appId)
            Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}, path=$path")
            path
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get install path for ${libraryItem.appId}")
            null
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        Timber.tag(TAG).d("loadContainerData: appId=${libraryItem.appId}")
        // Load GOG-specific container data using ContainerUtils
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        val containerData = app.gamenative.utils.ContainerUtils.toContainerData(container)
        Timber.tag(TAG).d("loadContainerData: loaded container for ${libraryItem.appId}")
        return containerData
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        Timber.tag(TAG).i("saveContainerConfig: appId=${libraryItem.appId}")
        // Save GOG-specific container configuration using ContainerUtils
        app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, config)
        Timber.tag(TAG).d("saveContainerConfig: saved container config for ${libraryItem.appId}")
    }

    override fun supportsContainerConfig(): Boolean {
        Timber.tag(TAG).d("supportsContainerConfig: returning true")
        // GOG games support container configuration like other Wine games
        return true
    }

    /**
     * GOG-specific menu options
     */
    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean
    ): List<AppMenuOption> {
        val options = mutableListOf<AppMenuOption>()
        return options
    }

    /**
     * GOG games support standard container reset
     */
    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem
    ): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = {
                resetContainerToDefaults(context, libraryItem)
            }
        )
    }

    /**
     * Override to add GOG-specific analytics
     */
    override fun onRunContainerClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        super.onRunContainerClick(context, libraryItem, onClickPlay)
    }

    /**
     * GOG games don't need special image fetching logic like Custom Games
     * Images come from GOG CDN
     */
    override fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        return null // GOG uses CDN images, not local files
    }

    /**
     * Observe GOG game state changes (download progress, install status)
     */
    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?
    ): (() -> Unit)? {
        Timber.tag(TAG).d("[OBSERVE] Setting up observeGameState for appId=${libraryItem.appId}, gameId=${libraryItem.gameId}")
        val disposables = mutableListOf<() -> Unit>()

        // Listen for download status changes
        val downloadStatusListener: (app.gamenative.events.AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Download status changed for ${libraryItem.appId}, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    // Download started - attach progress listener
                    // For GOG games, appId is already the numeric game ID
                    val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
                    downloadInfo?.addProgressListener { progress ->
                        onProgressChanged(progress)
                    }
                } else {
                    // Download stopped/completed
                    onHasPartialDownloadChanged?.invoke(false)
                }
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables += { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        // Listen for install status changes
        val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] LibraryInstallStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Install status changed for ${libraryItem.appId}, calling onStateChanged()")
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables += { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener) }

        // Return cleanup function
        return {
            disposables.forEach { it() }
        }
    }

    /**
     * GOG-specific dialogs (install confirmation, uninstall confirmation)
     */
    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit
    ) {
        Timber.tag(TAG).d("AdditionalDialogs: composing for appId=${libraryItem.appId}")
        val context = LocalContext.current

        // Monitor uninstall dialog state
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowUninstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showUninstallDialog = shouldShow
                }
        }

        // Monitor install dialog state
        var showInstallDialog by remember { mutableStateOf(shouldShowInstallDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowInstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showInstallDialog = shouldShow
                }
        }
        // Show install confirmation dialog
        if (showInstallDialog) {
            // For GOG games, appId is already the numeric game ID
            val gameId = libraryItem.appId
            val gogGame = remember(gameId) {
                GOGService.getGOGGameOf(gameId)
            }

            val downloadSizeGB = (gogGame?.downloadSize ?: 0L) / 1_000_000_000.0
            val sizeText = if (downloadSizeGB > 0) {
                String.format("%.2f GB", downloadSizeGB)
            } else {
                "Unknown size"
            }

            AlertDialog(
                onDismissRequest = {
                    hideInstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.gog_install_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.gog_install_confirmation_message,
                            gogGame?.title ?: libraryItem.name,
                            sizeText
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideInstallDialog(libraryItem.appId)
                            performDownload(context, libraryItem) {}
                        }
                    ) {
                        Text(stringResource(R.string.download))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideInstallDialog(libraryItem.appId)
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Show uninstall confirmation dialog
        if (showUninstallDialog) {
            // For GOG games, appId is already the numeric game ID
            val gameId = libraryItem.appId
            val gogGame = remember(gameId) {
                GOGService.getGOGGameOf(gameId)
            }

            AlertDialog(
                onDismissRequest = {
                    hideUninstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.gog_uninstall_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.gog_uninstall_confirmation_message,
                            gogGame?.title ?: libraryItem.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                            performUninstall(context, libraryItem)
                        }
                    ) {
                        Text(stringResource(R.string.uninstall))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}
