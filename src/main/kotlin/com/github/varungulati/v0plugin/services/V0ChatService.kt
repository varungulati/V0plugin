package com.github.varungulati.v0plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import java.io.File
import java.nio.file.Paths
import org.json.JSONArray
import java.net.HttpCookie
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@Service(Service.Level.PROJECT)
class V0ChatService(private val project: Project) {

    private val executor = Executors.newSingleThreadExecutor()
    private val authService = project.getService(V0AuthService::class.java)
    private val cookieManager = CookieManager()

    companion object {
        private const val V0_URL = "https://v0.dev"
        private const val V0_API_URL = "$V0_URL/api"
        private const val CHAT_API_URL = "$V0_API_URL/chat"
        private const val CONVERSATION_API_URL = "$V0_API_URL/conversation"
    }

    init {
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(cookieManager)

        // Load cookies from auth service
        val cookies = authService.getCookies()
        for (cookie in cookies) {
            cookieManager.cookieStore.add(URI(V0_URL), cookie)
        }
    }

    fun sendMessage(message: String, onResponse: (String, Boolean) -> Unit) {
        val future = CompletableFuture.supplyAsync({
            try {
                thisLogger().info("Sending message to V0.dev: $message")

                // Ensure we're logged in
                if (!authService.isLoggedIn()) {
                    throw RuntimeException("Not logged in to V0.dev. Please log in first.")
                }

                // Send the message to V0.dev
                val response = sendMessageToV0(message)

                if (response.isNotEmpty()) {
                    thisLogger().info("Received response from V0.dev")
                    response to true
                } else {
                    thisLogger().error("Empty response from V0.dev")
                    "Failed to get a response from V0.dev. Please try again." to false
                }
            } catch (e: Exception) {
                thisLogger().error("Error sending message to V0.dev", e)
                "Error: ${e.message}" to false
            }
        }, executor)

        future.thenAccept { (response, success) ->
            onResponse(response, success)
        }
    }

    private fun sendMessageToV0(message: String): String {
        try {
            // First, check if we need to create a new conversation
            val conversationId = getOrCreateConversation()

            // Now send the message
            val url = URL("$CHAT_API_URL/$conversationId")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000 // Longer timeout for responses

            // Create request body
            val requestBody = JSONObject()
            requestBody.put("message", message)

            // Send request
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream)
            writer.write(requestBody.toString())
            writer.flush()

            // Read response
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val response = readStreamAsString(inputStream)
                return parseResponse(response)
            } else {
                // Try to read error stream
                val errorStream = connection.errorStream
                val errorResponse = if (errorStream != null) readStreamAsString(errorStream) else "Unknown error"

                thisLogger().error("API request failed with response code: $responseCode, error: $errorResponse")
                return "Error: HTTP $responseCode - $errorResponse"
            }
        } catch (e: Exception) {
            thisLogger().error("Error sending message to V0.dev", e)
            throw e
        }
    }

    private fun getOrCreateConversation(): String {
        try {
            // First try to get existing conversations
            val url = URL(CONVERSATION_API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val response = readStreamAsString(inputStream)

                // Parse response to get conversation ID
                val jsonResponse = JSONObject(response)
                if (jsonResponse.has("conversations") && jsonResponse.getJSONArray("conversations").length() > 0) {
                    val conversations = jsonResponse.getJSONArray("conversations")
                    val conversation = conversations.getJSONObject(0)
                    return conversation.getString("id")
                }
            }

            // If we get here, we need to create a new conversation
            return createNewConversation()
        } catch (e: Exception) {
            thisLogger().error("Error getting conversation", e)
            // If we fail to get a conversation, create a new one
            return createNewConversation()
        }
    }

    private fun createNewConversation(): String {
        try {
            val url = URL(CONVERSATION_API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Create request body with a title
            val requestBody = JSONObject()
            requestBody.put("title", "IntelliJ Plugin Conversation ${UUID.randomUUID().toString().substring(0, 8)}")

            // Send request
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream)
            writer.write(requestBody.toString())
            writer.flush()

            // Read response
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val inputStream = connection.inputStream
                val response = readStreamAsString(inputStream)

                // Parse response to get conversation ID
                val jsonResponse = JSONObject(response)
                if (jsonResponse.has("id")) {
                    return jsonResponse.getString("id")
                }
            }

            throw RuntimeException("Failed to create conversation, response code: $responseCode")
        } catch (e: Exception) {
            thisLogger().error("Error creating conversation", e)
            throw e
        }
    }

    private fun readStreamAsString(inputStream: InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val response = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }

        reader.close()
        return response.toString()
    }

    private fun parseResponse(response: String): String {
        try {
            // Try to parse as JSON
            val jsonResponse = JSONObject(response)

            // Extract the relevant parts of the response
            if (jsonResponse.has("message")) {
                return jsonResponse.getString("message")
            } else if (jsonResponse.has("content")) {
                return jsonResponse.getString("content")
            } else if (jsonResponse.has("response")) {
                return jsonResponse.getString("response")
            } else if (jsonResponse.has("error")) {
                return "Error: ${jsonResponse.getString("error")}"
            }

            // If we can't extract specific fields, return the whole JSON
            return "Response: $response"
        } catch (e: Exception) {
            // If it's not valid JSON, return as is
            return response
        }
    }
}
