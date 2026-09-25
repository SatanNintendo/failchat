package failchat.tts

import failchat.ConfigKeys
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import mu.KotlinLogging
import org.apache.commons.configuration2.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TtsService(
    private val configuration: Configuration,
    private val client: StreamElementsTtsClient,
    private val guiAvailable: () -> Boolean
) {

    private companion object {
        val logger = KotlinLogging.logger {}
    }

    private val stopped = AtomicBoolean(false)
    private val queueCapacity = try {
        configuration.getInt(ConfigKeys.Tts.queueCapacity)
    } catch (t: Throwable) {
        logger.warn("Invalid TTS queue capacity; using default", t)
        20
    }.coerceIn(1, 100)
    private val queue = ArrayBlockingQueue<String>(queueCapacity)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "TTS-Worker").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    @Volatile
    private var currentPlaybackLatch: CountDownLatch? = null

    @Volatile
    private var currentPlaybackCancellation: AtomicBoolean? = null

    init {
        executor.execute(::workerLoop)
    }

    /**
     * Enqueueing is intentionally non-blocking and best-effort.
     * A full queue simply drops the newest message.
     */
    fun enqueue(text: String) {
        try {
            if (stopped.get() || !guiAvailable()) return
            if (!isEnabled()) return
            if (configuration.getString(ConfigKeys.Tts.key, "").trim().isEmpty()) return

            val maxCharacters = try {
                configuration.getInt(ConfigKeys.Tts.maxCharacters)
            } catch (t: Throwable) {
                logger.debug("Invalid TTS maximum text length; using default", t)
                400
            }.coerceIn(1, 2000)

            val sanitizedText = TtsTextSanitizer.sanitize(text, maxCharacters)
            if (sanitizedText.isEmpty()) return

            queue.offer(sanitizedText)
        } catch (t: Throwable) {
            // TTS must never be able to break the chat message pipeline.
            logger.debug("Failed to queue TTS message", t)
        }
    }

    fun clearQueueAndStop() {
        queue.clear()
        stopCurrentPlayback()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        queue.clear()
        stopCurrentPlayback()
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun workerLoop() {
        while (!stopped.get()) {
            val text = try {
                queue.take()
            } catch (_: InterruptedException) {
                if (stopped.get()) return
                continue
            }

            if (stopped.get() || !guiAvailable()) continue
            if (!isEnabled()) continue

            val voice: String
            val key: String
            val apiUrl: String
            try {
                voice = configuration.getString(ConfigKeys.Tts.voice, "").trim()
                key = configuration.getString(ConfigKeys.Tts.key, "").trim()
                apiUrl = configuration.getString(ConfigKeys.Tts.apiUrl, "").trim()
            } catch (t: Throwable) {
                logger.debug("Invalid TTS configuration; skipping message", t)
                continue
            }

            if (voice.isEmpty() || key.isEmpty() || apiUrl.isEmpty()) continue

            val volume = readVolume()

            var tempFile: Path? = null
            try {
                tempFile = client.synthesize(text, voice, key, apiUrl)
                if (!stopped.get() && guiAvailable() && isEnabled()) {
                    playBlocking(tempFile, volume)
                }
            } catch (t: Throwable) {
                // Network/service/media failures are isolated from chat processing.
                logger.warn("TTS playback failed: {}", t.message ?: t::class.java.simpleName)
            } finally {
                tempFile?.let {
                    try {
                        Files.deleteIfExists(it)
                    } catch (t: Throwable) {
                        logger.debug("Failed to delete TTS temporary file", t)
                    }
                }
            }
        }
    }

    private fun playBlocking(audioFile: Path, volume: Double) {
        val cancelled = AtomicBoolean(false)
        val startedLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        currentPlaybackLatch = finishedLatch
        currentPlaybackCancellation = cancelled

        try {
            Platform.runLater {
                if (stopped.get() || cancelled.get()) {
                    startedLatch.countDown()
                    finishedLatch.countDown()
                    return@runLater
                }

                try {
                    val player = MediaPlayer(Media(audioFile.toUri().toString()))
                    if (stopped.get() || cancelled.get()) {
                        player.dispose()
                        startedLatch.countDown()
                        finishedLatch.countDown()
                        return@runLater
                    }

                    currentPlayer = player
                    player.volume = volume

                    player.setOnReady {
                        if (stopped.get() || cancelled.get()) {
                            cleanupPlayer(player)
                            startedLatch.countDown()
                            finishedLatch.countDown()
                        } else {
                            try {
                                player.play()
                                startedLatch.countDown()
                            } catch (t: Throwable) {
                                logger.warn("Failed to start TTS media player", t)
                                cleanupPlayer(player)
                                startedLatch.countDown()
                                finishedLatch.countDown()
                            }
                        }
                    }
                    player.setOnEndOfMedia {
                        cleanupPlayer(player)
                        finishedLatch.countDown()
                    }
                    player.setOnError {
                        logger.warn("TTS media player error", player.error)
                        cleanupPlayer(player)
                        startedLatch.countDown()
                        finishedLatch.countDown()
                    }
                } catch (t: Throwable) {
                    logger.warn("Failed to create TTS media player", t)
                    startedLatch.countDown()
                    finishedLatch.countDown()
                }
            }
        } catch (t: Throwable) {
            logger.warn("JavaFX platform is unavailable for TTS playback", t)
            startedLatch.countDown()
            finishedLatch.countDown()
        }

        try {
            if (!startedLatch.await(5, TimeUnit.SECONDS)) {
                cancelled.set(true)
                stopCurrentPlayback()
                finishedLatch.countDown()
                return
            }

            while (!stopped.get() && !finishedLatch.await(250, TimeUnit.MILLISECONDS)) {
                if (!configuration.getBoolean(ConfigKeys.Tts.enabled)) {
                    stopCurrentPlayback()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            cancelled.set(true)
            if (currentPlaybackCancellation === cancelled) {
                currentPlaybackCancellation = null
            }
            if (currentPlaybackLatch === finishedLatch) {
                currentPlaybackLatch = null
            }
        }
    }

    private fun readVolume(): Double {
        return try {
            configuration.getDouble(ConfigKeys.Tts.volume).coerceIn(0.0, 1.0)
        } catch (t: Throwable) {
            logger.debug("Invalid TTS volume setting; using default", t)
            1.0
        }
    }

    private fun isEnabled(): Boolean {
        return try {
            configuration.getBoolean(ConfigKeys.Tts.enabled)
        } catch (t: Throwable) {
            logger.debug("Invalid TTS enabled setting", t)
            false
        }
    }

    private fun cleanupPlayer(player: MediaPlayer) {
        if (currentPlayer === player) {
            currentPlayer = null
        }
        player.dispose()
    }

    private fun stopCurrentPlayback() {
        val cancellation = currentPlaybackCancellation
        currentPlaybackCancellation = null
        cancellation?.set(true)

        val latch = currentPlaybackLatch
        currentPlaybackLatch = null

        if (!guiAvailable()) {
            currentPlayer = null
            latch?.countDown()
            return
        }

        try {
            Platform.runLater {
                currentPlayer?.let {
                    try {
                        it.stop()
                    } catch (t: Throwable) {
                        logger.debug("Failed to stop TTS media player", t)
                    }
                    it.dispose()
                    currentPlayer = null
                }
                latch?.countDown()
            }
        } catch (t: Throwable) {
            logger.debug("Failed to schedule TTS stop on JavaFX thread", t)
            currentPlayer = null
            latch?.countDown()
        }
    }
}
