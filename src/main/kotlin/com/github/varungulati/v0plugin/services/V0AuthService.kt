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

@Service(Service.Level.PROJECT)
class V0AuthService(private val project: Project) {

    private val authFilePath = Paths.get(System.getProperty("user.home"), ".v0plugin", "auth.json")
    private val executor = Executors.newSingleThreadExecutor()

    init {
        // Create directory if it doesn't exist
        authFilePath.parent.toFile().mkdirs()
        thisLogger().info("V0AuthService initialized. Auth file path: $authFilePath")
    }

    fun isLoggedIn(): Boolean {
        return authFilePath.toFile().exists()
    }

    fun loginWithBrowser(onComplete: (Boolean) -> Unit) {
        val future = CompletableFuture.supplyAsync({
            try {
                // Instead of using Playwright, let's use a simpler approach
                // Open the default browser to v0.dev
                openBrowserToV0Dev()

                // Show a dialog to ask the user if they've completed login
                val result = showLoginConfirmationDialog()

                if (result) {
                    // User confirmed login, create a simple auth file
                    createSimpleAuthFile()
                    thisLogger().info("✅ Auth state saved to $authFilePath")
                    true
                } else {
                    thisLogger().info("Login cancelled by user")
                    false
                }
            } catch (e: Exception) {
                thisLogger().error("Error during login process", e)
                false
            }
        }, executor)

        future.thenAccept { success ->
            onComplete(success)
        }
    }

    private fun openBrowserToV0Dev() {
        thisLogger().info("Opening browser to v0.dev")

        try {
            val url = URI("https://v0.dev").toURL()
            val desktop = java.awt.Desktop.getDesktop()

            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(url.toURI())
                thisLogger().info("Browser opened successfully")
            } else {
                thisLogger().warn("Desktop browsing not supported, trying alternative method")

                // Try alternative methods based on OS
                val os = System.getProperty("os.name").lowercase()
                val runtime = Runtime.getRuntime()

                when {
                    os.contains("win") -> {
                        runtime.exec("rundll32 url.dll,FileProtocolHandler " + url)
                    }
                    os.contains("mac") -> {
                        runtime.exec("open " + url)
                    }
                    os.contains("nix") || os.contains("nux") -> {
                        // Try common browsers
                        val browsers = arrayOf("xdg-open", "google-chrome", "firefox", "mozilla", "opera")
                        var found = false

                        for (browser in browsers) {
                            try {
                                runtime.exec(arrayOf(browser, url.toString()))
                                found = true
                                break
                            } catch (e: Exception) {
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
            thisLogger().error("Failed to open browser", e)
            throw RuntimeException("Failed to open browser: ${e.message}")
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

    private fun createSimpleAuthFile() {
        // Create a simple JSON file with a timestamp to indicate login
        val authJson = """
            {
                "cookies": [],
                "origins": [
                    {
                        "origin": "https://v0.dev",
                        "localStorage": [
                            {
                                "name": "v0_login_timestamp",
                                "value": "${System.currentTimeMillis()}"
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        authFilePath.toFile().writeText(authJson)
    }

    fun logout() {
        try {
            val file = authFilePath.toFile()
            if (file.exists()) {
                file.delete()
                thisLogger().info("Logged out successfully")
            }
        } catch (e: Exception) {
            thisLogger().error("Error during logout", e)
        }
    }

    fun getAuthFilePath(): Path {
        return authFilePath
    }
}
