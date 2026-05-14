package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.TrackSearchTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.RuntimeStartupMetrics
import suwayomi.tachidesk.server.plugin.ServerPluginRegistry
import suwayomi.tachidesk.server.util.ExitCode
import suwayomi.tachidesk.server.util.shutdownApp
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.SQLException
import java.security.MessageDigest
import java.util.Properties

object DBManager {
    var db: Database? = null
        private set

    data class RuntimeDatabaseSetup(
        val database: Database,
        val runtimePath: Path,
        val templatePath: Path,
        val restoredFromTemplate: Boolean,
        val reusedBootstrapCache: Boolean,
        val bootstrapCacheMarkerPath: Path,
        val manifestFingerprint: String?,
    )

    private const val RUNTIME_SCHEMA_TEMPLATE_VERSION = 1
    private const val RUNTIME_BOOTSTRAP_CACHE_VERSION = 1
    private const val RUNTIME_BOOTSTRAP_CACHE_VERSION_KEY = "version"
    private const val RUNTIME_BOOTSTRAP_CACHE_MANIFEST_HASH_KEY = "manifestSha256"
    private var currentRuntimeSetup: RuntimeDatabaseSetup? = null

    private fun persistentDatabasePath(): Path = Path.of(Injekt.get<ApplicationDirs>().dataRoot, "database.sqlite")

    private fun runtimeDatabasePath(): Path = Path.of(Injekt.get<ApplicationDirs>().dataRoot, "runtime.sqlite")

    private fun runtimeTemplatePath(): Path =
        Path.of(
            Injekt.get<ApplicationDirs>().dataRoot,
            "runtime-template-v$RUNTIME_SCHEMA_TEMPLATE_VERSION.sqlite",
        )

    private fun runtimeBootstrapCacheMarkerPath(): Path =
        Path.of(
            Injekt.get<ApplicationDirs>().dataRoot,
            "runtime-bootstrap-cache-v$RUNTIME_BOOTSTRAP_CACHE_VERSION.properties",
        )

    private fun sqliteJdbcUrl(
        path: Path,
        journalMode: String,
        synchronous: String,
    ): String =
        "jdbc:sqlite:${path.toAbsolutePath()}?busy_timeout=10000&foreign_keys=on&journal_mode=$journalMode&synchronous=$synchronous"

