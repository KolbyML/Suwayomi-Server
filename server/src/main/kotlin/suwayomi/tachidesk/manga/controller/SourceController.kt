package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import android.widget.Toast
import suwayomi.tachidesk.manga.impl.MangaList
import suwayomi.tachidesk.manga.impl.Search
import suwayomi.tachidesk.manga.impl.Search.FilterChange
import suwayomi.tachidesk.manga.impl.Search.FilterData
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.Source.SourcePreferenceChange
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.dataclass.PagedMangaListDataClass
import suwayomi.tachidesk.manga.model.dataclass.SourceDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy

object SourceController {
    private val logger = KotlinLogging.logger {}

    private fun logMangaResult(kind: String, sourceId: Long, pageNum: Int, result: PagedMangaListDataClass) {
        val sourceIds = result.mangaList.map { it.sourceId }.distinct()
        val sampleTitles = result.mangaList.take(5).map { manga ->
            "${manga.id}:${manga.sourceId}:${manga.title}"
        }
        val items = result.mangaList.joinToString(";") { manga ->
            "${manga.id}:${manga.sourceId}"
        }
        logger.info {
            "runtime $kind result sourceId=$sourceId page=$pageNum count=${result.mangaList.size} hasNext=${result.hasNextPage} sourceIds=$sourceIds sampleTitles=$sampleTitles items=$items"
        }
    }

    private fun applyCapturedToasts(ctx: io.javalin.http.Context, captured: List<String>) {
        val uniqueToasts = captured.distinct().filter { it.isNotBlank() }
        if (uniqueToasts.isNotEmpty()) {
            ctx.header("x-manatan-toast", uniqueToasts.last())
            ctx.header("x-manatan-toast-variant", "info")
        }
    }
    /** list of sources */
    val list =
        handler(
            documentWith = {
                withOperation {
                    summary("Sources list")
                    description("List of sources")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Source.getSourceList())
            },
            withResults = {
                json<Array<SourceDataClass>>(HttpStatus.OK)
            },
        )

