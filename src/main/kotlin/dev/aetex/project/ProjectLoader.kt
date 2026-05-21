package dev.aetex.project

import java.io.File
import javax.swing.JFileChooser

fun chooseProjectDirectory(): File? {
    val chooser = JFileChooser()

    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY

    val result = chooser.showOpenDialog(null)

    return if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}