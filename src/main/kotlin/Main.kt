package dev.aetex

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AeTeX"
    ) {
        MaterialTheme {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .padding(12.dp)
                ) {
                    Text("Project files")
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(12.dp)
                ) {
                    Text("Editor")
                }

                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .padding(12.dp)
                ) {
                    Text("PDF Preview")
                }
            }
        }
    }
}