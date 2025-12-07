package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGGame
import app.gamenative.db.dao.GOGGameDao
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for GOG library operations - fetching, caching, and syncing the user's GOG library
 */
@Singleton
class GOGLibraryManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
) {
    /**
     * Get a GOG game by ID from database
     */
    suspend fun getGameById(gameId: String): GOGGame? {
        return withContext(Dispatchers.IO) {
            gogGameDao.getById(gameId)
        }
    }

    /**
     * Start background library sync
     * TODO: Implement full progressive library fetching from GOG API
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!GOGService.hasStoredCredentials(context)) {
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.i("Starting GOG library background sync...")

            // TODO: Implement progressive library fetching like in the branch
            // This should:
            // 1. Call getUserLibraryProgressively
            // 2. Fetch games one by one from GOG API
            // 3. Enrich with GamesDB metadata
            // 4. Update database using gogGameDao.upsertPreservingInstallStatus()

            Timber.w("GOG library sync not yet fully implemented")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync GOG library")
            Result.failure(e)
        }
    }

    /**
     * Refresh the entire library (called manually by user)
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!GOGService.hasStoredCredentials(context)) {
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            // TODO: Implement full library refresh
            Timber.i("Refreshing GOG library...")
            Result.success(0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh GOG library")
            Result.failure(e)
        }
    }
}
