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
     * Start background library sync
     * Progressively fetches and updates the GOG library in the background
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!GOGService.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("GOG").i("Starting GOG library background sync...")

            // Use the same refresh logic but don't block on completion
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
            if (!GOGService.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with GOG")
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            Timber.tag("GOG").i("Refreshing GOG library from GOG API...")

            // Fetch games from GOG via GOGDL Python backend
            val listResult = GOGService.listGames(context)

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

            // Log sample of fetched games
            games.take(3).forEach { game ->
                Timber.tag("GOG").d("""
                    |=== Fetched GOG Game ===
                    |ID: ${game.id}
                    |Title: ${game.title}
                    |Slug: ${game.slug}
                    |Developer: ${game.developer}
                    |Publisher: ${game.publisher}
                    |Description: ${game.description.take(100)}...
                    |Release Date: ${game.releaseDate}
                    |Image URL: ${game.imageUrl}
                    |Icon URL: ${game.iconUrl}
                    |Genres: ${game.genres.joinToString(", ")}
                    |Languages: ${game.languages.joinToString(", ")}
                    |Download Size: ${game.downloadSize}
                    |Install Size: ${game.installSize}
                    |Is Installed: ${game.isInstalled}
                    |Install Path: ${game.installPath}
                    |Type: ${game.type}
                    |=======================
                """.trimMargin())
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
}
