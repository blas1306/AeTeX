package dev.aetex.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.aetex.project.TeXProject
import dev.aetex.ui.panels.EditorPanel
import dev.aetex.ui.panels.PreviewPanel
import dev.aetex.ui.panels.ProjectPanel

@Composable
fun MainWindow() {
    var currentProject by remember {
        mutableStateOf<TeXProject?>(null)
    }

    var editorText by remember {
        mutableStateOf(
            """
\documentclass{article}

\begin{document}

Hello AeTeX!

\end{document}
            """.trimIndent()
        )
    }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            ProjectPanel(
                project = currentProject,
                onProjectOpened = { openedProject ->
                    currentProject = openedProject
                }
            )

            EditorPanel(
                text = editorText,
                onTextChange = { editorText = it },
                modifier = Modifier.weight(1f)
            )

            PreviewPanel()
        }
    }
}