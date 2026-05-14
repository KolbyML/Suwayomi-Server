package suwayomi.tachidesk

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.javalinSetup
import suwayomi.tachidesk.server.applicationSetup
import suwayomi.tachidesk.server.RuntimeMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

private val runtimeKeepAliveLatch = CountDownLatch(1)
private val logger = KotlinLogging.logger {}

private const val runtimeHealthUrl = "http://127.0.0.1:4566/runtime/v1/health"
private const val runtimeWatchdogStartDelayMs = 5000L
private const val runtimeWatchdogIntervalMs = 500L
private const val runtimeWatchdogFailureThreshold = 8

private fun runtimeAppActive(): Boolean {
    val value = System.getProperty("suwayomi.app.active", "true").lowercase()
    return value == "1" || value == "true" || value == "yes" || value == "on"
}

private fun runtimeHealthCheck(): Boolean {
    return runCatching {
        val connection = URL(runtimeHealthUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 700
        connection.readTimeout = 700
        connection.useCaches = false
        connection.instanceFollowRedirects = false

        val isHealthy = connection.responseCode in 200..299
        connection.inputStream?.close()
        connection.disconnect()
        isHealthy
    }.getOrDefault(false)
}

private fun startRuntimeWatchdog() {
    Thread(
        {
            Thread.sleep(runtimeWatchdogStartDelayMs)

            var consecutiveFailures = 0
            var wasInactive = false
            while (true) {
                if (!runtimeAppActive()) {
                    if (!wasInactive) {
                        logger.info { "Runtime health watchdog paused while app is inactive" }
                        wasInactive = true
                    }
                    consecutiveFailures = 0
                    Thread.sleep(runtimeWatchdogIntervalMs)
                    continue
                }

                if (wasInactive) {
                    logger.info { "Runtime health watchdog resumed after app became active" }
                    wasInactive = false
                }

                if (runtimeHealthCheck()) {
                    if (consecutiveFailures > 0) {
                        logger.info {
                            "Runtime health watchdog recovered after $consecutiveFailures consecutive failures"
                        }
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                    if (consecutiveFailures == 1) {
                        logger.warn { "Runtime health watchdog probe failed" }
                    }
                    if (consecutiveFailures >= runtimeWatchdogFailureThreshold) {
                        logger.warn {
                            "Runtime health watchdog detected $consecutiveFailures consecutive failures; restarting Javalin runtime server"
                        }
                        JavalinSetup.restartServer("runtime_health_watchdog")
                        consecutiveFailures = 0
                        Thread.sleep(1500)
                    }
                }

                Thread.sleep(runtimeWatchdogIntervalMs)
            }
        },
        "runtime-health-watchdog",
    ).apply {
        isDaemon = true
        start()
    }
}

fun main() {
    applicationSetup()
    javalinSetup()

    if (RuntimeMode.isRuntimeOnly()) {
        startRuntimeWatchdog()

        Runtime
            .getRuntime()
            .addShutdownHook(
                Thread {
                    runtimeKeepAliveLatch.countDown()
                },
            )

        try {
            runtimeKeepAliveLatch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

fun startSocket() {
    JavalinSetup.javalinStartSocket()
}

fun stopSocket() {
    JavalinSetup.javalinStopSocket()
}

fun waitRequestDone() {
    logger.debug { "waitRequestDone() invoked; runtime currently uses immediate socket suspend semantics" }
}
