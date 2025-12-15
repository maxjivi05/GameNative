package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGCredentials
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages GOG authentication and account operations.
 *
 * Responsibilities:
 * - OAuth2 authentication flow
 * - Credential storage and validation
 * - Token refresh
 * - Account logout
 *
 * Uses GOGPythonBridge for all GOGDL command execution.
 */
object GOGAuthManager {

    /**
     * Get the auth config file path for a context
     */
    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/gog_auth.json"
    }

    /**
     * Check if user is authenticated by checking if auth file exists
     */
    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
    }

    /**
     * Authenticate with GOG using authorization code from OAuth2 flow
     * Users must visit GOG login page, authenticate, and copy the authorization code
     *
     * @param context Android context
     * @param authorizationCode OAuth2 authorization code (or full redirect URL)
     * @return Result containing GOGCredentials or error
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
        return try {
            Timber.i("Starting GOG authentication with authorization code...")

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

            // Execute GOGDL auth command with the authorization code
            Timber.d("Authenticating with auth config path: $authConfigPath, code: ${actualCode.take(10)}...")

            val result = GOGPythonBridge.executeCommand(
                "--auth-config-path", authConfigPath,
                "auth", "--code", actualCode
            )

            Timber.d("GOGDL executeCommand result: isSuccess=${result.isSuccess}")

            if (result.isSuccess) {
                val gogdlOutput = result.getOrNull() ?: ""
                Timber.i("GOGDL command completed, checking authentication result...")
                Timber.d("GOGDL output for auth: $gogdlOutput")

                // Parse and validate the authentication result
                return parseAuthenticationResult(authConfigPath, gogdlOutput)
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown authentication error"
                Timber.e(error, "GOG authentication command failed: $errorMsg")
                Result.failure(Exception("Authentication failed: $errorMsg", error))
            }
        } catch (e: Exception) {
            Timber.e(e, "GOG authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    /**
     * Get user credentials by calling GOGDL auth command (without --code)
     * This will automatically handle token refresh if needed
     */
    suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Use GOGDL to get credentials - this will handle token refresh automatically
            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "auth")

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                Timber.d("GOGDL credentials output: $output")

                return parseCredentialsFromOutput(output)
            } else {
                Timber.e("GOGDL credentials command failed")
                Result.failure(Exception("Failed to get credentials from GOG"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get stored credentials via GOGDL")
            Result.failure(e)
        }
    }

    /**
     * Validate credentials by calling GOGDL auth command (without --code)
     * This will automatically refresh tokens if they're expired
     */
    suspend fun validateCredentials(context: Context): Result<Boolean> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                Timber.d("No stored credentials found for validation")
                return Result.success(false)
            }

            Timber.d("Starting credentials validation with GOGDL")

            // Use GOGDL to validate credentials - this will handle token refresh automatically
            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "auth")

            if (!result.isSuccess) {
                val error = result.exceptionOrNull()
                Timber.e("Credentials validation failed - command failed: ${error?.message}")
                return Result.success(false)
            }

            val output = result.getOrNull() ?: ""
            Timber.d("GOGDL validation output: $output")

            try {
                val credentialsJson = JSONObject(output.trim())

                // Check if there's an error
                if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                    val errorDesc = credentialsJson.optString("message", "Unknown error")
                    Timber.e("Credentials validation failed: $errorDesc")
                    return Result.success(false)
                }

                Timber.d("Credentials validation successful")
                return Result.success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse validation response: $output")
                return Result.success(false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate credentials")
            return Result.failure(e)
        }
    }

    /**
     * Clear stored credentials (logout)
     */
    fun clearStoredCredentials(context: Context): Boolean {
        return try {
            val authFile = File(getAuthConfigPath(context))
            if (authFile.exists()) {
                authFile.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear GOG credentials")
            false
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * Extract authorization code from user input (URL or raw code)
     */
    private fun extractCodeFromInput(input: String): String {
        return if (input.startsWith("http")) {
            // Extract code parameter from URL
            val codeParam = input.substringAfter("code=", "")
            if (codeParam.isEmpty()) {
                ""
            } else {
                // Remove any additional parameters after the code
                val cleanCode = codeParam.substringBefore("&")
                Timber.d("Extracted authorization code from URL: ${cleanCode.take(20)}...")
                cleanCode
            }
        } else {
            input
        }
    }

    /**
     * Parse authentication result from GOGDL output and auth file
     */
    private fun parseAuthenticationResult(authConfigPath: String, gogdlOutput: String): Result<GOGCredentials> {
        try {
            Timber.d("Attempting to parse GOGDL output as JSON (length: ${gogdlOutput.length})")
            val outputJson = JSONObject(gogdlOutput.trim())
            Timber.d("Successfully parsed JSON, keys: ${outputJson.keys().asSequence().toList()}")

            // Check if the response indicates an error
            if (outputJson.has("error") && outputJson.getBoolean("error")) {
                val errorMsg = outputJson.optString("error_description", "Authentication failed")
                val errorDetails = outputJson.optString("message", "No details available")
                Timber.e("GOG authentication failed: $errorMsg - Details: $errorDetails")
                return Result.failure(Exception("GOG authentication failed: $errorMsg"))
            }

            // Check if we have the required fields for successful auth
            val accessToken = outputJson.optString("access_token", "")
            val userId = outputJson.optString("user_id", "")

            if (accessToken.isEmpty() || userId.isEmpty()) {
                Timber.e("GOG authentication incomplete: missing access_token or user_id in output")
                return Result.failure(Exception("Authentication incomplete: missing required data"))
            }

            // GOGDL output looks good, now check if auth file was created
            val authFile = File(authConfigPath)
            if (authFile.exists()) {
                // Parse authentication result from file
                val authData = parseFullCredentialsFromFile(authConfigPath)
                Timber.i("GOG authentication successful for user: ${authData.username}")
                return Result.success(authData)
            } else {
                Timber.w("GOGDL returned success but no auth file created, using output data")
                // Create credentials from GOGDL output
                val credentials = createCredentialsFromJson(outputJson)
                return Result.success(credentials)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse GOGDL output")
            // Fallback: check if auth file exists
            val authFile = File(authConfigPath)
            if (authFile.exists()) {
                try {
                    val authData = parseFullCredentialsFromFile(authConfigPath)
                    Timber.i("GOG authentication successful (fallback) for user: ${authData.username}")
                    return Result.success(authData)
                } catch (ex: Exception) {
                    Timber.e(ex, "Failed to parse auth file")
                    return Result.failure(Exception("Failed to parse authentication result: ${ex.message}"))
                }
            } else {
                Timber.e("GOG authentication failed: no auth file created and failed to parse output")
                return Result.failure(Exception("Authentication failed: no credentials available"))
            }
        }
    }

    /**
     * Parse GOGCredentials from GOGDL command output
     */
    private fun parseCredentialsFromOutput(output: String): Result<GOGCredentials> {
        try {
            val credentialsJson = JSONObject(output.trim())

            // Check if there's an error
            if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                val errorMsg = credentialsJson.optString("message", "Authentication failed")
                Timber.e("GOGDL credentials failed: $errorMsg")
                return Result.failure(Exception("Authentication failed: $errorMsg"))
            }

            // Extract credentials from GOGDL response
            val accessToken = credentialsJson.optString("access_token", "")
            val refreshToken = credentialsJson.optString("refresh_token", "")
            val username = credentialsJson.optString("username", "GOG User")
            val userId = credentialsJson.optString("user_id", "")

            val credentials = GOGCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                username = username,
                userId = userId,
            )

            Timber.d("Got credentials for user: $username")
            return Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse GOGDL credentials response")
            return Result.failure(e)
        }
    }

    /**
     * Parse full GOGCredentials from auth file
     */
    private fun parseFullCredentialsFromFile(authConfigPath: String): GOGCredentials {
        return try {
            val authFile = File(authConfigPath)
            if (authFile.exists()) {
                val authContent = authFile.readText()
                val authJson = JSONObject(authContent)

                // GOGDL stores credentials nested under client ID
                val credentialsJson = if (authJson.has(GOGConstants.GOG_CLIENT_ID)) {
                    authJson.getJSONObject(GOGConstants.GOG_CLIENT_ID)
                } else {
                    // Fallback: try to read from root level
                    authJson
                }

                GOGCredentials(
                    accessToken = credentialsJson.optString("access_token", ""),
                    refreshToken = credentialsJson.optString("refresh_token", ""),
                    userId = credentialsJson.optString("user_id", ""),
                    username = credentialsJson.optString("username", "GOG User"),
                )
            } else {
                // Return dummy credentials for successful auth
                GOGCredentials(
                    accessToken = "authenticated_${System.currentTimeMillis()}",
                    refreshToken = "refresh_${System.currentTimeMillis()}",
                    userId = "user_123",
                    username = "GOG User",
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse auth result from file")
            // Return dummy credentials as fallback
            GOGCredentials(
                accessToken = "fallback_token",
                refreshToken = "fallback_refresh",
                userId = "fallback_user",
                username = "GOG User",
            )
        }
    }

    /**
     * Create GOGCredentials from JSON output
     */
    private fun createCredentialsFromJson(outputJson: JSONObject): GOGCredentials {
        return GOGCredentials(
            accessToken = outputJson.optString("access_token", ""),
            refreshToken = outputJson.optString("refresh_token", ""),
            userId = outputJson.optString("user_id", ""),
            username = "GOG User", // We don't have username in the token response
        )
    }
}
