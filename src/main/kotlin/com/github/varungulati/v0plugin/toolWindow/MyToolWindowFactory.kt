package com.github.varungulati.v0plugin.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.github.varungulati.v0plugin.MyBundle
import com.github.varungulati.v0plugin.services.MyProjectService
import com.github.varungulati.v0plugin.services.V0AuthService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages


class MyToolWindowFactory : ToolWindowFactory {

    init {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) {

        private val service = toolWindow.project.service<MyProjectService>()
        private val authService = toolWindow.project.service<V0AuthService>()
        private val contentPanel = JPanel(BorderLayout())
        private val mainPanel = JBPanel<JBPanel<*>>()
        private val statusLabel = JBLabel("")

        init {
            updateUI()
        }

        private fun updateUI() {
            if (authService.isLoggedIn()) {
                showLoggedInUI()
            } else {
                showLoginUI()
            }
        }

        private fun showLoginUI() {
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
                            "Please complete the login process in the browser.\n\n" +
                            "You have 60 seconds to complete the login.",
                            "V0.dev Login"
                        )

                        // Update status
                        statusLabel.text = "Initializing browser... This may take a moment."

                        // Start the login process
                        authService.loginWithBrowser { success ->
                            ApplicationManager.getApplication().invokeLater {
                                if (success) {
                                    Messages.showInfoMessage(
                                        "Successfully logged in to V0.dev!",
                                        "Login Successful"
                                    )
                                    statusLabel.text = ""
                                    updateUI()
                                } else {
                                    Messages.showErrorDialog(
                                        "Failed to log in to V0.dev. Please try again.",
                                        "Login Failed"
                                    )
                                    statusLabel.text = "Login failed. Please try again."
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

                // Status label
                statusLabel.horizontalAlignment = JBLabel.CENTER
                val statusPanel = JPanel(FlowLayout(FlowLayout.CENTER))
                statusPanel.add(statusLabel)
                add(statusPanel, BorderLayout.SOUTH)
            }

            contentPanel.add(loginPanel, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()
        }

        private fun showLoggedInUI() {
            contentPanel.removeAll()

            val loggedInPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

                val welcomeLabel = JBLabel("Connected to V0.dev").apply {
                    font = font.deriveFont(font.size * 1.5f)
                    horizontalAlignment = JBLabel.CENTER
                }

                val descriptionLabel = JBLabel("You're now connected to V0.dev and can use all features").apply {
                    horizontalAlignment = JBLabel.CENTER
                }

                val logoutButton = JButton("Logout").apply {
                    addActionListener {
                        authService.logout()
                        updateUI()
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
                buttonPanel.add(logoutButton)

                add(topPanel, BorderLayout.NORTH)
                add(buttonPanel, BorderLayout.CENTER)
            }

            contentPanel.add(loggedInPanel, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()
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
