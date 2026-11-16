package com.lagradost.cloudstream4

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.util.Duration
import javax.swing.SwingUtilities

internal data class PlayerState(
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val status: String = "Opening video…",
    val ready: Boolean = false
)

internal class DesktopPlayer(
    private val source: DesktopMedia,
    private val progress: PlaybackProgress,
    private val report: (PlayerState) -> Unit
) {
    private var player: MediaPlayer? = null
    private var subtitle: Label? = null
    private var cues = emptyList<SubtitleCue>()
    private var ready = false
    private var ended = false
    private var error: String? = null
    private var lastReported = Long.MIN_VALUE
    private var lastSave = System.nanoTime()

    @Volatile
    private var closed = false
    private var started = false

    fun panel(): JFXPanel = JFXPanel().also { panel ->
        started = true
        Platform.setImplicitExit(false)
        Platform.runLater {
            if (closed) return@runLater
            try {
                val media = Media(source.uri.toASCIIString())
                val video = MediaPlayer(media)
                player = video
                val view = MediaView(video)
                val captions = Label().apply {
                    style = "-fx-background-color: rgba(0,0,0,0.8); -fx-text-fill: white;" +
                        " -fx-font-size: 20px; -fx-padding: 5px 12px; -fx-background-radius: 4px;"
                    isWrapText = true
                    isVisible = false
                    translateY = -22.0
                }
                subtitle = captions
                val root = StackPane(view, captions).apply { style = "-fx-background-color: black;" }
                StackPane.setAlignment(captions, Pos.BOTTOM_CENTER)
                captions.maxWidthProperty().bind(root.widthProperty().multiply(0.85))
                view.fitWidthProperty().bind(root.widthProperty())
                view.fitHeightProperty().bind(root.heightProperty())
                panel.scene = Scene(root, Color.BLACK)

                media.setOnError { fail(media.error) }
                video.setOnError { fail(video.error) }
                video.setOnReady {
                    ready = true
                    val saved = progress.read(source.uri)
                    if (saved > 0 && saved < total()) video.seek(Duration.millis(saved.toDouble()))
                    update()
                    video.play()
                }
                video.setOnPlaying { update() }
                video.setOnPaused { save(); update() }
                video.setOnStalled { update() }
                video.setOnEndOfMedia {
                    ended = true
                    progress.write(source.uri, 0L)
                    update()
                }
                video.currentTimeProperty().addListener { _, _, _ ->
                    updateCaption()
                    val pos = position()
                    if (pos / 250 != lastReported) {
                        lastReported = pos / 250
                        update()
                    }
                    if (System.nanoTime() - lastSave > 5_000_000_000L) save()
                }
                if (media.error != null) fail(media.error)
                if (video.error != null) fail(video.error)
            } catch (failure: RuntimeException) {
                fail(failure)
            }
        }
    }

    fun toggle() = onFx {
        val video = player ?: return@onFx
        if (!ready || error != null) return@onFx
        if (ended) {
            ended = false
            video.seek(Duration.ZERO)
            video.play()
            update()
        } else if (video.status == MediaPlayer.Status.PLAYING) {
            video.pause()
        } else {
            video.play()
        }
    }

    fun seek(millis: Long) = onFx {
        val video = player ?: return@onFx
        if (!ready || total() <= 0) return@onFx
        if (ended) video.pause()
        ended = false
        video.seek(Duration.millis(millis.coerceIn(0L, total()).toDouble()))
        update()
    }

    fun captions(subtitles: List<SubtitleCue>) = onFx {
        cues = subtitles
        updateCaption()
    }

    fun close() {
        closed = true
        if (!started) return
        Platform.runLater {
            save()
            player?.dispose()
            player = null
        }
    }

    private fun onFx(action: () -> Unit) {
        if (started && !closed) Platform.runLater { if (!closed) action() }
    }

    private fun position(): Long = player?.currentTime?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: 0L

    private fun total(): Long = player?.totalDuration?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: 0L

    private fun save() {
        lastSave = System.nanoTime()
        if (ready && !ended && error == null) progress.write(source.uri, position())
    }

    private fun updateCaption() {
        val pos = position()
        val captions = cues.asSequence().takeWhile { it.start <= pos }
            .filter { pos < it.end }.joinToString("\n") { it.text }
        subtitle?.apply {
            text = captions
            isVisible = captions.isNotEmpty()
        }
    }

    private fun fail(failure: Throwable?) {
        error = "Unable to play this media. ${failure?.message.orEmpty()}"
        update()
    }

    private fun update() {
        val playing = !ended && player?.status == MediaPlayer.Status.PLAYING
        val status = when {
            error != null -> error.orEmpty()
            ended -> "Playback finished"
            player?.status == MediaPlayer.Status.STALLED -> "Buffering…"
            playing -> "Playing"
            ready -> "Paused"
            else -> "Opening video…"
        }
        val state = PlayerState(position(), total(), playing, status, ready && error == null)
        SwingUtilities.invokeLater { if (!closed) report(state) }
    }
}