    /** fetch source with id `sourceId` */
    val retrieve =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source fetch")
                    description("Fetch source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val source = Source.getSource(sourceId) ?: throw NotFoundResponse()
                ctx.json(source)
            },
            withResults = {
                json<SourceDataClass>(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    /** popular mangas from source with id `sourceId` */
    val popular =
        handler(
            pathParam<Long>("sourceId"),
            pathParam<Int>("pageNum"),
            documentWith = {
                withOperation {
                    summary("Source popular manga")
                    description("Popular mangas from source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                logger.info { "runtime popular request sourceId=$sourceId page=$pageNum" }
                ctx.future {
                    future {
                        try {
                            Toast.beginCapture()
                            val result = try {
                                MangaList.getMangaList(sourceId, pageNum, popular = true)
                            } finally {
                                // handled below so we always clear capture before leaving the worker
                            }
                            val captured = Toast.endCapture()
                            logMangaResult("popular", sourceId, pageNum, result)
                            result to captured
                        } catch (e: StubSource.SourceNotInstalledException) {
                            Toast.endCapture()
                            logger.warn { "runtime popular source not installed sourceId=$sourceId" }
                            throw NotFoundResponse(e.message ?: "Source not installed")
                        } catch (e: Exception) {
                            Toast.endCapture()
                            throw e
                        }
                    }.thenApply {
                        applyCapturedToasts(ctx, it.second)
                        ctx.json(it.first)
                    }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** latest mangas from source with id `sourceId` */
    val latest =
        handler(
            pathParam<Long>("sourceId"),
            pathParam<Int>("pageNum"),
            documentWith = {
                withOperation {
                    summary("Source latest manga")
                    description("Latest mangas from source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                logger.info { "runtime latest request sourceId=$sourceId page=$pageNum" }
                ctx.future {
                    future {
                        try {
                            Toast.beginCapture()
                            val result = try {
                                MangaList.getMangaList(sourceId, pageNum, popular = false)
                            } finally {
                                // handled below so we always clear capture before leaving the worker
                            }
                            val captured = Toast.endCapture()
                            logMangaResult("latest", sourceId, pageNum, result)
                            result to captured
                        } catch (e: StubSource.SourceNotInstalledException) {
                            Toast.endCapture()
                            logger.warn { "runtime latest source not installed sourceId=$sourceId" }
                            throw NotFoundResponse(e.message ?: "Source not installed")
                        } catch (e: Exception) {
                            Toast.endCapture()
                            throw e
                        }
                    }.thenApply {
                        applyCapturedToasts(ctx, it.second)
                        ctx.json(it.first)
                    }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** fetch preferences of source with id `sourceId` */
    val getPreferences =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source preferences")
                    description("Fetch preferences of source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Source.getSourcePreferences(sourceId))
            },
            withResults = {
                json<Array<Source.PreferenceObject>>(HttpStatus.OK)
            },
        )

    /** set one preference of source with id `sourceId` */
    val setPreference =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source preference set")
                    description("Set one preference of source with id `sourceId`")
                }
                body<SourcePreferenceChange>()
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val preferenceChange = ctx.bodyAsClass(SourcePreferenceChange::class.java)
                Toast.beginCapture()
                val captured = try {
                    Source.setSourcePreference(sourceId, preferenceChange.position, preferenceChange.value)
                    Toast.endCapture()
                } catch (e: Exception) {
                    Toast.endCapture()
                    throw e
                }
                val uniqueToasts = captured.distinct().filter { it.isNotBlank() }
                if (uniqueToasts.isNotEmpty()) {
                    ctx.header("x-manatan-toast", uniqueToasts.last())
                    ctx.header("x-manatan-toast-variant", "info")
                }
                ctx.json(mapOf("ok" to true))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** fetch filters of source with id `sourceId` */
    val getFilters =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("reset", false),
            documentWith = {
                withOperation {
                    summary("Source filters")
                    description("Fetch filters of source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, reset ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Search.getFilterList(sourceId, reset))
            },
            withResults = {
                json<Array<Search.FilterObject>>(HttpStatus.OK)
            },
        )

    private val json: Json by injectLazy()

    /** change filters of source with id `sourceId` */
    val setFilters =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source filters set")
                    description("Change filters of source with id `sourceId`")
                }
                body<FilterChange>()
                body<Array<FilterChange>>()
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val filterChange =
                    try {
                        json.decodeFromString<List<FilterChange>>(ctx.body())
                    } catch (e: Exception) {
                        listOf(json.decodeFromString<FilterChange>(ctx.body()))
                    }

                ctx.json(Search.setFilter(sourceId, filterChange))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** single source search */
    val searchSingle =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("searchTerm", ""),
            queryParam("pageNum", 1),
            documentWith = {
                withOperation {
                    summary("Source search")
                    description("Single source search")
                }
            },
            behaviorOf = { ctx, sourceId, searchTerm, pageNum ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                logger.info {
                    "runtime search request sourceId=$sourceId page=$pageNum termLength=${searchTerm.length}"
                }
                ctx.future {
                    future {
                        try {
                            Toast.beginCapture()
                            val result = try {
                                Search.sourceSearch(sourceId, searchTerm, pageNum)
                            } finally {
                                // handled below so we always clear capture before leaving the worker
                            }
                            val captured = Toast.endCapture()
                            logMangaResult("search", sourceId, pageNum, result)
                            result to captured
                        } catch (e: StubSource.SourceNotInstalledException) {
                            Toast.endCapture()
                            logger.warn { "runtime search source not installed sourceId=$sourceId" }
                            throw NotFoundResponse(e.message ?: "Source not installed")
                        } catch (e: Exception) {
                            Toast.endCapture()
                            throw e
                        }
                    }.thenApply {
                        applyCapturedToasts(ctx, it.second)
                        ctx.json(it.first)
                    }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** quick search single source filter */
    val quickSearchSingle =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("pageNum", 1),
            documentWith = {
                withOperation {
                    summary("Source manga quick search")
                    description("Returns list of manga from source matching posted searchTerm and filter")
                }
                body<FilterData>()
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val filter = json.decodeFromString<FilterData>(ctx.body())
                logger.info { "runtime quick search request sourceId=$sourceId page=$pageNum" }
                ctx.future {
                    future {
                        try {
                            Toast.beginCapture()
                            val result = try {
                                Search.sourceFilter(sourceId, pageNum, filter)
                            } finally {
                                // handled below so we always clear capture before leaving the worker
                            }
                            val captured = Toast.endCapture()
                            logMangaResult("quick-search", sourceId, pageNum, result)
                            result to captured
                        } catch (e: StubSource.SourceNotInstalledException) {
                            Toast.endCapture()
                            logger.warn { "runtime quick search source not installed sourceId=$sourceId" }
                            throw NotFoundResponse(e.message ?: "Source not installed")
                        } catch (e: Exception) {
                            Toast.endCapture()
                            throw e
                        }
                    }.thenApply {
                        applyCapturedToasts(ctx, it.second)
                        ctx.json(it.first)
                    }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** all source search */
    val searchAll =
        handler(
            pathParam<String>("searchTerm"),
            documentWith = {
                withOperation {
                    summary("Source global search")
                    description("All source search")
                }
            },
            behaviorOf = { ctx, searchTerm ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                // TODO
                ctx.json(Search.sourceGlobalSearch(searchTerm))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )
}
