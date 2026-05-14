package suwayomi.tachidesk.manga.impl.util.source

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.PackageTools.loadExtensionSources
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

object GetCatalogueSource {
    private val logger = KotlinLogging.logger { }

    private val sourceCache = ConcurrentHashMap<Long, CatalogueSource>()
    private val applicationDirs: ApplicationDirs by injectLazy()

    private fun getCatalogueSource(sourceId: Long): CatalogueSource? {
        val cachedResult: CatalogueSource? = sourceCache[sourceId]
        if (cachedResult != null) {
            return cachedResult
        }

        val sourceRecord =
            transaction {
                SourceTable.selectAll().where { SourceTable.id eq sourceId }.firstOrNull()
            } ?: run {
                logger.warn { "getCatalogueSource missing SourceTable row for sourceId=$sourceId" }
                return null
            }

        val extensionId = sourceRecord[SourceTable.extension]
        val extensionRecord =
            transaction {
                ExtensionTable.selectAll().where { ExtensionTable.id eq extensionId }.first()
            }

        val apkName = extensionRecord[ExtensionTable.apkName]
        val pkgName = extensionRecord[ExtensionTable.pkgName]
        val jarName = apkName.substringBefore(".apk") + ".jar"
        val jarPath = "${applicationDirs.extensionsRoot}/$jarName"
        val className =
            resolveClassName(
                extensionId = extensionId.value,
                pkgName = pkgName,
                apkName = apkName,
                existingClassName = extensionRecord[ExtensionTable.classFQName],
            ) ?: return null

        logger.info {
            "getCatalogueSource sourceId=$sourceId extensionId=${extensionId.value} pkg=$pkgName apk=$apkName class=$className jar=$jarPath"
        }

        when (val instance = loadExtensionSources(jarPath, className)) {
            is Source -> listOf(instance)
            is SourceFactory -> instance.createSources()
            else -> throw Exception("Unknown source class type! ${instance.javaClass}")
        }.forEach {
            logger.info {
                "registering catalogue source id=${it.id} name=${it.name} lang=${it.lang} from class=$className"
            }
            sourceCache[it.id] = it as HttpSource
        }
        return sourceCache[sourceId]!!
    }

    private fun resolveClassName(
        extensionId: Int,
        pkgName: String,
        apkName: String,
        existingClassName: String,
    ): String? {
        if (existingClassName.isNotBlank()) {
            return existingClassName
        }

        val apkPath = "${applicationDirs.extensionsRoot}/$apkName"
        val startedAt = System.nanoTime()
        val derivedClassName = PackageTools.deriveExtensionClassName(apkPath, pkgName, isAnime = false)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (derivedClassName.isNullOrBlank()) {
            logger.warn {
                "getCatalogueSource failed to derive class name pkg=$pkgName apk=$apkName elapsedMs=$elapsedMs"
            }
            return null
        }

        transaction {
            ExtensionTable.update({ ExtensionTable.id eq extensionId }) {
                it[classFQName] = derivedClassName
            }
        }
        logger.info {
            "getCatalogueSource lazily derived class name pkg=$pkgName apk=$apkName class=$derivedClassName elapsedMs=$elapsedMs"
        }
        return derivedClassName
    }

    fun getCatalogueSourceOrNull(sourceId: Long): CatalogueSource? =
        try {
            getCatalogueSource(sourceId)
        } catch (e: Exception) {
            logger.warn(e) { "getCatalogueSource($sourceId) failed" }
            null
        }

    fun getCatalogueSourceOrStub(sourceId: Long): CatalogueSource = getCatalogueSourceOrNull(sourceId) ?: StubSource(sourceId)

    fun registerCatalogueSource(sourcePair: Pair<Long, CatalogueSource>) {
        sourceCache += sourcePair
    }

    fun unregisterCatalogueSource(sourceId: Long) {
        sourceCache.remove(sourceId)
    }

    fun unregisterAllCatalogueSources() {
        (sourceCache - 0L).forEach { (id, _) ->
            sourceCache.remove(id)
        }
    }
}
