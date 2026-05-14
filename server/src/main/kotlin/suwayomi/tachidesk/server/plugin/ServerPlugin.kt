package suwayomi.tachidesk.server.plugin

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.TopLevelObject
import okhttp3.OkHttpClient
import org.jetbrains.exposed.sql.Table
import suwayomi.tachidesk.server.ApplicationDirs

interface ServerPlugin {
    fun databaseTables(): List<Table> = emptyList()

    fun graphQLQueries(): List<TopLevelObject> = emptyList()

    fun graphQLMutations(): List<TopLevelObject> = emptyList()

    fun graphQLDataLoaders(): List<KotlinDataLoader<*, *>> = emptyList()

    fun configureNetworkClient(builder: OkHttpClient.Builder) = Unit

    fun defineApiV1Routes() = Unit

    fun defineRuntimeV1Routes() = Unit

    fun onApplicationDirsReady(applicationDirs: ApplicationDirs) = Unit

    fun onPersistentDatabaseReady() = Unit

    fun onRuntimeDatabaseReady() = Unit
}
