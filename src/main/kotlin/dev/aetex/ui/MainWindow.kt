package dev.aetex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainWindow() {

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

            Box(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF202225))
                    .padding(12.dp)
            ) {
                Text(
                    "Project files",
                    color = Color.White
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF1E1E1E))
                    .padding(12.dp)
            ) {

                BasicTextField(
                    value = editorText,
                    onValueChange = {
                        editorText = it
                    },
                    modifier = Modifier
                        .fillMaxSize(),
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
    }
}