package dev.aetex.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PreviewPanel() {
    Box(
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(Color(0xFF252526))
            .padding(12.dp)
    ) {
        Text(
            "PDF Preview",
            color = Color.White
        )
    }
}