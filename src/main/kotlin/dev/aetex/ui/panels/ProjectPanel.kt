package dev.aetex.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aetex.project.TeXProject
import dev.aetex.project.chooseProjectDirectory

@Composable
fun ProjectPanel(
    project: TeXProject?,
    onProjectOpened: (TeXProject) -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(Color(0xFF202225))
            .padding(12.dp)
    ) {
        Button(
            onClick = {
                val directory = chooseProjectDirectory()

                if (directory != null) {
                    onProjectOpened(
                        TeXProject(rootDirectory = directory)
                    )
                }
            }
        ) {
            Text("Open Project")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = project?.rootDirectory?.name ?: "No project open",
            color = Color.White
        )
    }
}