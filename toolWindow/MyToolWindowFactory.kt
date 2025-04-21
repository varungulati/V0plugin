package com.github.varungulati.v0plugin.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.github.varungulati.v0plugin.MyBundle
import com.github.varungulati.v0plugin.services.MyProjectService
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), "Main", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) {

        private val service = toolWindow.project.service<MyProjectService>()

        fun getContent() = JBPanel<JBPanel<*>>().apply {
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
            val contentPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

                // Text area for displaying content
                val textArea = JTextArea().apply {
                    text = "Welcome to V0 Assistant!\n\nThis tool helps you with your development tasks."
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                }
                add(JBScrollPane(textArea), BorderLayout.CENTER)

                // Button panel
                val buttonPanel = JPanel(GridLayout(3, 1, 5, 5)).apply {
                    border = BorderFactory.createEmptyBorder(5, 0, 0, 0)

                    add(JButton("Generate Code").apply {
                        addActionListener {
                            textArea.text = "Code generation feature will be implemented here.\n\nRandom number: ${service.getRandomNumber()}"
                        }
                    })

                    add(JButton("Analyze Code").apply {
                        addActionListener {
                            textArea.text = "Code analysis feature will be implemented here.\n\nRandom number: ${service.getRandomNumber()}"
                        }
                    })

                    add(JButton("Help").apply {
                        addActionListener {
                            textArea.text = "V0 Assistant is a plugin that helps you with various development tasks.\n\n" +
                                    "Features:\n" +
                                    "- Code generation\n" +
                                    "- Code analysis\n" +
                                    "- And more to come!"
                        }
                    })
                }
                add(buttonPanel, BorderLayout.SOUTH)
            }
            add(contentPanel, BorderLayout.CENTER)
        }
    }
}
