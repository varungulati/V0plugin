package com.github.varungulati.v0plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.io.File
import java.net.URI
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import org.json.JSONArray
import java.awt.Desktop
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JProgressBar
import javax.swing.Timer
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.CookieHandler
import javax.swing.JScrollPane
import java.io.IOException
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.io.FileReader
import java.io.FileWriter
import java.io.StringWriter
import java.io.PrintWriter

@Service(Service.Level.PROJECT)
class V0AuthService(private val project: Project) {

    private val authDir = Paths.get(System.getProperty("user.home"), ".v0plugin")
    private val authFilePath = authDir.resolve("auth.json")
    private val cookiesFilePath = authDir.resolve("cookies.json")
    private val logFilePath = authDir.resolve("v0plugin_debug.log")
    private val executor = Executors.newSingleThreadExecutor()
    private val cookieManager = CookieManager()

    // Browser automation constants
    private val V0_URL = "https://v0.dev"
    private val LOGIN_TIMEOUT_SECONDS = 10L
    private val COOKIE_CHECK_INTERVAL_SECONDS = 5L

    // Browser detection
    private val CHROME_PATHS = mapOf(
        "win" to listOf(
            "${System.getenv("LOCALAPPDATA")}\\Google\\Chrome\\User Data",
            "${System.getenv("PROGRAMFILES")}\\Google\\Chrome\\Application\\chrome.exe",
            "${System.getenv("PROGRAMFILES(X86)")}\\Google\\Chrome\\Application\\chrome.exe"
        ),
        "mac" to listOf(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "${System.getProperty("user.home")}/Library/Application Support/Google/Chrome"
        ),
        "linux" to listOf(
            "${System.getProperty("user.home")}/.config/google-chrome",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium-browser"
        )
    )

    private val FIREFOX_PATHS = mapOf(
        "win" to listOf(
            "${System.getenv("APPDATA")}\\Mozilla\\Firefox\\Profiles",
            "${System.getenv("PROGRAMFILES")}\\Mozilla Firefox\\firefox.exe",
            "${System.getenv("PROGRAMFILES(X86)")}\\Mozilla Firefox\\firefox.exe"
        ),
        "mac" to listOf(
            "/Applications/Firefox.app/Contents/MacOS/firefox",
            "${System.getProperty("user.home")}/Library/Application Support/Firefox/Profiles"
        ),
        "linux" to listOf(
            "${System.getProperty("user.home")}/.mozilla/firefox",
            "/usr/bin/firefox"
        )
    )

    private val EDGE_PATHS = mapOf(
        "win" to listOf(
            "${System.getenv("LOCALAPPDATA")}\\Microsoft\\Edge\\User Data",
            "${System.getenv("PROGRAMFILES")}\\Microsoft\\Edge\\Application\\msedge.exe",
            "${System.getenv("PROGRAMFILES(X86)")}\\Microsoft\\Edge\\Application\\msedge.exe"
        ),
        "mac" to listOf(
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "${System.getProperty("user.home")}/Library/Application Support/Microsoft Edge"
        ),
        "linux" to listOf(
            "${System.getProperty("user.home")}/.config/microsoft-edge",
            "/usr/bin/microsoft-edge"
        )
    )

    init {
        // Create directory if it doesn't exist
        authDir.toFile().mkdirs()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)
        logDebug("V0AuthService initialized. Auth file path: $authFilePath")

