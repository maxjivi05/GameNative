package app.gamenative.service.gog

import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import timber.log.Timber

/**
 * Constants for GOG integration
 */
object GOGConstants {
    // GOG API URLs
    const val GOG_BASE_API_URL = "https://api.gog.com"
    const val GOG_AUTH_URL = "https://auth.gog.com"
    const val GOG_EMBED_URL = "https://embed.gog.com"
    const val GOG_GAMESDB_URL = "https://gamesdb.gog.com"

    // GOG Client ID for authentication
    const val GOG_CLIENT_ID = "46899977096215655"

    // GOG uses a standard redirect URI that we can intercept
    const val GOG_REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client"

    // GOG OAuth authorization URL with redirect
    const val GOG_AUTH_LOGIN_URL = "https://auth.gog.com/auth?client_id=$GOG_CLIENT_ID&redirect_uri=$GOG_REDIRECT_URI&response_type=code&layout=client2"

    // GOG paths - following Steam's structure pattern
    private const val INTERNAL_BASE_PATH = "/data/data/app.gamenative"

    /**
     * Internal GOG games installation path (similar to Steam's internal path)
     * /data/data/app.gamenative/files/GOG/games/common/
     */
    val internalGOGGamesPath: String
        get() = Paths.get(INTERNAL_BASE_PATH, "GOG", "games", "common").toString()

    /**
     * External GOG games installation path (similar to Steam's external path)
     * {externalStoragePath}/GOG/games/common/
     */
    val externalGOGGamesPath: String
        get() = Paths.get(PrefManager.externalStoragePath, "GOG", "games", "common").toString()

    val defaultGOGGamesPath: String
        get() {
            return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                Timber.i("GOG using external storage: $externalGOGGamesPath")
                externalGOGGamesPath
            } else {
                Timber.i("GOG using internal storage: $internalGOGGamesPath")
                internalGOGGamesPath
            }
        }

    fun getGameInstallPath(gameTitle: String): String {
        // Sanitize game title for filesystem
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        return Paths.get(defaultGOGGamesPath, sanitizedTitle).toString()
    }
}
