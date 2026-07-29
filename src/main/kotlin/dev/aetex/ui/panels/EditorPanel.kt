package dev.aetex.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetex.editor.OpenDocument
import java.nio.file.Path

@Composable
fun EditorPanel(
    documents: List<OpenDocument>,
    activeDocument: OpenDocument?,
    onDocumentActivated: (Path) -> Unit,
    onDocumentChanged: (Path, String) -> Unit,
    onDocumentCloseRequested: (Path) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E))
    ) {
        DocumentTabs(
            documents = documents,
            activeDocument = activeDocument,
            onDocumentActivated = onDocumentActivated,
            onDocumentCloseRequested = onDocumentCloseRequested
        )

        if (activeDocument == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select an editable file from the project.",
                    color = Color(0xFF9B9B9B)
                )
            }
        } else {
            activeDocument.error?.let { error ->
                Text(
                    text = error.userMessage,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF7A2929))
                        .padding(8.dp)
                )
            }

            key(activeDocument.path) {
                BasicTextField(
                    value = activeDocument.content,
                    onValueChange = { content ->
                        onDocumentChanged(activeDocument.path, content)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentTabs(
    documents: List<OpenDocument>,
    activeDocument: OpenDocument?,
    onDocumentActivated: (Path) -> Unit,
    onDocumentCloseRequested: (Path) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252526))
            .horizontalScroll(rememberScrollState())
    ) {
        documents.forEach { document ->
            val active = activeDocument?.path == document.path
            Row(
                modifier = Modifier
                    .background(if (active) Color(0xFF1E1E1E) else Color(0xFF2D2D30))
                    .clickable { onDocumentActivated(document.path) }
                    .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = document.path.fileName.toString() +
                        if (document.isModified) " *" else "",
                    color = if (active) Color.White else Color(0xFFB7B7B7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDocumentCloseRequested(document.path) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Color(0xFFD0D0D0))
                }
            }
        }
    }
}
