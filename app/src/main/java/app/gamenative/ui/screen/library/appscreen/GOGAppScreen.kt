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
import app.gamenative.db.PluviaDatabase
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import dagger.hilt.android.EntryPointAccessors
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
        // Shared state for uninstall dialog - list of appIds that should show the dialog
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        fun showUninstallDialog(appId: String) {
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
            }
        }

        fun hideUninstallDialog(appId: String) {
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean {
            return uninstallDialogAppIds.contains(appId)
        }

        // Shared state for install dialog - list of appIds that should show the dialog
        private val installDialogAppIds = mutableStateListOf<String>()

        fun showInstallDialog(appId: String) {
            if (!installDialogAppIds.contains(appId)) {
                installDialogAppIds.add(appId)
            }
        }

        fun hideInstallDialog(appId: String) {
            installDialogAppIds.remove(appId)
        }

        fun shouldShowInstallDialog(appId: String): Boolean {
            return installDialogAppIds.contains(appId)
        }
    }

    /**
     * Get PluviaDatabase instance using Hilt EntryPoint
     */
    private fun getDatabase(context: Context): PluviaDatabase {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            DatabaseEntryPoint::class.java
        )
        return entryPoint.database()
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        var gogGame by remember { mutableStateOf<GOGGame?>(null) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(libraryItem.appId) {
            coroutineScope.launch(Dispatchers.IO) {
                val db = getDatabase(context)
                val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
                gogGame = db.gogGameDao().getById(gameId)
            }
        }

        val game = gogGame
        return GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            iconUrl = game?.iconUrl ?: libraryItem.iconHash,
            heroImageUrl = game?.imageUrl ?: game?.iconUrl ?: libraryItem.iconHash,
            gameId = libraryItem.appId.removePrefix("GOG_").toIntOrNull() ?: 0,
            appId = libraryItem.appId,
            releaseDate = 0L, // GOG uses string release dates, would need parsing
            developer = game?.developer ?: "Unknown"
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        // Check GOGGame.isInstalled from database (synchronous for UI)
        return try {
            val db = getDatabase(context)
            val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
            val game = kotlinx.coroutines.runBlocking {
                db.gogGameDao().getById(gameId)
            }
            game?.isInstalled == true
        } catch (e: Exception) {
            Timber.e(e, "Failed to check install status for ${libraryItem.appId}")
            false
        }
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // GOG games can be downloaded if not already installed or downloading
        return !isInstalled(context, libraryItem) && !isDownloading(context, libraryItem)
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        // Check if there's an active download for this GOG game
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        return downloadInfo != null && (downloadInfo.getProgress() ?: 0f) in 0f..0.99f
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        // Get actual download progress from GOGService
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        return downloadInfo?.getProgress() ?: 0f
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        if (isDownloading) {
            // Cancel ongoing download
            Timber.d("Cancelling GOG download for: ${libraryItem.appId}")
            downloadInfo.cancel()
        } else if (installed) {
            // Already installed: launch game
            Timber.d("GOG game already installed, launching: ${libraryItem.appId}")
            onClickPlay(false)
        } else {
            // Show install confirmation dialog
            showInstallDialog(libraryItem.appId)
        }
    }

    /**
     * Perform the actual download after confirmation
     */
    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
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

                    // Monitor download completion
                    info?.let { downloadInfo ->
                        // Wait for download to complete
                        while (downloadInfo.getProgress() in 0f..0.99f) {
                            kotlinx.coroutines.delay(1000)
                        }

                        val finalProgress = downloadInfo.getProgress()
                        if (finalProgress >= 1.0f) {
                            // Download completed successfully
                            Timber.i("GOG download completed: $gameId")

                            // Update database
                            val db = getDatabase(context)
                            val game = db.gogGameDao().getById(gameId)
                            if (game != null) {
                                db.gogGameDao().update(
                                    game.copy(
                                        isInstalled = true,
                                        installPath = installPath
                                    )
                                )
                                Timber.d("Updated GOG game install status in database")
                            }

                            // Trigger library refresh
                            app.gamenative.PluviaApp.events.emitJava(
                                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId)
                            )
                        } else {
                            Timber.w("GOG download did not complete successfully: $finalProgress")
                        }
                    }
                } else {
                    Timber.e(result.exceptionOrNull(), "Failed to start GOG download")
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
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f

        if (isDownloading) {
            // Cancel/pause download
            Timber.d("Pausing GOG download: ${libraryItem.appId}")
            downloadInfo.cancel()
        } else {
            // Resume download (restart from beginning for now)
            Timber.d("Resuming GOG download: ${libraryItem.appId}")
            onDownloadInstallClick(context, libraryItem) {}
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = isInstalled(context, libraryItem)

        if (isDownloading) {
            // Cancel download immediately if currently downloading
            Timber.d("Cancelling active download for GOG game: ${libraryItem.appId}")
            downloadInfo.cancel()
            android.widget.Toast.makeText(
                context,
                "Download cancelled",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (isInstalled) {
            // Show uninstall confirmation dialog
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
                val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()

                // Get install path from database
                val db = getDatabase(context)
                val game = db.gogGameDao().getById(gameId)

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

                    // Update database - mark as not installed
                    db.gogGameDao().update(
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
        // TODO: Implement update for GOG games
        // Check GOG for newer version and download if available
        Timber.d("Update clicked for GOG game: ${libraryItem.appId}")
    }

    override fun getExportFileExtension(): String {
        // GOG containers use the same export format as other Wine containers
        return "tzst"
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        return try {
            val db = getDatabase(context)
            val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
            val game = kotlinx.coroutines.runBlocking {
                db.gogGameDao().getById(gameId)
            }
            if (game?.isInstalled == true) game.installPath else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to get install path for ${libraryItem.appId}")
            null
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        // Load GOG-specific container data using ContainerUtils
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return app.gamenative.utils.ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        // Save GOG-specific container configuration using ContainerUtils
        app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean {
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

        // TODO: Add GOG-specific options like:
        // - Verify game files
        // - Check for updates
        // - View game on GOG.com
        // - Manage DLC

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
        // TODO: Add PostHog analytics for GOG game launches
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
     * GOG-specific dialogs (install confirmation, uninstall confirmation)
     */
    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit
    ) {
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
            val gameId = remember(libraryItem.appId) {
                ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
            }
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
            val gameId = remember(libraryItem.appId) {
                ContainerUtils.extractGameIdFromContainerId(libraryItem.appId).toString()
            }
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

/**
 * Hilt EntryPoint to access PluviaDatabase from non-Hilt components
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface DatabaseEntryPoint {
    fun database(): PluviaDatabase
}
