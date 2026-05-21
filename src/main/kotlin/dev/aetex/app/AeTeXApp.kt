package dev.aetex.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aetex.ui.MainWindow

fun startAeTeX() = application {

    Window(
        onCloseRequest = ::exitApplication,
        title = "AeTeX"
    ) {
        MainWindow()
    }
}