package com.github.varungulati.v0plugin.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.github.varungulati.v0plugin.MyBundle
import com.github.varungulati.v0plugin.services.MyProjectService
import com.github.varungulati.v0plugin.services.V0AuthService
import com.github.varungulati.v0plugin.services.V0ChatService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.StyleSheet


class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) {

        private val project = toolWindow.project
        private val authService = project.service<V0AuthService>()
        private val chatService = project.service<V0ChatService>()
        private val contentPanel = JPanel(BorderLayout())
        private val mainPanel = JBPanel<JBPanel<*>>()

        // Chat UI components
        private val chatHistoryArea = JTextPane()
        private val htmlKit = HTMLEditorKit()
        private val htmlDoc = HTMLDocument()
        private val messageField = JTextArea(3, 20)
        private val sendButton = JButton("Send")

        init {
            setupChatUI()

            // Initial UI setup based on login state
            if (authService.isLoggedIn()) {
                showChatUI()
            } else {
                showLoginUI()
            }

            // Add a timer to check login state periodically
            Timer(2000) { _ ->
                SwingUtilities.invokeLater {
                    if (authService.isLoggedIn()) {
                        showChatUI()
                    } else {
                        showLoginUI()
                    }
                }
            }.apply {
                isRepeats = true
                start()
            }
        }

        private fun setupChatUI() {
            // Configure chat history area with proper HTML document
            chatHistoryArea.isEditable = false
            chatHistoryArea.editorKit = htmlKit
            chatHistoryArea.document = htmlDoc
            chatHistoryArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            chatHistoryArea.font = Font("SansSerif", Font.PLAIN, 12)

            // Add base CSS styles
            try {
                val styleSheet = StyleSheet()
                styleSheet.addRule("body { font-family: SansSerif; font-size: 12pt; margin: 5px; }")
                styleSheet.addRule(".user-message { color: #007ACC; margin-bottom: 10px; }")
                styleSheet.addRule(".bot-message { color: #6C6C6C; margin-bottom: 10px; }")
                styleSheet.addRule(".error-message { color: #FF0000; margin-bottom: 10px; }")
                styleSheet.addRule(".message-content { margin-top: 5px; margin-left: 10px; white-space: pre-wrap; }")
                styleSheet.addRule("pre { background-color: #f5f5f5; padding: 10px; border-radius: 5px; overflow-x: auto; }")
                styleSheet.addRule("code { font-family: monospace; background-color: #f5f5f5; padding: 2px 4px; border-radius: 3px; }")
                htmlKit.styleSheet = styleSheet
            } catch (e: Exception) {
                thisLogger().error("Error setting up HTML styles", e)
            }

            // Configure message field
            messageField.lineWrap = true
            messageField.wrapStyleWord = true
            messageField.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )

