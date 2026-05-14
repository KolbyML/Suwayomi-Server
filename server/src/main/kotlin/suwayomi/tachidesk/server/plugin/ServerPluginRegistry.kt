package suwayomi.tachidesk.server.plugin

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import suwayomi.tachidesk.server.ApplicationDirs
import java.util.ServiceLoader

object ServerPluginRegistry {
    private val logger = KotlinLogging.logger {}

    val plugins: List<ServerPlugin> by lazy {
        ServiceLoader
            .load(ServerPlugin::class.java)
            .toList()
            .also { plugins ->
                if (plugins.isNotEmpty()) {
                    logger.info { "Loaded server plugins: ${plugins.joinToString { it::class.java.name }}" }
                }
            }
    }

    fun onApplicationDirsReady(applicationDirs: ApplicationDirs) {
        plugins.forEach { plugin -> plugin.onApplicationDirsReady(applicationDirs) }
    }

    fun onPersistentDatabaseReady() {
        plugins.forEach { plugin -> plugin.onPersistentDatabaseReady() }
    }

    fun onRuntimeDatabaseReady() {
        plugins.forEach { plugin -> plugin.onRuntimeDatabaseReady() }
    }

    fun configureNetworkClient(builder: OkHttpClient.Builder) {
        plugins.forEach { plugin -> plugin.configureNetworkClient(builder) }
    }

    fun defineRuntimeV1Routes() {
        plugins.forEach { plugin -> plugin.defineRuntimeV1Routes() }
    }
}
