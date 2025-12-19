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
     * Get path to legendary's user.json auth file
     * This is where legendary stores Epic credentials
     */
    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/legendary_user.json"
    }

    /**
     * Check if user has stored Epic credentials
     */
    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
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

            // Call legendary auth via Python
            val pythonCode = """
import json
import sys
from legendary.core import LegendaryCore

try:
    core = LegendaryCore(override_config='$authConfigPath')
    success = core.auth_code('$actualCode')

    if success:
        # Get stored credentials from legendary
        userdata = core.lgd.get_userdata()
        print(json.dumps({
            "success": True,
            "access_token": userdata.get("access_token", ""),
            "account_id": userdata.get("account_id", ""),
            "display_name": userdata.get("displayName", "Epic User"),
            "expires_at": userdata.get("expires_at", "")
        }))
    else:
        print(json.dumps({"error": True, "message": "Authentication failed"}))
except Exception as e:
    print(json.dumps({"error": True, "message": str(e)}))
            """.trimIndent()

            val result = EpicPythonBridge.executePythonCode(context, pythonCode)

            Timber.d("Legendary auth result: isSuccess=${result.isSuccess}")

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                Timber.i("Legendary command completed, parsing authentication result...")
                return parseAuthenticationResult(authConfigPath, output)
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

            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Use legendary to get credentials - handles token refresh automatically
            val pythonCode = """
import json
from legendary.core import LegendaryCore

try:
    core = LegendaryCore(override_config='$authConfigPath')

    # login() will refresh tokens if needed
    if core.login():
        userdata = core.lgd.get_userdata()
        print(json.dumps({
            "access_token": userdata.get("access_token", ""),
            "account_id": userdata.get("account_id", ""),
            "display_name": userdata.get("displayName", "Epic User"),
            "expires_at": userdata.get("expires_at", "")
        }))
    else:
        print(json.dumps({"error": True, "message": "Login failed"}))
except Exception as e:
    print(json.dumps({"error": True, "message": str(e)}))
            """.trimIndent()

            val result = EpicPythonBridge.executePythonCode(context, pythonCode)

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                return parseCredentialsFromOutput(output)
            } else {
                Timber.e("Legendary credentials command failed")
                Result.failure(Exception("Failed to get credentials from Epic"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get stored credentials via legendary")
            Result.failure(e)
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
    core = LegendaryCore(override_config='$authConfigPath')
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
            val authFile = File(getAuthConfigPath(context))
            if (authFile.exists()) {
                authFile.delete()
                Timber.i("Epic credentials cleared")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Epic credentials")
            Result.failure(e)
        }
    }

    /**
     * Extract authorization code from input (handle both raw code and full URL)
     */
    private fun extractCodeFromInput(input: String): String {
        return if (input.contains("code=")) {
            val codeParam = input.substringAfter("code=", "")
            if (codeParam.isEmpty()) {
                ""
            } else {
                val cleanCode = codeParam.substringBefore("&")
                Timber.d("Extracted authorization code from URL")
                cleanCode
            }
        } else {
            input.trim()
        }
    }

    /**
     * Parse authentication result from legendary output
     */
    private fun parseAuthenticationResult(authConfigPath: String, output: String): Result<EpicCredentials> {
        return try {
            Timber.d("Parsing legendary authentication output (length: ${output.length})")
            val json = JSONObject(output.trim())

            if (json.has("error") && json.getBoolean("error")) {
                val errorMsg = json.optString("message", "Authentication failed")
                Timber.e("Authentication failed: $errorMsg")
                return Result.failure(Exception(errorMsg))
            }

            if (json.has("success") && json.getBoolean("success")) {
                val credentials = EpicCredentials(
                    accessToken = json.optString("access_token", ""),
                    refreshToken = json.optString("refresh_token", ""),
                    accountId = json.optString("account_id", ""),
                    displayName = json.optString("display_name", "Epic User"),
                    expiresAt = json.optLong("expires_at", 0L)
                )

                Timber.i("Authentication successful for user: ${credentials.displayName}")
                return Result.success(credentials)
            }

            Result.failure(Exception("Invalid authentication response"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse authentication result")
            Result.failure(e)
        }
    }

    /**
     * Parse credentials from legendary output
     */
    private fun parseCredentialsFromOutput(output: String): Result<EpicCredentials> {
        return try {
            val json = JSONObject(output.trim())

            if (json.has("error") && json.getBoolean("error")) {
                return Result.failure(Exception(json.optString("message", "Unknown error")))
            }

            val credentials = EpicCredentials(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", ""),
                accountId = json.optString("account_id", ""),
                displayName = json.optString("display_name", "Epic User"),
                expiresAt = json.optLong("expires_at", 0L)
            )

            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse credentials")
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