            // Add key listener to message field for Enter key
            messageField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                        e.consume()
                        sendMessage()
                    }
                }
            })

            // Configure send button
            sendButton.addActionListener {
                sendMessage()
            }
        }

        private fun sendMessage() {
            val message = messageField.text.trim()
            if (message.isEmpty()) return

            // Disable input while processing
            messageField.isEnabled = false
            sendButton.isEnabled = false

            // Add user message to chat history
            addMessageToChat("You", message, true)

            // Clear input field
            messageField.text = ""

            // Send message to V0.dev
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // Show typing indicator
                    ApplicationManager.getApplication().invokeLater {
                        addTypingIndicator()
                    }

                    // Send the message to V0.dev
                    chatService.sendMessage(message) { response, success ->
                        ApplicationManager.getApplication().invokeLater {
                            // Remove typing indicator
                            removeTypingIndicator()

                            if (success) {
                                addMessageToChat("V0", response, false)
                            } else {
                                addErrorToChat(response)
                            }

                            // Re-enable input
                            messageField.isEnabled = true
                            sendButton.isEnabled = true
                            messageField.requestFocus()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        // Remove typing indicator
                        removeTypingIndicator()

                        addErrorToChat("Error: ${e.message}")

                        // Re-enable input
                        messageField.isEnabled = true
                        sendButton.isEnabled = true
                        messageField.requestFocus()
                    }
                }
            }
        }

        private fun addTypingIndicator() {
            try {
                val html = """
                    <div id="typing-indicator" class="bot-message">
                        <strong>V0:</strong>
                        <div class="message-content">Typing...</div>
                    </div>
                """.trimIndent()

                // Insert HTML at the end of the document
                htmlKit.insertHTML(htmlDoc, htmlDoc.length, html, 0, 0, null)

                // Scroll to bottom
                chatHistoryArea.caretPosition = htmlDoc.length
            } catch (e: Exception) {
                thisLogger().error("Error adding typing indicator", e)
            }
        }

        private fun removeTypingIndicator() {
            try {
                val element = htmlDoc.getElement("typing-indicator")
                if (element != null) {
                    htmlDoc.removeElement(element)
                }
            } catch (e: Exception) {
                thisLogger().error("Error removing typing indicator", e)
            }
        }

        private fun addMessageToChat(sender: String, message: String, isUser: Boolean) {
            try {
                // Process message to handle code blocks and formatting
                val processedMessage = processMessageContent(message)

                val cssClass = if (isUser) "user-message" else "bot-message"

                val html = """
                    <div class="$cssClass">
                        <strong>$sender:</strong>
                        <div class="message-content">$processedMessage</div>
                    </div>
                """.trimIndent()

                // Insert HTML at the end of the document
                htmlKit.insertHTML(htmlDoc, htmlDoc.length, html, 0, 0, null)

                // Scroll to bottom
                chatHistoryArea.caretPosition = htmlDoc.length
            } catch (e: Exception) {
                thisLogger().error("Error adding message to chat", e)
            }
        }

        private fun processMessageContent(message: String): String {
            // Escape HTML special characters
            var processedMessage = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Process code blocks (\`\`\`language code \`\`\`)
            val codeBlockRegex = """\`\`\`([a-zA-Z]*)\s*([\s\S]*?)\`\`\`""".toRegex()
            processedMessage = processedMessage.replace(codeBlockRegex) { matchResult ->
                val language = matchResult.groupValues[1]
                val code = matchResult.groupValues[2].trim()

                "<pre><code class=\"language-$language\">$code</code></pre>"
            }

            // Process inline code (`code`)
            val inlineCodeRegex = """`([^`]+)`""".toRegex()
            processedMessage = processedMessage.replace(inlineCodeRegex) { matchResult ->
                val code = matchResult.groupValues[1]
                "<code>$code</code>"
            }

            // Convert newlines to <br> tags
            processedMessage = processedMessage.replace("\n", "<br/>")

            return processedMessage
        }

        private fun addErrorToChat(errorMessage: String) {
            try {
                val html = """
                    <div class="error-message">
                        <strong>Error:</strong>
                        <div class="message-content">$errorMessage</div>
                    </div>
                """.trimIndent()

                // Insert HTML at the end of the document
                htmlKit.insertHTML(htmlDoc, htmlDoc.length, html, 0, 0, null)

                // Scroll to bottom
                chatHistoryArea.caretPosition = htmlDoc.length
            } catch (e: Exception) {
                thisLogger().error("Error adding error message to chat", e)
            }
        }

        private fun showLoginUI() {
            // Check if we're already showing the login UI
            if (isShowingLoginUI()) {
                return
            }

            thisLogger().info("Showing login UI")

            contentPanel.removeAll()

            // Login panel with prominent login button
            val loginPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

                val welcomeLabel = JBLabel("Welcome to V0 Assistant").apply {
                    font = font.deriveFont(font.size * 1.5f)
                    horizontalAlignment = JBLabel.CENTER
                }

                val descriptionLabel = JBLabel("Connect to V0.dev to unlock AI-powered development tools").apply {
                    horizontalAlignment = JBLabel.CENTER
                }

                val loginButton = JButton("Login to V0.dev").apply {
                    font = font.deriveFont(font.size * 1.2f)
                    addActionListener {
                        thisLogger().info("Login button clicked")

                        // Show a message that browser will open
                        Messages.showInfoMessage(
                            "A browser window will open for you to log in to V0.dev.\n\n" +
                            "Please complete the login process in the browser.",
                            "V0.dev Login"
                        )

                        // Start the login process
                        authService.loginWithBrowser { success ->
                            ApplicationManager.getApplication().invokeLater {
                                if (success) {
                                    Messages.showInfoMessage(
                                        "Successfully logged in to V0.dev!",
                                        "Login Successful"
                                    )
                                    showChatUI()
                                } else {
                                    Messages.showErrorDialog(
                                        "Failed to log in to V0.dev. Please try again.",
                                        "Login Failed"
                                    )
                                }
                            }
                        }
                    }
                }

                val topPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(welcomeLabel)
                    add(Box.createVerticalStrut(10))
                    add(descriptionLabel)
                    add(Box.createVerticalStrut(30))
                }

                val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
                buttonPanel.add(loginButton)

                add(topPanel, BorderLayout.NORTH)
                add(buttonPanel, BorderLayout.CENTER)
            }

            contentPanel.add(loginPanel, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()
        }

        private fun isShowingLoginUI(): Boolean {
            if (contentPanel.componentCount == 0) return false

            val component = contentPanel.getComponent(0)
            if (component is JPanel) {
                // Check if this panel contains a login button
                for (c in component.components) {
                    if (c is JPanel) {
                        for (innerC in c.components) {
                            if (innerC is JButton && innerC.text == "Login to V0.dev") {
                                return true
                            }
                        }
                    }
                }
            }
            return false
        }

        private fun isShowingChatUI(): Boolean {
            if (contentPanel.componentCount == 0) return false

            val component = contentPanel.getComponent(0)
            if (component is JPanel) {
                // Check if this panel contains the chat history area
                for (c in component.components) {
                    if (c is JScrollPane && c.viewport.view == chatHistoryArea) {
                        return true
                    }
                }
            }
            return false
        }

        private fun showChatUI() {
            // Check if we're already showing the chat UI
            if (isShowingChatUI()) {
                return
            }

            thisLogger().info("Showing chat UI")

            contentPanel.removeAll()

            // Chat panel
            val chatPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

                // Header
                val headerPanel = JPanel(BorderLayout()).apply {
                    border = BorderFactory.createEmptyBorder(0, 0, 10, 0)

                    add(JBLabel("V0 Assistant Chat").apply {
                        font = font.deriveFont(font.size * 1.2f)
                    }, BorderLayout.WEST)

                    val logoutButton = JButton("Logout").apply {
                        addActionListener {
                            authService.logout()
                            showLoginUI()
                        }
                    }

                    add(logoutButton, BorderLayout.EAST)
                }

                // Chat history
                val chatScrollPane = JBScrollPane(chatHistoryArea).apply {
                    preferredSize = Dimension(400, 300)
                    border = BorderFactory.createLineBorder(JBColor.border())
                }

                // Input area
                val inputPanel = JPanel(BorderLayout()).apply {
                    border = BorderFactory.createEmptyBorder(10, 0, 0, 0)

                    val messageScrollPane = JBScrollPane(messageField)

                    add(messageScrollPane, BorderLayout.CENTER)
                    add(sendButton, BorderLayout.EAST)

                    // Add a hint label
                    add(JBLabel("Press Ctrl+Enter to send").apply {
                        font = font.deriveFont(Font.ITALIC, 10f)
                        foreground = JBColor.GRAY
                    }, BorderLayout.SOUTH)
                }

                add(headerPanel, BorderLayout.NORTH)
                add(chatScrollPane, BorderLayout.CENTER)
                add(inputPanel, BorderLayout.SOUTH)
            }

            contentPanel.add(chatPanel, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()

            // Focus on message field
            messageField.requestFocus()

            // Clear existing chat history if it's not empty
            try {
                if (htmlDoc.length > 0) {
                    htmlDoc.remove(0, htmlDoc.length)
                }
            } catch (e: Exception) {
                thisLogger().error("Error clearing chat history", e)
            }

            // Add welcome message
            addMessageToChat("V0", "Hello! I'm V0 Assistant. How can I help you today?", false)
        }

        fun getContent(): JComponent {
            mainPanel.apply {
                layout = BorderLayout()

                // Title panel
                val titlePanel = JPanel(BorderLayout()).apply {
                    border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    add(JBLabel("V0 Assistant").apply {
                        font = font.deriveFont(font.size * 1.2f)
                    }, BorderLayout.WEST)
                }
                add(titlePanel, BorderLayout.NORTH)

                // Main content panel
                contentPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                add(contentPanel, BorderLayout.CENTER)
            }

            return mainPanel
        }
    }
}
