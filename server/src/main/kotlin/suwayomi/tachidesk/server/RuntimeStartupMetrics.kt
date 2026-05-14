package suwayomi.tachidesk.server

import io.github.oshai.kotlinlogging.KotlinLogging

object RuntimeStartupMetrics {
    private val logger = KotlinLogging.logger {}
    private val startupEpochNs = System.nanoTime()

    fun elapsedMs(): Long = (System.nanoTime() - startupEpochNs) / 1_000_000

    fun logStage(stage: String) {
        val elapsedMs = elapsedMs()
        System.err.println("RUNTIME_STARTUP_METRIC stage=$stage elapsedMs=$elapsedMs")
        logger.info { "RUNTIME_STARTUP_METRIC stage=$stage elapsedMs=$elapsedMs" }
    }
}
