package com.orbitalhq.nebula.cli

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Watches a file for changes and triggers a callback when modifications are detected.
 */
class FileWatcher(
    private val file: File,
    private val onChange: (File) -> Unit
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val watching = AtomicBoolean(false)
    private var watcherThread: Thread? = null

    /**
     * Starts watching the file for changes.
     */
    fun start() {
        if (watching.getAndSet(true)) {
            logger.warn { "FileWatcher is already running for ${file.absolutePath}" }
            return
        }

        logger.info { "Starting file watcher for ${file.absolutePath}" }

        watcherThread = thread(name = "FileWatcher-${file.name}") {
            val watchService: WatchService = FileSystems.getDefault().newWatchService()
            // Use absoluteFile to ensure we have a parent directory
            val directory = (file.parentFile ?: File(".")).toPath()

            try {
                directory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE
                )

                while (watching.get()) {
                    val key: WatchKey = try {
                        watchService.take()
                    } catch (e: InterruptedException) {
                        logger.debug { "File watcher interrupted" }
                        break
                    }

                    for (event in key.pollEvents()) {
                        val kind = event.kind()

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue
                        }

                        @Suppress("UNCHECKED_CAST")
                        val ev = event as WatchEvent<Path>
                        val filename = ev.context()

                        // Check if the modified file is the one we're watching
                        if (filename.toString() == file.name) {
                            logger.info { "Detected change in ${file.name}" }
                            try {
                                onChange(file)
                            } catch (e: Exception) {
                                logger.error(e) { "Error processing file change" }
                            }
                        }
                    }

                    val valid = key.reset()
                    if (!valid) {
                        logger.warn { "Watch key no longer valid, stopping watcher" }
                        break
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in file watcher" }
            } finally {
                try {
                    watchService.close()
                } catch (e: Exception) {
                    logger.error(e) { "Error closing watch service" }
                }
            }
        }
    }

    /**
     * Stops watching the file.
     */
    fun stop() {
        if (!watching.getAndSet(false)) {
            return
        }

        logger.info { "Stopping file watcher for ${file.absolutePath}" }
        watcherThread?.interrupt()
        watcherThread?.join(5000)
    }
}
