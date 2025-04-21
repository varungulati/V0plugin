package com.github.varungulati.v0plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import java.io.IOException

@Service(Service.Level.PROJECT)
class HeadlessBrowserService(private val project: Project) {

    private val authService = project.getService(V0AuthService::class.java)
    private val isRunning = AtomicBoolean(false)
    private var browserProcess: Process? = null

    companion object {
        private const val V0_URL = "https://v0.dev"
        private const val TIMEOUT_SECONDS = 60L
    }

    fun sendMessage(message: String): String {
        try {
            thisLogger().info("Sending message via headless browser: $message")

            // Ensure we have valid auth
            if (!authService.isLoggedIn()) {
                throw RuntimeException("Not logged in to V0.dev")
            }

            // In a real implementation, you would:
            // 1. Start a headless browser if not already running
            // 2. Navigate to v0.dev
            // 3. Load cookies from the auth file
            // 4. Fill the message in the textarea
            // 5. Press Enter
            // 6. Wait for the response
            // 7. Extract the code from the response

            // For this example, we'll simulate the process
            val simulatedResponse = simulateBrowserInteraction(message)

            return simulatedResponse
        } catch (e: Exception) {
            thisLogger().error("Error sending message via headless browser", e)
            throw e
        }
    }

    private fun simulateBrowserInteraction(message: String): String {
        try {
            thisLogger().info("Simulating browser interaction for message: $message")

            // Create a script that simulates the browser interaction
            val scriptContent = createBrowserSimulationScript(message)

            // Execute the script
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val scriptExt = if (isWindows) "bat" else "sh"
            val scriptFile = File.createTempFile("v0_browser_", ".$scriptExt")
            scriptFile.writeText(scriptContent)
            scriptFile.setExecutable(true)

            val processBuilder = ProcessBuilder()
            if (isWindows) {
                processBuilder.command("cmd.exe", "/c", scriptFile.absolutePath)
            } else {
                processBuilder.command("sh", scriptFile.absolutePath)
            }

            val process = processBuilder.start()

            // Read output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            // Wait for process to complete
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw RuntimeException("Browser simulation timed out")
            }

            // Clean up
            scriptFile.delete()

            // Extract code from output
            return extractCodeFromOutput(output.toString())
        } catch (e: Exception) {
            thisLogger().error("Error in browser simulation", e)
            throw e
        }
    }

    private fun createBrowserSimulationScript(message: String): String {
        // This is a simplified script that simulates browser interaction
        // In a real implementation, you would use a browser automation library

        val escapedMessage = message.replace("\"", "\\\"")

        return if (System.getProperty("os.name").lowercase().contains("win")) {
            """
            @echo off
            echo Simulating browser interaction...
            echo Message: "$escapedMessage"
            echo.
            echo Navigating to V0.dev...
            timeout /t 1 > nul
            echo Loading cookies...
            timeout /t 1 > nul
            echo Filling message in textarea...
            timeout /t 1 > nul
            echo Sending message...
            timeout /t 2 > nul
            echo Waiting for response...
            timeout /t 3 > nul
            echo.
            echo Response received:
            echo.
            echo ```javascript
            echo // Generated code based on your message: "$escapedMessage"
            echo function processUserInput(input) {
            echo   console.log('Processing: ' + input);
            echo   return {
            echo     status: 'success',
            echo     message: 'Processed user input',
            echo     data: { input: input, timestamp: new Date().toISOString() }
            echo   };
            echo }
            echo ```
            """.trimIndent()
        } else {
            """
            #!/bin/sh
            echo "Simulating browser interaction..."
            echo "Message: \"$escapedMessage\""
            echo ""
            echo "Navigating to V0.dev..."
            sleep 1
            echo "Loading cookies..."
            sleep 1
            echo "Filling message in textarea..."
            sleep 1
            echo "Sending message..."
            sleep 2
            echo "Waiting for response..."
            sleep 3
            echo ""
            echo "Response received:"
            echo ""
            echo "```javascript"
            echo "// Generated code based on your message: \"$escapedMessage\""
            echo "function processUserInput(input) {"
            echo "  console.log('Processing: ' + input);"
            echo "  return {"
            echo "    status: 'success',"
            echo "    message: 'Processed user input',"
            echo "    data: { input: input, timestamp: new Date().toISOString() }"
            echo "  };"
            echo "}"
            echo "```"
            """.trimIndent()
        }
    }

    private fun extractCodeFromOutput(output: String): String {
        // Extract code between ```javascript and ``` markers
        val codeStart = output.indexOf("```javascript")
        val codeEnd = output.lastIndexOf("```")

        if (codeStart >= 0 && codeEnd > codeStart) {
            return output.substring(codeStart + "```javascript".length, codeEnd).trim()
        }

        // If no code markers found, return the whole output
        return output
    }

    fun shutdown() {
        try {
            browserProcess?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                    thisLogger().info("Headless browser process terminated")
                }
            }
            isRunning.set(false)
        } catch (e: Exception) {
            thisLogger().error("Error shutting down headless browser", e)
        }
    }
}