        // Load cookies if available
        if (isLoggedIn()) {
            loadCookiesToManager()
        }
    }

    // Enhanced logging method that writes to both the IDE log and a debug file
    private fun logDebug(message: String) {
        try {
            // Log to IDE log
            thisLogger().info(message)

            // Also log to debug file
            val logFile = logFilePath.toFile()
            val timestamp = java.time.LocalDateTime.now().toString()
            val logMessage = "[$timestamp] $message\n"

            // Append to log file
            logFile.appendText(logMessage)
        } catch (e: Exception) {
            // If logging fails, at least try to log to IDE log
            thisLogger().error("Failed to write to debug log: ${e.message}")
        }
    }

    // Log exceptions with full stack trace
    private fun logException(message: String, e: Exception) {
        try {
            // Convert stack trace to string
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val stackTrace = sw.toString()

            // Log to IDE log
            thisLogger().error(message, e)

            // Also log to debug file
            val logFile = logFilePath.toFile()
            val timestamp = java.time.LocalDateTime.now().toString()
            val logMessage = "[$timestamp] ERROR: $message\n$stackTrace\n"

            // Append to log file
            logFile.appendText(logMessage)
        } catch (ex: Exception) {
            // If logging fails, at least try to log to IDE log
            thisLogger().error("Failed to write exception to debug log", ex)
        }
    }

    fun isLoggedIn(): Boolean {
        return authFilePath.toFile().exists() && isAuthValid()
    }

    private fun isAuthValid(): Boolean {
        try {
            if (!authFilePath.toFile().exists()) {
                logDebug("Auth file does not exist")
                return false
            }

            val authJson = JSONObject(authFilePath.toFile().readText())
            val timestamp = authJson.optLong("timestamp", 0)

            // Check if auth is older than 30 days
            val thirtyDaysInMillis = 30 * 24 * 60 * 60 * 1000L
            if (System.currentTimeMillis() - timestamp > thirtyDaysInMillis) {
                logDebug("Auth is expired")
                return false
            }

            // Check if cookies file exists
            if (!cookiesFilePath.toFile().exists()) {
                logDebug("Cookies file not found")
                return false
            }

            logDebug("Auth is valid")
            return true
        } catch (e: Exception) {
            logException("Error checking auth validity", e)
            return false
        }
    }

    // Modify the loginWithBrowser method to better handle already logged in users
    fun loginWithBrowser(onComplete: (Boolean) -> Unit) {
        val future = CompletableFuture.supplyAsync({
            try {
                logDebug("Starting login process")

                // Log system information for debugging
                logSystemInfo()

                // Show progress dialog
                val progressDialog = showLoginProgressDialog()

                // Check if cookies are already available before opening browser
                SwingUtilities.invokeLater {
                    progressDialog.updateProgress(0, "Checking for existing login...")
                }

                logDebug("Checking for existing login...")

                // Try to extract cookies immediately - user might already be logged in
                val cookiesAlreadyAvailable = extractCookiesFromBrowser()
                if (cookiesAlreadyAvailable) {
                    logDebug("User already logged in, cookies found")
                    progressDialog.dispose()
                    createAuthFile()
                    loadCookiesToManager()
                    return@supplyAsync true
                }

                logDebug("No existing login found, proceeding with browser login")

                // Open the default browser to v0.dev
                SwingUtilities.invokeLater {
                    progressDialog.updateProgress(10, "Opening browser for login...")
                }
                openBrowserToV0Dev()

                // Wait for user to complete login and automatically extract cookies
                val loginCompleted = AtomicBoolean(false)
                val startTime = System.currentTimeMillis()

                logDebug("Starting cookie extraction loop")

                var attemptCount = 0
                while (System.currentTimeMillis() - startTime < LOGIN_TIMEOUT_SECONDS * 1000 && !loginCompleted.get()) {
                    // Update progress
                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                    val remainingSeconds = LOGIN_TIMEOUT_SECONDS - elapsedSeconds
                    SwingUtilities.invokeLater {
                        progressDialog.updateProgress(
                            (elapsedSeconds * 100 / LOGIN_TIMEOUT_SECONDS).toInt(),
                            "Waiting for login completion... ($remainingSeconds seconds remaining)"
                        )
                    }

                    // Check if dialog was cancelled
                    if (progressDialog.isCancelled()) {
                        logDebug("Login process cancelled by user")
                        progressDialog.dispose()
                        return@supplyAsync false
                    }

                    // Try to extract cookies programmatically
                    attemptCount++
                    logDebug("Extraction attempt #$attemptCount")
                    val cookiesExtracted = extractCookiesFromBrowser()
                    if (cookiesExtracted) {
                        logDebug("Login completed with automatic cookie extraction on attempt #$attemptCount")
                        loginCompleted.set(true)
                        break
                    }

                    // Wait before next check
                    logDebug("Waiting ${COOKIE_CHECK_INTERVAL_SECONDS}s before next extraction attempt")
                    Thread.sleep(COOKIE_CHECK_INTERVAL_SECONDS * 1000)
                }

                // Close the progress dialog
                progressDialog.dispose()

                if (loginCompleted.get()) {
                    logDebug("Login completed with automatic cookie extraction")
                    createAuthFile()
                    loadCookiesToManager()
                    return@supplyAsync true
                } else {
                    logDebug("Automatic extraction failed after $attemptCount attempts")

                    // If automatic extraction failed, ask user if they completed login
                    val manualConfirmation = showLoginConfirmationDialog()
                    if (manualConfirmation) {
                        logDebug("User confirmed login completion, trying extraction one more time")

                        // Try one more time to extract cookies
                        val cookiesExtracted = extractCookiesFromBrowser()
                        if (cookiesExtracted) {
                            logDebug("Extraction succeeded after manual confirmation")
                            createAuthFile()
                            loadCookiesToManager()
                            return@supplyAsync true
                        } else {
                            logDebug("Extraction still failed after manual confirmation, falling back to manual input")

                            // If still failed, fall back to manual cookie input
                            val manualCookiesExtracted = showCookieInputDialog()
                            if (manualCookiesExtracted) {
                                logDebug("Manual cookie input successful")
                                createAuthFile()
                                loadCookiesToManager()
                                return@supplyAsync true
                            } else {
                                logDebug("Manual cookie input cancelled or failed")
                                return@supplyAsync false
                            }
                        }
                    } else {
                        logDebug("Login cancelled by user after automatic extraction failed")
                        return@supplyAsync false
                    }
                }
            } catch (e: Exception) {
                logException("Error during login process", e)
                return@supplyAsync false
            }
        }, executor)

        future.thenAccept { success ->
            logDebug("Login process completed with result: $success")
            onComplete(success)
        }
    }

    private fun logSystemInfo() {
        try {
            val os = System.getProperty("os.name")
            val osVersion = System.getProperty("os.version")
            val osArch = System.getProperty("os.arch")
            val javaVersion = System.getProperty("java.version")
            val userHome = System.getProperty("user.home")
            val userDir = System.getProperty("user.dir")

            logDebug("System Information:")
            logDebug("OS: $os version $osVersion ($osArch)")
            logDebug("Java Version: $javaVersion")
            logDebug("User Home: $userHome")
            logDebug("Working Directory: $userDir")

            // Log browser paths
            val osType = getOSType()
            logDebug("OS Type for browser detection: $osType")

            logDebug("Chrome paths to check:")
            CHROME_PATHS[osType]?.forEach { path ->
                val exists = File(path).exists()
                logDebug("  $path - ${if (exists) "EXISTS" else "NOT FOUND"}")
            }

            logDebug("Firefox paths to check:")
            FIREFOX_PATHS[osType]?.forEach { path ->
                val exists = File(path).exists()
                logDebug("  $path - ${if (exists) "EXISTS" else "NOT FOUND"}")
            }

            logDebug("Edge paths to check:")
            EDGE_PATHS[osType]?.forEach { path ->
                val exists = File(path).exists()
                logDebug("  $path - ${if (exists) "EXISTS" else "NOT FOUND"}")
            }
        } catch (e: Exception) {
            logException("Error logging system information", e)
        }
    }

    private fun showLoginProgressDialog(): LoginProgressDialog {
        val dialog = LoginProgressDialog("V0.dev Login", "Opening browser for login...")
        SwingUtilities.invokeLater {
            dialog.isVisible = true
        }
        return dialog
    }

    private fun openBrowserToV0Dev() {
        logDebug("Opening browser to v0.dev")

        try {
            val url = URI(V0_URL).toURL()
            val desktop = Desktop.getDesktop()

            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                logDebug("Using Desktop.browse() to open browser")
                desktop.browse(url.toURI())
                logDebug("Browser opened successfully via Desktop API")
            } else {
                logDebug("Desktop browsing not supported, trying alternative method")

                // Try alternative methods based on OS
                val os = System.getProperty("os.name").lowercase()
                val runtime = Runtime.getRuntime()

                when {
                    os.contains("win") -> {
                        logDebug("Using rundll32 to open browser on Windows")
                        runtime.exec("rundll32 url.dll,FileProtocolHandler " + url)
                    }
                    os.contains("mac") -> {
                        logDebug("Using 'open' command to open browser on macOS")
                        runtime.exec("open " + url)
                    }
                    os.contains("nix") || os.contains("nux") -> {
                        // Try common browsers
                        val browsers = arrayOf("xdg-open", "google-chrome", "firefox", "mozilla", "opera")
                        var found = false

                        for (browser in browsers) {
                            try {
                                logDebug("Trying to open browser with: $browser")
                                runtime.exec(arrayOf(browser, url.toString()))
                                found = true
                                logDebug("Successfully opened browser with: $browser")
                                break
                            } catch (e: Exception) {
                                logDebug("Failed to open browser with $browser: ${e.message}")
                                // Try next browser
                            }
                        }

                        if (!found) {
                            throw Exception("No browser found")
                        }
                    }
                    else -> {
                        throw Exception("Unsupported operating system")
                    }
                }
            }
        } catch (e: Exception) {
            logException("Failed to open browser", e)
            throw RuntimeException("Failed to open browser: ${e.message}")
        }
    }

    // Modify the extractCookiesFromBrowser method to prioritize API method and improve detection
    private fun extractCookiesFromBrowser(): Boolean {
        try {
            logDebug("Attempting to extract cookies from browser - user is already logged in")

            // Try direct API method first since it's most reliable for already logged in users
            logDebug("Trying API method first (most reliable for already logged in users)")
            try {
                val success = extractCookiesViaAPI()
                if (success) {
                    logDebug("Successfully extracted cookies using API method")
                    return true
                } else {
                    logDebug("API method did not find valid cookies")
                }
            } catch (e: Exception) {
                logException("Failed to extract cookies using API method", e)
            }

            // Try different browser-specific methods
            val methods = listOf(
                this::extractCookiesFromChrome,
                this::extractCookiesFromFirefox,
                this::extractCookiesFromEdge
            )

            for (method in methods) {
                try {
                    logDebug("Trying extraction method: ${method.name}")
                    val success = method()
                    if (success) {
                        logDebug("Successfully extracted cookies using ${method.name}")
                        return true
                    } else {
                        logDebug("Method ${method.name} did not find valid cookies")
                    }
                } catch (e: Exception) {
                    logException("Failed to extract cookies using ${method.name}", e)
                }
            }

            // Try one more time with API method with different parameters
            logDebug("Trying API method again with different parameters")
            try {
                val success = extractCookiesViaAPIAlternative()
                if (success) {
                    logDebug("Successfully extracted cookies using alternative API method")
                    return true
                } else {
                    logDebug("Alternative API method did not find valid cookies")
                }
            } catch (e: Exception) {
                logException("Failed to extract cookies using alternative API method", e)
            }

            logDebug("All extraction methods failed")
            return false
        } catch (e: Exception) {
            logException("Error in extractCookiesFromBrowser", e)
            return false
        }
    }

    // Add a new method that tries a different approach to API extraction
    private fun extractCookiesViaAPIAlternative(): Boolean {
        try {
            logDebug("Attempting to extract cookies via alternative API approach")

            // Create a cookie manager for this request
            val localCookieManager = CookieManager()
            localCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            val originalCookieHandler = CookieHandler.getDefault()
            CookieHandler.setDefault(localCookieManager)

            try {
                // Try different endpoints that might have cookies
                val endpoints = listOf(
                    "$V0_URL/",                // Home page
                    "$V0_URL/chat",            // Chat page
                    "$V0_URL/api/auth/session" // Auth session endpoint
                )

                for (endpoint in endpoints) {
                    logDebug("Making request to: $endpoint")

                    val url = URL(endpoint)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = true  // Allow redirects
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    // Add some headers to make the request more browser-like
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

                    // Check response code
                    val responseCode = connection.responseCode
                    logDebug("Response code from $endpoint: $responseCode")

                    // Get cookies from the cookie store after the request
                    val domainCookies = localCookieManager.cookieStore.get(URI(V0_URL))
                    val cookies = mutableListOf<HttpCookie>()

                    if (domainCookies != null && domainCookies.isNotEmpty()) {
                        logDebug("Found ${domainCookies.size} cookies in cookie store for $endpoint")
                        cookies.addAll(domainCookies)

                        // Log all cookies for debugging
                        logDebug("Cookies found from $endpoint (${cookies.size}):")
                        cookies.forEach { cookie ->
                            logDebug("  ${cookie.name}: ${cookie.value.take(10)}... (domain: ${cookie.domain}, path: ${cookie.path})")
                        }

                        // Check if we have auth cookies
                        if (hasAuthCookies(cookies)) {
                            logDebug("Found auth cookies from $endpoint")
                            saveCookies(cookies)
                            return true
                        }
                    } else {
                        logDebug("No cookies found in cookie store for $endpoint")
                    }
                }

                // If we get here, we couldn't find auth cookies
                logDebug("No valid auth cookies found in alternative API approach")
                return false
            } finally {
                // Restore original cookie handler
                CookieHandler.setDefault(originalCookieHandler)
            }
        } catch (e: Exception) {
            logException("Error extracting cookies via alternative API", e)
            return false
        }
    }

    private fun extractCookiesFromChrome(): Boolean {
        val os = getOSType()
        val chromePaths = CHROME_PATHS[os] ?: return false

        logDebug("Attempting to extract cookies from Chrome")

        // Find Chrome user data directory
        var userDataDir: File? = null
        for (path in chromePaths) {
            val file = File(path)
            logDebug("Checking Chrome path: $path (exists: ${file.exists()}, isDir: ${file.isDirectory})")
            if (file.exists() && file.isDirectory && path.contains("User Data")) {
                userDataDir = file
                logDebug("Found Chrome user data directory: ${file.absolutePath}")
                break
            }
        }

        if (userDataDir == null) {
            logDebug("Chrome user data directory not found")
            return false
        }

        // Find Default profile or other profiles
        val profileDirs = listOf("Default", "Profile 1", "Profile 2")
        for (profile in profileDirs) {
            val cookiesDb = File(userDataDir, "$profile/Network/Cookies")
            logDebug("Checking for cookies database at: ${cookiesDb.absolutePath} (exists: ${cookiesDb.exists()})")
            if (cookiesDb.exists()) {
                logDebug("Found Chrome cookies database at ${cookiesDb.absolutePath}")

                // Chrome uses SQLite database for cookies, which requires a library to read
                // For simplicity, we'll use a different approach

                // Try to use Chrome's dev tools protocol or other methods
                logDebug("Chrome cookies database found, but direct reading not implemented. Falling back to API method.")
                return extractCookiesViaAPI()
            }
        }

        logDebug("No Chrome cookies database found")
        return false
    }

    private fun extractCookiesFromFirefox(): Boolean {
        val os = getOSType()
        val firefoxPaths = FIREFOX_PATHS[os] ?: return false

        logDebug("Attempting to extract cookies from Firefox")

        // Find Firefox profiles directory
        var profilesDir: File? = null
        for (path in firefoxPaths) {
            val file = File(path)
            logDebug("Checking Firefox path: $path (exists: ${file.exists()}, isDir: ${file.isDirectory})")
            if (file.exists() && file.isDirectory && (path.contains("Profiles") || path.contains("firefox"))) {
                profilesDir = file
                logDebug("Found Firefox profiles directory: ${file.absolutePath}")
                break
            }
        }

        if (profilesDir == null) {
            logDebug("Firefox profiles directory not found")
            return false
        }

        // Firefox also uses SQLite for cookies
        // For simplicity, we'll use a different approach
        logDebug("Firefox profiles found, but direct reading not implemented. Falling back to API method.")
        return extractCookiesViaAPI()
    }

    private fun extractCookiesFromEdge(): Boolean {
        val os = getOSType()
        val edgePaths = EDGE_PATHS[os] ?: return false

        logDebug("Attempting to extract cookies from Edge")

        // Find Edge user data directory
        var userDataDir: File? = null
        for (path in edgePaths) {
            val file = File(path)
            logDebug("Checking Edge path: $path (exists: ${file.exists()}, isDir: ${file.isDirectory})")
            if (file.exists() && file.isDirectory && path.contains("User Data")) {
                userDataDir = file
                logDebug("Found Edge user data directory: ${file.absolutePath}")
                break
            }
        }

        if (userDataDir == null) {
            logDebug("Edge user data directory not found")
            return false
        }

        // Edge is Chromium-based, so similar to Chrome
        logDebug("Edge user data directory found, but direct reading not implemented. Falling back to API method.")
        return extractCookiesViaAPI()
    }

    // Improve the extractCookiesViaAPI method to better detect existing cookies
    private fun extractCookiesViaAPI(): Boolean {
        try {
            logDebug("Attempting to extract cookies via API")

            // Create a cookie manager for this request
            val localCookieManager = CookieManager()
            localCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            val originalCookieHandler = CookieHandler.getDefault()
            CookieHandler.setDefault(localCookieManager)

            try {
                // Make a request to v0.dev to check if we're logged in
                val url = URL("$V0_URL/api/user")
                logDebug("Making request to: $url")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true  // Changed to true to follow redirects
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // Add some headers to make the request more browser-like
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

                // Check response code
                val responseCode = connection.responseCode
                logDebug("Response code: $responseCode")

                // Log response headers for debugging
                logDebug("Response headers:")
                connection.headerFields.forEach { (key, values) ->
                    logDebug("  $key: $values")
                }

                // Get cookies from the response
                val cookieHeader = connection.headerFields["Set-Cookie"]
                val cookies = mutableListOf<HttpCookie>()

                if (cookieHeader != null) {
                    logDebug("Found Set-Cookie headers: ${cookieHeader.size}")
                    for (header in cookieHeader) {
                        logDebug("  Processing cookie header: $header")
                        val parsedCookies = HttpCookie.parse(header)
                        logDebug("  Parsed ${parsedCookies.size} cookies from header")
                        cookies.addAll(parsedCookies)
                    }
                } else {
                    logDebug("No Set-Cookie headers found")
                }

                // Also get cookies from the cookie store
                val domainCookies = localCookieManager.cookieStore.get(URI(V0_URL))
                if (domainCookies != null && domainCookies.isNotEmpty()) {
                    logDebug("Found ${domainCookies.size} cookies in cookie store")
                    cookies.addAll(domainCookies)
                } else {
                    logDebug("No cookies found in cookie store")
                }

                // Log all cookies for debugging
                logDebug("All cookies found (${cookies.size}):")
                cookies.forEach { cookie ->
                    logDebug("  ${cookie.name}: ${cookie.value.take(10)}... (domain: ${cookie.domain}, path: ${cookie.path})")
                }

                // Check if we have auth cookies regardless of response code
                if (hasAuthCookies(cookies)) {
                    logDebug("Found auth cookies, saving them")
                    saveCookies(cookies)
                    return true
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Read response
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }

                    reader.close()

                    // Log response for debugging (truncated for large responses)
                    val responseStr = response.toString()
                    logDebug("Response body (truncated): ${responseStr.take(500)}${if (responseStr.length > 500) "..." else ""}")

                    // Check if response indicates we're logged in
                    try {
                        val responseJson = JSONObject(responseStr)
                        logDebug("Response parsed as JSON successfully")

                        if (responseJson.has("user") && !responseJson.isNull("user")) {
                            logDebug("Found user object in response, user is logged in")
                            // We're logged in, save cookies
                            saveCookies(cookies)
                            return true
                        } else {
                            logDebug("No user object found in response, user is not logged in")
                        }
                    } catch (e: Exception) {
                        logException("Failed to parse response as JSON", e)
                    }
                }

                // If we get here, we're not logged in yet or couldn't detect login
                logDebug("No valid login detected")
                return false
            } finally {
                // Restore original cookie handler
                CookieHandler.setDefault(originalCookieHandler)
            }
        } catch (e: Exception) {
            logException("Error extracting cookies via API", e)
            return false
        }
    }

    // Add a helper method to check if we have authentication cookies
    private fun hasAuthCookies(cookies: List<HttpCookie>): Boolean {
        // Look for common auth cookie names
        val authCookieNames = listOf(
            "next-auth.session-token",
            "next-auth.csrf-token",
            "__Secure-next-auth.session-token",
            "v0_session",
            "v0_auth",
            "v0_token",
            "__Host-next-auth.csrf-token",
            "next-auth.callback-url",
            "__Secure-next-auth.callback-url",
            "vercel",
            "__Secure-vercel",
            "__Host-vercel",
            "auth",
            "session",
            "token",
            "user_session",
            "v0.auth",
            "v0.session",
            "v0.token",
            "v0-token",
            "v0-session",
            "v0-auth"
        )

        logDebug("Checking for auth cookies among ${cookies.size} cookies")

        for (cookie in cookies) {
            if (authCookieNames.contains(cookie.name) ||
                cookie.name.contains("auth") ||
                cookie.name.contains("token") ||
                cookie.name.contains("session")) {
                logDebug("Found auth cookie: ${cookie.name}")
                return true
            }
        }

        logDebug("No auth cookies found")
        return false
    }

    private fun saveCookies(cookies: List<HttpCookie>) {
        try {
            logDebug("Saving ${cookies.size} cookies to file")

            val cookiesArray = JSONArray()

            for (cookie in cookies) {
                val cookieJson = JSONObject()
                cookieJson.put("name", cookie.name)
                cookieJson.put("value", cookie.value)
                cookieJson.put("domain", cookie.domain ?: "v0.dev")
                cookieJson.put("path", cookie.path ?: "/")
                cookieJson.put("expires", cookie.maxAge)
                cookieJson.put("httpOnly", cookie.isHttpOnly)
                cookieJson.put("secure", cookie.secure)

                cookiesArray.put(cookieJson)

                logDebug("Saved cookie: ${cookie.name} (domain: ${cookie.domain ?: "v0.dev"}, path: ${cookie.path ?: "/"})")
            }

            cookiesFilePath.toFile().writeText(cookiesArray.toString(2))
            logDebug("Saved ${cookies.size} cookies to $cookiesFilePath")
        } catch (e: Exception) {
            logException("Error saving cookies", e)
            throw e
        }
    }

    private fun showLoginConfirmationDialog(): Boolean {
        val resultHolder = arrayOf(false)

        // We need to show the dialog on the EDT
        SwingUtilities.invokeAndWait {
            val result = JOptionPane.showConfirmDialog(
                null,
                "Have you completed the login process in the browser?\n\n" +
                "Click 'Yes' once you've successfully logged in to V0.dev.",
                "V0.dev Login Confirmation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )

            resultHolder[0] = (result == JOptionPane.YES_OPTION)
        }

        return resultHolder[0]
    }

    private fun showCookieInputDialog(): Boolean {
        val resultHolder = arrayOf(false)

        SwingUtilities.invokeAndWait {
            val dialog = JDialog()
            dialog.title = "V0.dev Cookie Input"
            dialog.isModal = true
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

            val panel = JPanel(BorderLayout(10, 10))
            panel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)

            // Instructions
            val instructionsText = """
                Automatic cookie extraction failed. Please provide your cookies manually:
                
                1. Log in to V0.dev in your browser
                2. Open Developer Tools (F12 or right-click > Inspect)
                3. Go to the "Application" or "Storage" tab
                4. Find "Cookies" in the left sidebar and click on "https://v0.dev"
                5. Copy all cookies and paste them below
                
                Format: Either paste the raw cookies or in JSON format
            """.trimIndent()

            val instructionsLabel = JLabel("<html>" + instructionsText.replace("\n", "<br>") + "</html>")

            // Cookie input area
            val cookieTextArea = JTextArea(10, 40)
            cookieTextArea.lineWrap = true
            cookieTextArea.wrapStyleWord = true

            // Buttons
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            val cancelButton = JButton("Cancel")
            val submitButton = JButton("Submit")

            cancelButton.addActionListener {
                dialog.dispose()
            }

            submitButton.addActionListener {
                val cookiesText = cookieTextArea.text.trim()
                if (cookiesText.isNotEmpty()) {
                    try {
                        saveCookiesFromText(cookiesText)
                        resultHolder[0] = true
                        dialog.dispose()
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(
                            dialog,
                            "Error parsing cookies: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                } else {
                    JOptionPane.showMessageDialog(
                        dialog,
                        "Please enter your cookies",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }

            buttonPanel.add(cancelButton)
            buttonPanel.add(submitButton)

            panel.add(instructionsLabel, BorderLayout.NORTH)
            panel.add(JScrollPane(cookieTextArea), BorderLayout.CENTER)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            dialog.contentPane = panel
            dialog.pack()
            dialog.setLocationRelativeTo(null)
            dialog.isVisible = true
        }

        return resultHolder[0]
    }

    private fun saveCookiesFromText(cookiesText: String) {
        try {
            logDebug("Parsing cookies from text input")

            val cookiesArray = JSONArray()

            // Try to parse as JSON first
            try {
                if (cookiesText.trim().startsWith("[") && cookiesText.trim().endsWith("]")) {
                    logDebug("Parsing input as JSON array")
                    val jsonArray = JSONArray(cookiesText)
                    for (i in 0 until jsonArray.length()) {
                        cookiesArray.put(jsonArray.getJSONObject(i))
                    }
                    logDebug("Successfully parsed ${cookiesArray.length()} cookies from JSON array")
                } else if (cookiesText.trim().startsWith("{") && cookiesText.trim().endsWith("}")) {
                    logDebug("Parsing input as JSON object")
                    val jsonObject = JSONObject(cookiesText)
                    cookiesArray.put(jsonObject)
                    logDebug("Successfully parsed 1 cookie from JSON object")
                }
            } catch (e: Exception) {
                // Not valid JSON, try other formats
                logDebug("Not valid JSON, trying other formats: ${e.message}")
            }

            // If not parsed as JSON, try other formats
            if (cookiesArray.length() == 0) {
                logDebug("Trying to parse as name=value pairs or tab-delimited format")

                // Try parsing as name=value pairs
                val lines = cookiesText.split("\n")
                for (line in lines) {
                    if (line.isBlank()) continue

                    // Check if it's in the format from browser dev tools
                    if (line.contains("\t")) {
                        logDebug("Parsing tab-delimited line: $line")
                        val parts = line.split("\t")
                        if (parts.size >= 2) {
                            val cookieJson = JSONObject()
                            cookieJson.put("name", parts[0].trim())
                            cookieJson.put("value", parts[1].trim())
                            if (parts.size > 2) cookieJson.put("domain", parts[2].trim())
                            if (parts.size > 3) cookieJson.put("path", parts[3].trim())
                            cookiesArray.put(cookieJson)
                            logDebug("Added cookie: ${parts[0].trim()}")
                        }
                    } else if (line.contains("=")) {
                        logDebug("Parsing name=value line: $line")
                        // Simple name=value format
                        val parts = line.split("=", limit = 2)
                        val cookieJson = JSONObject()
                        cookieJson.put("name", parts[0].trim())
                        cookieJson.put("value", parts[1].trim())
                        cookieJson.put("domain", "v0.dev")
                        cookieJson.put("path", "/")
                        cookiesArray.put(cookieJson)
                        logDebug("Added cookie: ${parts[0].trim()}")
                    }
                }
            }

            if (cookiesArray.length() == 0) {
                logDebug("Could not parse any cookies from input")
                throw Exception("Could not parse cookies from input")
            }

            // Save cookies to file
            logDebug("Saving ${cookiesArray.length()} parsed cookies to file")
            val file = cookiesFilePath.toFile()
            val outputStream = FileOutputStream(file)
            val writer = OutputStreamWriter(outputStream)
            writer.write(cookiesArray.toString(2))
            writer.flush()
            writer.close()

            logDebug("Saved ${cookiesArray.length()} cookies to $cookiesFilePath")
        } catch (e: Exception) {
            logException("Error saving cookies from text", e)
            throw e
        }
    }

    private fun createAuthFile() {
        try {
            logDebug("Creating auth file")

            // Create a simple auth file with the current timestamp
            val authJson = JSONObject()
            authJson.put("timestamp", System.currentTimeMillis())
            authJson.put("cookiesFile", cookiesFilePath.toAbsolutePath().toString())

            authFilePath.toFile().writeText(authJson.toString(2))
            logDebug("Created auth file at $authFilePath")
        } catch (e: Exception) {
            logException("Error creating auth file", e)
            throw e
        }
    }

    fun getCookies(): List<HttpCookie> {
        val cookies = mutableListOf<HttpCookie>()

        try {
            if (!cookiesFilePath.toFile().exists()) {
                logDebug("Cookies file does not exist")
                return cookies
            }

            logDebug("Loading cookies from file: $cookiesFilePath")
            val cookiesJson = JSONArray(cookiesFilePath.toFile().readText())

            for (i in 0 until cookiesJson.length()) {
                val cookieJson = cookiesJson.getJSONObject(i)
                val cookie = HttpCookie(cookieJson.getString("name"), cookieJson.getString("value"))

                if (cookieJson.has("domain")) {
                    cookie.domain = cookieJson.getString("domain")
                }
                if (cookieJson.has("path")) {
                    cookie.path = cookieJson.getString("path")
                }
                if (cookieJson.has("httpOnly")) {
                    cookie.isHttpOnly = cookieJson.getBoolean("httpOnly")
                }
                if (cookieJson.has("secure")) {
                    cookie.secure = cookieJson.getBoolean("secure")
                }

                cookies.add(cookie)
                logDebug("Loaded cookie: ${cookie.name} (domain: ${cookie.domain}, path: ${cookie.path})")
            }

            logDebug("Loaded ${cookies.size} cookies from file")
        } catch (e: Exception) {
            logException("Error loading cookies", e)
        }

        return cookies
    }

    private fun loadCookiesToManager() {
        try {
            logDebug("Loading cookies to cookie manager")

            val cookies = getCookies()
            for (cookie in cookies) {
                cookieManager.cookieStore.add(URI(V0_URL), cookie)
            }
            logDebug("Loaded ${cookies.size} cookies to cookie manager")
        } catch (e: Exception) {
            logException("Error loading cookies to manager", e)
        }
    }

    fun logout() {
        try {
            logDebug("Logging out")

            val authFile = authFilePath.toFile()
            val cookiesFile = cookiesFilePath.toFile()

            if (authFile.exists()) {
                authFile.delete()
                logDebug("Deleted auth file")
            }

            if (cookiesFile.exists()) {
                cookiesFile.delete()
                logDebug("Deleted cookies file")
            }

            // Clear cookie manager
            cookieManager.cookieStore.removeAll()
            logDebug("Cleared cookie manager")

            logDebug("Logged out successfully")
        } catch (e: Exception) {
            logException("Error during logout", e)
        }
    }

    fun getAuthFilePath(): Path {
        return authFilePath
    }

    private fun getOSType(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "win"
            os.contains("mac") -> "mac"
            os.contains("nix") || os.contains("nux") -> "linux"
            else -> "unknown"
        }
    }

    // Inner class for login progress dialog
    private class LoginProgressDialog(title: String, initialMessage: String) : JDialog() {
        private val progressBar = JProgressBar(0, 100)
        private val messageLabel = JLabel(initialMessage)
        private val cancelled = AtomicBoolean(false)

        init {
            this.title = title
            this.isModal = false
            this.defaultCloseOperation = DISPOSE_ON_CLOSE

            val panel = JPanel(BorderLayout(10, 10))
            panel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)

            messageLabel.horizontalAlignment = JLabel.CENTER
            progressBar.isIndeterminate = false
            progressBar.value = 0
            progressBar.preferredSize = Dimension(300, 20)

            panel.add(messageLabel, BorderLayout.NORTH)
            panel.add(progressBar, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            val cancelButton = JButton("Cancel")
            cancelButton.addActionListener {
                cancelled.set(true)
                dispose()
            }
            buttonPanel.add(cancelButton)
            panel.add(buttonPanel, BorderLayout.SOUTH)

            contentPane = panel
            pack()
            setLocationRelativeTo(null)
        }

        fun updateProgress(progress: Int, message: String) {
            progressBar.value = progress
            messageLabel.text = message
        }

        fun isCancelled(): Boolean {
            return cancelled.get()
        }
    }
}
