package com.lagradost.cloudstream4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.default_icon
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val samples = listOf(
    DesktopMedia(
        "Big Buck Bunny Trailer",
        URI("https://media.w3.org/2010/05/bunny/trailer.mp4"),
        "Open animated short © 2008 Blender Foundation · CC BY 3.0"
    )
)

fun main() = application {
    val progress = remember { PlaybackProgress() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "CloudStream Desktop",
        icon = painterResource(Res.drawable.default_icon),
        state = rememberWindowState(width = 1060.dp, height = 700.dp)
    ) {
        CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
            Surface(Modifier.fillMaxSize()) {
                var selected by remember { mutableStateOf<DesktopMedia?>(null) }
                val media = selected
                if (media == null) {
                    LibraryScreen { selected = it }
                } else {
                    key(media.uri) { PlaybackScreen(media, progress) { selected = null } }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(open: (DesktopMedia) -> Unit) {
    var query by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    val results = samples.filter { it.title.contains(query.trim(), ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CloudStream Desktop", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = {
                    chooseFile("Open a video", "Video files", "mp4", "m4v", "m3u8")?.let {
                        open(DesktopMedia(it.nameWithoutExtension, it.toURI()))
                    }
                }) { Text("Open file") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    url,
                    onValueChange = { url = it; urlError = false },
                    label = { Text("Direct video URL") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = urlError
                )
                FilledTonalButton(enabled = url.isNotBlank(), onClick = {
                    val media = directMedia(url)
                    if (media == null) urlError = true else open(media)
                }) { Text("Play URL") }
            }
            if (urlError) Text("Enter a valid HTTP or HTTPS video URL.", color = MaterialTheme.colorScheme.error)
        }
        item { HorizontalDivider() }
        item {
            Text("Sample library", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, label = { Text("Search samples") }, singleLine = true)
        }
        items(results, key = { it.uri.toString() }) { media ->
            Card(onClick = { open(media) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(media.title, style = MaterialTheme.typography.titleLarge)
                        Text(media.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Play", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (results.isEmpty()) item { Text("No matching samples.") }
    }
}

@Composable
private fun PlaybackScreen(media: DesktopMedia, progress: PlaybackProgress, back: () -> Unit) {
    var state by remember { mutableStateOf(PlayerState()) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var subtitleMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val player = remember { DesktopPlayer(media, progress) { state = it } }
    DisposableEffect(player) { onDispose { player.close() } }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = back) { Text("Back") }
            Text(media.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
        }
        SwingPanel(
            factory = { player.panel() },
            background = Color.Black,
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
        )
        val duration = state.duration.coerceAtLeast(1L).toFloat()
        Slider(
            value = (dragPosition ?: state.position.toFloat()).coerceIn(0f, duration),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { player.seek(it.toLong()) }
                dragPosition = null
            },
            enabled = state.ready && state.duration > 0,
            valueRange = 0f..duration
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = player::toggle, enabled = state.ready) { Text(if (state.playing) "Pause" else "Play") }
            Text("${timeLabel(dragPosition?.toLong() ?: state.position)} / ${timeLabel(state.duration)}")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                player.captions(emptyList())
                subtitleMessage = "Subtitles off"
            }) { Text("Clear subtitles") }
            OutlinedButton(onClick = {
                val file = chooseFile("Open subtitles", "SubRip subtitles", "srt")
                if (file != null) scope.launch {
                    val cues = withContext(Dispatchers.IO) {
                        runCatching { parseSrt(file.readText()) }.getOrNull()
                    }
                    if (cues.isNullOrEmpty()) {
                        subtitleMessage = "No readable subtitle cues were found."
                    } else {
                        player.captions(cues)
                        subtitleMessage = file.name
                    }
                }
            }) { Text("Load .srt") }
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        if (subtitleMessage.isNotBlank()) Text(subtitleMessage, style = MaterialTheme.typography.bodySmall)
    }
}

private fun timeLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours == 0L) "%d:%02d".format(seconds / 60, seconds % 60)
    else "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
}

private fun chooseFile(title: String, description: String, vararg extensions: String): java.io.File? {
    val dialog = JFileChooser().apply {
        dialogTitle = title
        fileFilter = FileNameExtensionFilter(description, *extensions)
    }
    return if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) dialog.selectedFile else null
}
