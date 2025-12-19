package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicCredentials
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages Epic Games authentication and account operations.
 *
 * - OAuth2 authorization code authentication
 * - Credential storage (via legendary's user.json)
 * - Token refresh (handled by legendary-android)
 * - Account logout
 *
 * Uses EpicPythonBridge + legendary.core for all operations.
 */
object EpicAuthManager {

    /**
     * Get path to legendary's config directory
     * Legendary stores user.json at: {configDir}/.config/legendary/user.json
     */
    fun getAuthConfigPath(context: Context): String {
        return context.filesDir.absolutePath
    }

    /**
     * Get the actual user.json file path where legendary stores credentials
     */
    private fun getUserJsonPath(context: Context): String {
        return "${context.filesDir}/.config/legendary/user.json"
    }

    /**
     * Check if user has stored Epic credentials
     */
    fun hasStoredCredentials(context: Context): Boolean {
        val userJsonFile = File(getUserJsonPath(context))
        return userJsonFile.exists()
    }

    /**
     * Extract authorization code from various input formats:
     * - Full URL: https://www.epicgames.com/id/api/redirect?code=abc123
     * - Just code: abc123
     */
    private fun extractCodeFromInput(input: String): String {
        val trimmed = input.trim()
        // Check if it's a URL with code parameter
        if (trimmed.startsWith("http")) {
            val codeMatch = Regex("[?&]code=([^&]+)").find(trimmed)
            return codeMatch?.groupValues?.get(1) ?: ""
        }
        // Otherwise assume it's already the code
        return trimmed
    }

    /**
     * Authenticate with Epic Games using authorization code from OAuth2 flow
     * Users must visit Epic login page, authenticate, and copy the authorization code
     *
     * @param context Android context
     * @param authorizationCode OAuth authorization code from Epic redirect
     * @return Result containing EpicCredentials on success, exception on failure
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
        return try {
            Timber.i("Starting Epic authentication with authorization code...")

            // Extract the actual authorization code from URL if needed
            val actualCode = extractCodeFromInput(authorizationCode)
            if (actualCode.isEmpty()) {
                return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
            }

            val authConfigPath = getAuthConfigPath(context)

            // Create auth config directory
            val authFile = File(authConfigPath)
            val authDir = authFile.parentFile
            if (authDir != null && !authDir.exists()) {
                authDir.mkdirs()
                Timber.d("Created auth config directory: ${authDir.absolutePath}")
            }

            // Use legendary Python module to authenticate
            Timber.d("Authenticating via legendary.core.auth_code()")

            // Call legendary auth via Python - just return success/failure
            val pythonCode = """
import json
from legendary.core import LegendaryCore

try:
    core = LegendaryCore()
    success = core.auth_code('$actualCode')
    print(json.dumps({"success": success}))
except Exception as e:
    print(json.dumps({"error": True, "message": str(e)}))
"""

            val result = EpicPythonBridge.executePythonCode(context, pythonCode)

            Timber.d("Legendary auth result: isSuccess=${result.isSuccess}")

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                Timber.d("Legendary auth output: $output")

                val json = JSONObject(output.trim())

                if (json.has("error") && json.getBoolean("error")) {
                    val errorMsg = json.optString("message", "Authentication failed")
                    Timber.e("Epic authentication failed: $errorMsg")
                    return Result.failure(Exception(errorMsg))
                }

                val success = json.optBoolean("success", false)
                if (success) {
                    Timber.i("Legendary authentication successful, reading credentials from file...")
                    // Read credentials from the file legendary created
                    val userJsonPath = getUserJsonPath(context)
                    val credentials = parseFullCredentialsFromFile(userJsonPath)
                    if (credentials != null) {
                        return Result.success(credentials)
                    } else {
                        return Result.failure(Exception("Authentication succeeded but failed to read credentials"))
                    }
                } else {
                    return Result.failure(Exception("Authentication failed"))
                }
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown authentication error"
                Timber.e(error, "Epic authentication command failed: $errorMsg")
                Result.failure(Exception("Authentication failed: $errorMsg", error))
            }
        } catch (e: Exception) {
            Timber.e(e, "Epic authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    /**
     * Get stored credentials by reading legendary's user.json
     * Will automatically refresh tokens if expired
     */
    suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
        return try {
            val authConfigPath = getAuthConfigPath(context)
            val userJsonPath = getUserJsonPath(context)

            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Use legendary to refresh tokens if needed, then read file
            val pythonCode = """
import json
from legendary.core import LegendaryCore

try:
    core = LegendaryCore()
    # login() will refresh tokens if needed
    success = core.login()
    print(json.dumps({"success": success}))
except Exception as e:
    print(json.dumps({"error": True, "message": str(e)}))
"""

            val result = EpicPythonBridge.executePythonCode(context, pythonCode)

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                Timber.d("Legendary login result: $output")

                val json = JSONObject(output.trim())

                if (json.has("error") && json.getBoolean("error")) {
                    val errorMsg = json.optString("message", "Login failed")
                    Timber.e("Epic credential refresh failed: $errorMsg")
                    return Result.failure(Exception(errorMsg))
                }

                val success = json.optBoolean("success", false)
                if (success) {
                    Timber.i("Legendary login successful, reading credentials from file...")
                    // Read credentials from the file
                    val credentials = parseFullCredentialsFromFile(userJsonPath)
                    if (credentials != null) {
                        return Result.success(credentials)
                    } else {
                        return Result.failure(Exception("Login succeeded but failed to read credentials"))
                    }
                } else {
                    return Result.failure(Exception("Login failed"))
                }
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown error"
                Timber.e(error, "Legendary credentials command failed: $errorMsg")
                Result.failure(Exception("Failed to get credentials: $errorMsg", error))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting Epic credentials: ${e.message}")
            Result.failure(Exception("Error getting credentials: ${e.message}", e))
        }
    }

    /**
     * Validate credentials by calling legendary login
     * This will automatically refresh tokens if they're expired
     */
    suspend fun validateCredentials(context: Context): Result<Boolean> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                Timber.d("No stored credentials found for validation")
                return Result.success(false)
            }

            Timber.d("Starting credentials validation with legendary")

            val pythonCode = """
import json
from legendary.core import LegendaryCore

try:
    core = LegendaryCore()
    success = core.login()
    print(json.dumps({"valid": success}))
except Exception as e:
    print(json.dumps({"valid": False, "error": str(e)}))
            """.trimIndent()

            val result = EpicPythonBridge.executePythonCode(context, pythonCode)

            if (!result.isSuccess) {
                return Result.success(false)
            }

            val output = result.getOrNull() ?: ""
            val json = JSONObject(output.trim())

            val isValid = json.optBoolean("valid", false)
            Timber.d("Credentials validation result: $isValid")

            Result.success(isValid)
        } catch (e: Exception) {
            Timber.w(e, "Credentials validation failed")
            Result.success(false)
        }
    }

    /**
     * Logout and clear stored credentials
     */
    suspend fun logout(context: Context): Result<Unit> {
        return try {
            val userJsonFile = File(getUserJsonPath(context))
            if (userJsonFile.exists()) {
                userJsonFile.delete()
                Timber.i("Epic credentials cleared")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Epic credentials")
            Result.failure(e)
        }
    }

    /**
     * Parse full credentials data directly from user.json file
     * Fallback method if Python command fails
     */
    private fun parseFullCredentialsFromFile(authConfigPath: String): EpicCredentials? {
        return try {
            val authFile = File(authConfigPath)
            if (!authFile.exists()) {
                Timber.w("Auth file does not exist: $authConfigPath")
                return null
            }

            val jsonContent = authFile.readText()
            val json = JSONObject(jsonContent)

            EpicCredentials(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", ""),
                accountId = json.optString("account_id", ""),
                displayName = json.optString("displayName", "Epic User"),
                expiresAt = json.optLong("expires_at", 0L)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse credentials from file")
            null
        }
    }
}
