package dev.aetex.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aetex.project.ProjectDirectory
import dev.aetex.project.ProjectEntry
import dev.aetex.project.ProjectFile
import dev.aetex.project.OpenedDirectoryKind
import dev.aetex.project.TeXProject
import java.nio.file.Path

private data class VisibleProjectEntry(
    val entry: ProjectEntry,
    val depth: Int
)

@Composable
fun ProjectPanel(
    project: TeXProject?,
    activeDocumentPath: Path?,
    onCreateProject: () -> Unit,
    onOpenProject: () -> Unit,
    onInitializeProject: () -> Unit,
    onFileSelected: (Path) -> Unit
) {
    val expandedDirectories = remember(project?.rootDirectory) {
        mutableStateMapOf<Path, Boolean>()
    }
    val visibleEntries = project?.let {
        flattenEntries(it.entries, expandedDirectories)
    }.orEmpty()

    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(Color(0xFF202225))
            .padding(12.dp)
    ) {
        Button(onClick = onCreateProject, modifier = Modifier.fillMaxWidth()) {
            Text("New Project...")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenProject, modifier = Modifier.fillMaxWidth()) {
            Text("Open Project...")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = project?.rootDirectory?.fileName?.toString() ?: "No project open",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (val kind = project?.directoryKind) {
            is OpenedDirectoryKind.Unconfigured -> {
                Text(
                    text = "This folder is not an AeTeX project.\n" +
                        "An AeTeX project requires .aetex/project.toml.",
                    color = Color(0xFFFFD580),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = onInitializeProject,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Initialize Project")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            is OpenedDirectoryKind.InvalidProject -> {
                Text(
                    text = buildString {
                        append("Invalid AeTeX project configuration:\n")
                        append(kind.configurationPath)
                        kind.diagnostics.firstOrNull()?.let {
                            append("\n")
                            append(it.message)
                            if (it.line != null) {
                                append(" (line ${it.line}")
                                it.column?.let { column -> append(", column $column") }
                                append(')')
                            }
                        }
                    },
                    color = Color(0xFFFF9B9B),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            is OpenedDirectoryKind.Configured, null -> Unit
        }

        if (project != null && visibleEntries.isEmpty()) {
            Text(
                text = "This project folder is empty.",
                color = Color(0xFFB7B7B7),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxHeight()) {
                items(
                    items = visibleEntries,
                    key = { it.entry.path.toString() }
                ) { visible ->
                    ProjectEntryRow(
                        visibleEntry = visible,
                        expanded = expandedDirectories[visible.entry.path] == true,
                        selected = activeDocumentPath == visible.entry.path,
                        onClick = {
                            when (val entry = visible.entry) {
                                is ProjectDirectory -> {
                                    expandedDirectories[entry.path] =
                                        expandedDirectories[entry.path] != true
                                }

                                is ProjectFile -> onFileSelected(entry.path)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectEntryRow(
    visibleEntry: VisibleProjectEntry,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val entry = visibleEntry.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF3B556F) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = (visibleEntry.depth * 14).dp,
                top = 5.dp,
                end = 4.dp,
                bottom = 5.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (entry) {
                is ProjectDirectory -> if (expanded) "▼" else "▶"
                is ProjectFile -> " "
            },
            color = Color(0xFFB7B7B7),
            modifier = Modifier.width(12.dp)
        )
        Text(
            text = if (entry is ProjectDirectory) "Folder" else "File",
            color = if (entry is ProjectDirectory) Color(0xFFD7BA7D) else Color(0xFF9CDCFE)
        )
        Text(
            text = entry.name + if (entry.isSymbolicLink) " ↗" else "",
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun flattenEntries(
    entries: List<ProjectEntry>,
    expandedDirectories: Map<Path, Boolean>,
    depth: Int = 0
): List<VisibleProjectEntry> = buildList {
    entries.forEach { entry ->
        add(VisibleProjectEntry(entry, depth))
        if (entry is ProjectDirectory && expandedDirectories[entry.path] == true) {
            addAll(flattenEntries(entry.children, expandedDirectories, depth + 1))
        }
    }
}