    private fun currentRuntimeManifestFingerprint(): String? {
        val manifestPathString = System.getProperty("manatan.runtimeBootstrapManifest")?.trim().orEmpty()
        if (manifestPathString.isBlank()) {
            return null
        }

        val manifestPath = Path.of(manifestPathString)
        if (!Files.exists(manifestPath)) {
            return null
        }

        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(manifestPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun readBootstrapCacheFingerprint(markerPath: Path): String? {
        if (!Files.exists(markerPath)) {
            return null
        }

        return try {
            Files.newInputStream(markerPath).use { input ->
                Properties().apply { load(input) }.let { props ->
                    val version = props.getProperty(RUNTIME_BOOTSTRAP_CACHE_VERSION_KEY)
                    if (version != RUNTIME_BOOTSTRAP_CACHE_VERSION.toString()) {
                        null
                    } else {
                        props.getProperty(RUNTIME_BOOTSTRAP_CACHE_MANIFEST_HASH_KEY)?.trim()?.ifBlank { null }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read runtime bootstrap cache marker from $markerPath" }
            null
        }
    }

    private fun writeBootstrapCacheMarker(
        markerPath: Path,
        manifestFingerprint: String,
    ) {
        Files.createDirectories(markerPath.parent)
        Files.newOutputStream(markerPath).use { output ->
            Properties().apply {
                setProperty(RUNTIME_BOOTSTRAP_CACHE_VERSION_KEY, RUNTIME_BOOTSTRAP_CACHE_VERSION.toString())
                setProperty(RUNTIME_BOOTSTRAP_CACHE_MANIFEST_HASH_KEY, manifestFingerprint)
                store(output, "Runtime bootstrap cache marker")
            }
        }
    }

    private fun clearBootstrapCacheMarker(markerPath: Path) {
        try {
            Files.deleteIfExists(markerPath)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clear runtime bootstrap cache marker at $markerPath" }
        }
    }

    fun setupDatabase(): Database {
        if (TransactionManager.isInitialized()) {
            val currentDatabase = TransactionManager.currentOrNull()?.db
            if (currentDatabase != null) {
                TransactionManager.closeAndUnregister(currentDatabase)
            }
        }

        val dbConfig =
            DatabaseConfig {
                useNestedTransactions = false
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = false
            }

        val jdbcUrl = sqliteJdbcUrl(persistentDatabasePath(), journalMode = "WAL", synchronous = "NORMAL")

        return Database.connect(
            jdbcUrl,
            "org.sqlite.JDBC",
            databaseConfig = dbConfig,
        ).also { db = it }
    }

    private fun prepareRuntimeDatabaseFile(
        runtimePath: Path,
        templatePath: Path,
    ): Boolean {
        Files.createDirectories(runtimePath.parent)
        Files.deleteIfExists(runtimePath)

        if (!Files.exists(templatePath)) {
            return false
        }

        return try {
            Files.copy(templatePath, runtimePath, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore runtime SQLite template from $templatePath" }
            Files.deleteIfExists(runtimePath)
            false
        }
    }

    fun setupRuntimeDatabase(): RuntimeDatabaseSetup {
        if (TransactionManager.isInitialized()) {
            val currentDatabase = TransactionManager.currentOrNull()?.db
            if (currentDatabase != null) {
                TransactionManager.closeAndUnregister(currentDatabase)
            }
        }

        val dbConfig =
            DatabaseConfig {
                useNestedTransactions = false
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = false
            }

        val runtimePath = runtimeDatabasePath()
        val templatePath = runtimeTemplatePath()
        val bootstrapCacheMarkerPath = runtimeBootstrapCacheMarkerPath()
        val manifestFingerprint = currentRuntimeManifestFingerprint()
        val cachedBootstrapFingerprint = readBootstrapCacheFingerprint(bootstrapCacheMarkerPath)
        val reusedBootstrapCache =
            manifestFingerprint != null &&
                cachedBootstrapFingerprint == manifestFingerprint &&
                Files.exists(runtimePath)
        val restoredFromTemplate =
            if (reusedBootstrapCache) {
                false
            } else {
                clearBootstrapCacheMarker(bootstrapCacheMarkerPath)
                prepareRuntimeDatabaseFile(runtimePath, templatePath)
            }
        val jdbcUrl = sqliteJdbcUrl(runtimePath, journalMode = "MEMORY", synchronous = "OFF")

        val database =
            Database.connect(
                jdbcUrl,
                "org.sqlite.JDBC",
                databaseConfig = dbConfig,
            ).also { db = it }

        return RuntimeDatabaseSetup(
            database = database,
            runtimePath = runtimePath,
            templatePath = templatePath,
            restoredFromTemplate = restoredFromTemplate,
            reusedBootstrapCache = reusedBootstrapCache,
            bootstrapCacheMarkerPath = bootstrapCacheMarkerPath,
            manifestFingerprint = manifestFingerprint,
        )
            .also { currentRuntimeSetup = it }
    }


    fun shutdown() = Unit

    fun getPoolStats(): String? = null

    fun shouldReuseRuntimeBootstrap(): Boolean = currentRuntimeSetup?.reusedBootstrapCache == true

    fun markRuntimeBootstrapReady() {
        val runtimeSetup = currentRuntimeSetup ?: return
        val manifestFingerprint = runtimeSetup.manifestFingerprint ?: return

        try {
            writeBootstrapCacheMarker(runtimeSetup.bootstrapCacheMarkerPath, manifestFingerprint)
            logger.info {
                "Marked runtime bootstrap cache ready marker=${runtimeSetup.bootstrapCacheMarkerPath} manifestFingerprint=$manifestFingerprint"
            }
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to write runtime bootstrap cache marker marker=${runtimeSetup.bootstrapCacheMarkerPath}"
            }
        }
    }
}

private val logger = KotlinLogging.logger {}

private val schemaTables: Array<Table>
    get() =
        arrayOf<Table>(
        ExtensionTable,
        SourceTable,
        SourceMetaTable,
        CategoryTable,
        CategoryMetaTable,
        MangaTable,
        MangaMetaTable,
        ChapterTable,
        ChapterMetaTable,
        PageTable,
        CategoryMangaTable,
        TrackRecordTable,
        TrackSearchTable,
        GlobalMetaTable,
    ) + ServerPluginRegistry.plugins.flatMap { it.databaseTables() }

private val supplementalIndexStatements =
    listOf(
        """CREATE INDEX IF NOT EXISTS idx_chapter_last_read_at ON Chapter(last_read_at);""",
        """CREATE INDEX IF NOT EXISTS Chapter_idx_manga ON Chapter(manga);""",
        """CREATE INDEX IF NOT EXISTS Manga_idx_in_library ON Manga(in_library);""",
        """CREATE INDEX IF NOT EXISTS Manga_idx_source ON Manga(source);""",
        """CREATE INDEX IF NOT EXISTS Page_idx_chapter ON Page(chapter);""",
        """CREATE INDEX IF NOT EXISTS CategoryManga_idx_manga ON CategoryManga(manga);""",
        """CREATE INDEX IF NOT EXISTS CategoryManga_idx_category ON CategoryManga(category);""",
        """CREATE INDEX IF NOT EXISTS CategoryMeta_idx_category_ref ON CategoryMeta(category_ref);""",
        """CREATE INDEX IF NOT EXISTS ChapterMeta_idx_chapter_ref ON ChapterMeta(chapter_ref);""",
        """CREATE INDEX IF NOT EXISTS MangaMeta_idx_manga_ref ON MangaMeta(manga_ref);""",
    )

private fun seedSchema() {
    transaction {
        supplementalIndexStatements.forEach { exec(it) }
        exec(
            """
            INSERT OR IGNORE INTO Category (id, name, sort_order, is_default, include_in_update, include_in_download)
            VALUES (0, 'Default', 0, 1, -1, -1);
            """.trimIndent(),
        )
    }
}

private fun initializeSchema() {
    transaction {
        SchemaUtils.createMissingTablesAndColumns(*schemaTables)
    }
    seedSchema()
}

private fun initializeRuntimeSchema() {
    transaction {
        // Runtime SQLite is recreated from scratch on every launch, so avoid
        // migration-style ALTER statements that Exposed may emit for SQLite.
        SchemaUtils.create(*schemaTables)
    }
    seedSchema()
}

fun databaseUp() {
    val db =
        try {
            DBManager.setupDatabase()
        } catch (e: Exception) {
            logger.error(e) { "Failed to setup Database" }
            return
        }

    logger.info {
        "Using ${db.vendor} database version ${db.version}"
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            DBManager.shutdown()
        },
    )

    try {
        initializeSchema()
    } catch (e: SQLException) {
        logger.error(e) { "Error initializing SQLite database schema" }
        if (System.getProperty("crashOnFailedMigration").toBoolean()) {
            shutdownApp(ExitCode.DbMigrationFailure)
        }
    }
}

fun databaseUpRuntime() {
    RuntimeStartupMetrics.logStage("runtime_database_connect_begin")
    val runtimeSetup =
        try {
            DBManager.setupRuntimeDatabase()
        } catch (e: Exception) {
            logger.error(e) { "Failed to setup runtime database" }
            return
        }
    RuntimeStartupMetrics.logStage("runtime_database_connected")
    val db = runtimeSetup.database

    logger.info {
        "Using ${db.vendor} in-memory runtime database templateRestored=${runtimeSetup.restoredFromTemplate} cacheReused=${runtimeSetup.reusedBootstrapCache}"
    }

    try {
        if (runtimeSetup.reusedBootstrapCache) {
            RuntimeStartupMetrics.logStage("runtime_bootstrap_cache_reused")
            logger.info {
                "Reusing cached runtime bootstrap database path=${runtimeSetup.runtimePath} marker=${runtimeSetup.bootstrapCacheMarkerPath}"
            }
        } else if (runtimeSetup.restoredFromTemplate) {
            RuntimeStartupMetrics.logStage("runtime_schema_template_restore_complete")
            logger.info {
                "Restored runtime SQLite schema template path=${runtimeSetup.templatePath} tableCount=${schemaTables.size}"
            }
        } else {
            val schemaInitStartedAt = System.nanoTime()
            RuntimeStartupMetrics.logStage("runtime_schema_create_begin")
            initializeRuntimeSchema()
            RuntimeStartupMetrics.logStage("runtime_schema_create_complete")
            logger.info {
                "Initialized runtime SQLite schema directly tableCount=${schemaTables.size} elapsedMs=${(System.nanoTime() - schemaInitStartedAt) / 1_000_000}"
            }

            try {
                Files.copy(
                    runtimeSetup.runtimePath,
                    runtimeSetup.templatePath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                logger.info {
                    "Cached runtime SQLite schema template path=${runtimeSetup.templatePath}"
                }
            } catch (e: Exception) {
                logger.warn(e) {
                    "Failed to cache runtime SQLite schema template path=${runtimeSetup.templatePath}"
                }
            }
        }
    } catch (e: SQLException) {
        logger.error(e) { "Error up-to-runtime database migration" }
        if (System.getProperty("crashOnFailedMigration").toBoolean()) {
            shutdownApp(ExitCode.DbMigrationFailure)
        }
    }
}
