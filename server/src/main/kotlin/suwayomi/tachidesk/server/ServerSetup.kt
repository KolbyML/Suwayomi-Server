package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.os.Looper
import ch.qos.logback.classic.Level
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import com.typesafe.config.parser.ConfigDocument
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.local.LocalSource
import io.github.config4k.toConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.json.JavalinJackson
import io.javalin.json.JsonMapper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.impl.update.Updater
import suwayomi.tachidesk.manga.impl.util.lang.renameTo
import suwayomi.tachidesk.server.database.databaseUp
import suwayomi.tachidesk.server.database.databaseUpRuntime
import suwayomi.tachidesk.server.generated.BuildConfig
import suwayomi.tachidesk.server.plugin.ServerPluginRegistry
import suwayomi.tachidesk.server.settings.SettingsRegistry
import suwayomi.tachidesk.server.util.AppMutex.handleAppMutex
import suwayomi.tachidesk.server.util.ConfigTypeRegistration
import suwayomi.tachidesk.server.util.ExitCode
import suwayomi.tachidesk.server.util.SystemTray
import suwayomi.tachidesk.server.util.shutdownApp
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule
import xyz.nulldev.ts.config.ApplicationRootDir
import xyz.nulldev.ts.config.BASE_LOGGER_NAME
import xyz.nulldev.ts.config.GlobalConfigManager
import xyz.nulldev.ts.config.configManagerModule
import xyz.nulldev.ts.config.initLoggerConfig
import xyz.nulldev.ts.config.setLogLevelFor
import xyz.nulldev.ts.config.updateFileAppender
import java.io.File
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.Security
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

private val logger = KotlinLogging.logger {}

private fun shouldDeferEmbeddedLoggerInit(runtimeOnly: Boolean): Boolean =
    runtimeOnly && !System.getProperty("manatan.runtimeBootstrapManifest").isNullOrBlank()

private fun applyProxySettings(
    proxyEnabled: Boolean,
    proxyVersion: Int,
    proxyHost: String,
    proxyPort: String,
    proxyUsername: String,
    proxyPassword: String,
) {
    if (proxyEnabled) {
        System.setProperty("socksProxyHost", proxyHost)
        System.setProperty("socksProxyPort", proxyPort)
        System.setProperty("socksProxyVersion", proxyVersion.toString())

        Authenticator.setDefault(
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestingProtocol.startsWith("SOCKS", ignoreCase = true)) {
                        return PasswordAuthentication(
                            proxyUsername,
                            proxyPassword.toCharArray(),
                        )
                    }

                    return null
                }
            },
        )
    } else {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        System.clearProperty("socksProxyVersion")

        Authenticator.setDefault(null)
    }
}

private fun registerProxySettingsSubscription() {
    serverConfig.subscribeTo(
        combine<Any, ProxySettings>(
            serverConfig.socksProxyEnabled,
            serverConfig.socksProxyVersion,
            serverConfig.socksProxyHost,
            serverConfig.socksProxyPort,
            serverConfig.socksProxyUsername,
            serverConfig.socksProxyPassword,
        ) { vargs ->
            ProxySettings(
                vargs[0] as Boolean,
                vargs[1] as Int,
                vargs[2] as String,
                vargs[3] as String,
                vargs[4] as String,
                vargs[5] as String,
            )
        }.distinctUntilChanged(),
        { (proxyEnabled, proxyVersion, proxyHost, proxyPort, proxyUsername, proxyPassword) ->
            logger.info {
                "Socks Proxy changed - enabled=$proxyEnabled address=$proxyHost:$proxyPort , username=[REDACTED], password=[REDACTED]"
            }
            applyProxySettings(
                proxyEnabled = proxyEnabled,
                proxyVersion = proxyVersion,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
            )
        },
        ignoreInitialValue = false,
    )
}

private fun startDeferredRuntimeOnlyServices(
    applicationDirs: ApplicationDirs,
    deferEmbeddedLoggerInit: Boolean,
) {
    thread(name = "runtime-only-startup", isDaemon = true) {
        val startedAt = System.nanoTime()

        registerProxySettingsSubscription()
        val proxyElapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info { "Deferred runtime-only proxy subscription complete elapsedMs=$proxyElapsedMs" }

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val cryptoElapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info { "Deferred runtime-only crypto provider ready elapsedMs=$cryptoElapsedMs" }

        if (deferEmbeddedLoggerInit) {
            startDeferredLoggerInitialization(applicationDirs)
            logger.info { "Deferred runtime-only logger init launched elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}" }
        }
    }
}

private fun ensureDirectories(paths: List<String>) {
    paths.forEach { File(it).mkdirs() }
}

private fun ensureServerConf(applicationDirs: ApplicationDirs) {
    try {
        val dataConfFile = File("${applicationDirs.dataRoot}/server.conf")
        if (!dataConfFile.exists()) {
            JavalinSetup::class.java.getResourceAsStream("/server-reference.conf").use { input ->
                dataConfFile.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            GlobalConfigManager.updateUserConfig { migrateConfig(this, it) }
        }
    } catch (e: Exception) {
        logger.error(e) { "Exception while creating initial server.conf" }
    }
}

private fun ensureLocalSourceIcon(applicationDirs: ApplicationDirs) {
    try {
        val localSourceIconFile = File("${applicationDirs.extensionsRoot}/icon/localSource.png")
        if (!localSourceIconFile.exists()) {
            JavalinSetup::class.java.getResourceAsStream("/icon/localSource.png").use { input ->
                localSourceIconFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "Exception while copying Local source's icon" }
    }
}

private fun startDeferredRuntimeOnlyDirectorySetup(applicationDirs: ApplicationDirs) {
    thread(name = "runtime-only-dirs", isDaemon = true) {
        val startedAt = System.nanoTime()
        ensureDirectories(
            listOf(
                applicationDirs.tempThumbnailCacheRoot,
                applicationDirs.downloadsRoot,
            ),
        )
        logger.info { "Deferred runtime-only directory setup complete elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}" }
    }
}

private fun startDeferredRuntimeOnlyStaticArtifacts(applicationDirs: ApplicationDirs) {
    thread(name = "runtime-only-static-artifacts", isDaemon = true) {
        val startedAt = System.nanoTime()
        ensureServerConf(applicationDirs)
        val serverConfElapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info { "Deferred runtime-only server.conf setup complete elapsedMs=$serverConfElapsedMs" }

        ensureLocalSourceIcon(applicationDirs)
        val iconElapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info { "Deferred runtime-only local source icon setup complete elapsedMs=$iconElapsedMs" }
    }
}

private fun initializeLoggerPipeline(applicationDirs: ApplicationDirs) {
    initLoggerConfig(
        applicationDirs.dataRoot,
        serverConfig.maxLogFiles.value,
        serverConfig.maxLogFileSize.value,
        serverConfig.maxLogFolderSize.value,
    )

    serverConfig.subscribeTo(
        combine(
            serverConfig.maxLogFiles,
            serverConfig.maxLogFileSize,
            serverConfig.maxLogFolderSize,
        ) { maxLogFiles, maxLogFileSize, maxLogFolderSize ->
            Triple(maxLogFiles, maxLogFileSize, maxLogFolderSize)
        }.distinctUntilChanged(),
        { (maxLogFiles, maxLogFileSize, maxLogFolderSize) ->
            logger.debug {
                "updateFileAppender: maxLogFiles= $maxLogFiles, maxLogFileSize= $maxLogFileSize, maxLogFolderSize= $maxLogFolderSize"
            }
            updateFileAppender(maxLogFiles, maxLogFileSize, maxLogFolderSize)
        },
    )

    setupLogLevelUpdating(serverConfig.debugLogsEnabled, listOf(BASE_LOGGER_NAME))
}

private fun startDeferredLoggerInitialization(applicationDirs: ApplicationDirs) {
    thread(name = "embedded-logger-init", isDaemon = true) {
        val startedAt = System.nanoTime()
        initializeLoggerPipeline(applicationDirs)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info { "Deferred embedded logger initialization complete elapsedMs=$elapsedMs" }
    }
}

class ApplicationDirs(
    val dataRoot: String = ApplicationRootDir,
    val tempRoot: String = "${System.getProperty("java.io.tmpdir")}/Tachidesk",
) {
    private val configuredDownloadsRoot = System.getProperty("suwayomi.tachidesk.config.server.downloadsPath").orEmpty()
    private val configuredLocalMangaRoot = System.getProperty("suwayomi.tachidesk.config.server.localSourcePath").orEmpty()
    private val configuredLocalAnimeRoot = System.getProperty("suwayomi.tachidesk.config.server.localAnimeSourcePath").orEmpty()
    private val configuredBackupRoot = System.getProperty("suwayomi.tachidesk.config.server.backupPath").orEmpty()

    val extensionsRoot = "$dataRoot/extensions"
    val downloadsRoot
        get() = configuredDownloadsRoot.ifBlank { serverConfig.downloadsPath.value.ifBlank { "$dataRoot/downloads" } }
    val localMangaRoot
        get() = configuredLocalMangaRoot.ifBlank { serverConfig.localSourcePath.value.ifBlank { "$dataRoot/local" } }
    val localAnimeRoot
        get() = configuredLocalAnimeRoot.ifBlank { serverConfig.localAnimeSourcePath.value.ifBlank { "$dataRoot/localanime" } }
    val webUIRoot = "$dataRoot/webUI"
    val webUIServe = "$tempRoot/webUI-serve"
    val automatedBackupRoot
        get() = configuredBackupRoot.ifBlank { serverConfig.backupPath.value.ifBlank { "$dataRoot/backups" } }

    val tempThumbnailCacheRoot = "$tempRoot/thumbnails"
    val tempMangaCacheRoot = "$tempRoot/manga-cache"

    val thumbnailDownloadsRoot
        get() = "$downloadsRoot/thumbnails"
    val mangaDownloadsRoot
        get() = "$downloadsRoot/mangas"
    val animeDownloadsRoot
        get() = "$downloadsRoot/anime"
}

@Suppress("DEPRECATION")
class LooperThread : Thread() {
    override fun run() {
        logger.info { "Starting Android Main Loop" }
        Looper.prepareMainLooper()
        Looper.loop()
    }
}

data class ProxySettings(
    val proxyEnabled: Boolean,
    val socksProxyVersion: Int,
    val proxyHost: String,
    val proxyPort: String,
    val proxyUsername: String,
    val proxyPassword: String,
)

val androidCompat by lazy { AndroidCompat() }

fun setupLogLevelUpdating(
    configFlow: MutableStateFlow<Boolean>,
    loggerNames: List<String>,
    defaultLevel: Level = Level.INFO,
) {
    serverConfig.subscribeTo(
        configFlow,
        { debugLogsEnabled ->
            loggerNames.forEach { loggerName ->
                setLogLevelFor(loggerName, if (debugLogsEnabled) Level.DEBUG else defaultLevel)
            }
        },
        ignoreInitialValue = false,
    )
}

fun migrateConfigValue(
    configDocument: ConfigDocument,
    config: Config,
    configKey: String,
    toConfigKey: String,
    toType: (ConfigValue) -> Any?,
): ConfigDocument {
    try {
        val configValue = config.getValue(configKey)
        val typedValue = toType(configValue)
        if (typedValue != null) {
            logger.debug { "Migrating config value: $configKey -> $toConfigKey" }
            return configDocument.withValue(
                toConfigKey,
                typedValue.toConfig("internal").getValue("internal"),
            )
        }
    } catch (_: ConfigException) {
        // ignore, likely already migrated
    }

    return configDocument
}

fun migrateConfig(
    configDocument: ConfigDocument,
    config: Config,
): ConfigDocument {
    var updatedConfig = configDocument

    val settingsRequiringMigration = SettingsRegistry.getAll().filterValues { it.deprecated?.replaceWith != null }
    settingsRequiringMigration.forEach { (name, data) ->
        val configKey = "server.$name"
        val toConfigKey = "server.${data.deprecated!!.replaceWith}"

        try {
            config.getValue(configKey)
        } catch (_: ConfigException) {
            // Ignore, no migration required
            return@forEach
        }

        logger.debug { "Migrating config value: $configKey -> $toConfigKey" }

        try {
            if (data.deprecated!!.migrateConfig != null) {
                updatedConfig = data.deprecated!!.migrateConfig!!(config.getValue(configKey), updatedConfig)
                return@forEach
            }

            if (data.deprecated!!.migrateConfigValue != null) {
                updatedConfig =
                    migrateConfigValue(
                        updatedConfig,
                        config,
                        configKey,
                        toConfigKey,
                        data.deprecated!!.migrateConfigValue!!,
                    )
                return@forEach
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to migrate config value: $configKey -> $toConfigKey" }
            return@forEach
        }

        shutdownApp(ExitCode.ConfigMigrationMisconfiguredFailure)
    }

    return updatedConfig
}

fun serverModule(applicationDirs: ApplicationDirs): Module =
    module {
        single { applicationDirs }
        single<IUpdater> { Updater() }
        single<JsonMapper> { JavalinJackson() }
    }

@OptIn(DelicateCoroutinesApi::class)
fun applicationSetup() {
    RuntimeStartupMetrics.logStage("application_setup_enter")
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        KotlinLogging.logger {}.error(throwable) { "unhandled exception" }
    }

    val runtimeOnly = RuntimeMode.isRuntimeOnly()
    if (runtimeOnly) {
        logger.info { "Runtime-only mode enabled; skipping database and background services." }
    }
    val deferEmbeddedLoggerInit = shouldDeferEmbeddedLoggerInit(runtimeOnly)

    val mainLoop = LooperThread()
    mainLoop.start()
    RuntimeStartupMetrics.logStage("main_loop_started")

    // register Tachidesk's config which is dubbed "ServerConfig"
    ConfigTypeRegistration.registerCustomTypes()
    GlobalConfigManager.registerModule(
        ServerConfig.register { GlobalConfigManager.config },
    )
    RuntimeStartupMetrics.logStage("config_registered")

    // Application dirs
    val applicationDirs = ApplicationDirs()
    ServerPluginRegistry.onApplicationDirsReady(applicationDirs)

    if (deferEmbeddedLoggerInit) {
        RuntimeStartupMetrics.logStage("logger_init_deferred")
    } else {
        initializeLoggerPipeline(applicationDirs)
        RuntimeStartupMetrics.logStage("logger_initialized")
    }

    logger.info { "Running Suwayomi-Server ${BuildConfig.VERSION}" }
    RuntimeStartupMetrics.logStage("version_logged")

    logger.debug {
        "Loaded config:\n" +
            GlobalConfigManager
                .getRedactedConfig(
                    SettingsRegistry
                        .getAll()
                        .filter { !it.value.privacySafe }
                        .keys
                        .toList(),
                ).root()
                .render(ConfigRenderOptions.concise().setFormatted(true))
    }

    logger.debug { "Data Root directory is set to: ${applicationDirs.dataRoot}" }

    // Migrate Directories from old versions
    File("$ApplicationRootDir/manga-thumbnails").renameTo(applicationDirs.tempThumbnailCacheRoot)
    File("$ApplicationRootDir/manga-local").renameTo(applicationDirs.localMangaRoot)
    File("$ApplicationRootDir/anime-thumbnails").delete()
    RuntimeStartupMetrics.logStage("legacy_dirs_migrated")

    val criticalDirs =
        listOf(
            applicationDirs.dataRoot,
            applicationDirs.extensionsRoot,
            applicationDirs.extensionsRoot + "/icon",
            applicationDirs.localMangaRoot,
            applicationDirs.localAnimeRoot,
        )
    ensureDirectories(criticalDirs)
    RuntimeStartupMetrics.logStage("critical_dirs_ready")

    if (runtimeOnly) {
        startDeferredRuntimeOnlyDirectorySetup(applicationDirs)
        RuntimeStartupMetrics.logStage("runtime_only_deferred_dirs_started")
    } else {
        ensureDirectories(
            listOf(
                applicationDirs.tempThumbnailCacheRoot,
                applicationDirs.downloadsRoot,
            ),
        )
        RuntimeStartupMetrics.logStage("all_dirs_ready")
    }

    // initialize Koin modules
    val app = App()
    RuntimeStartupMetrics.logStage("koin_start_begin")
    startKoin {
        modules(
            createAppModule(app),
            androidCompatModule(),
            configManagerModule(),
            serverModule(applicationDirs),
        )
    }
    RuntimeStartupMetrics.logStage("koin_started")

    // Make sure only one instance of the app is running
    handleAppMutex()
    RuntimeStartupMetrics.logStage("app_mutex_ready")

    // Load Android compatibility dependencies
    AndroidCompatInitializer().init()
    RuntimeStartupMetrics.logStage("android_compat_initializer_ready")
    // start app
    androidCompat.startApp(app)
    RuntimeStartupMetrics.logStage("android_compat_app_started")

    // Initialize NetworkHelper early
    Injekt
        .get<NetworkHelper>()
        .userAgentFlow
        .onEach { System.setProperty("http.agent", it) }
        .launchIn(GlobalScope)
    RuntimeStartupMetrics.logStage("network_helper_initialized")

    if (runtimeOnly) {
        startDeferredRuntimeOnlyStaticArtifacts(applicationDirs)
        RuntimeStartupMetrics.logStage("server_conf_deferred")
        RuntimeStartupMetrics.logStage("local_source_icon_deferred")
    } else {
        ensureServerConf(applicationDirs)
        RuntimeStartupMetrics.logStage("server_conf_ready")

        ensureLocalSourceIcon(applicationDirs)
        RuntimeStartupMetrics.logStage("local_source_icon_ready")
    }

    // fixes #119 , ref:
    // https://github.com/Suwayomi/Suwayomi-Server/issues/119#issuecomment-894681292 , source Id
    // calculation depends on String.lowercase()
    Locale.setDefault(Locale.ENGLISH)

    // Initialize the localization service
    LocalizationHelper.initialize()
    RuntimeStartupMetrics.logStage("localization_initialized")
    logger.debug {
        "Localization service initialized. Supported languages: ${LocalizationHelper.getSupportedLocales()}"
    }

    if (runtimeOnly) {
        setLogLevelFor("Exposed", Level.WARN)
        setLogLevelFor("org.jetbrains.exposed", Level.WARN)
        RuntimeStartupMetrics.logStage("runtime_sql_logging_quieted")
        databaseUpRuntime()
        RuntimeStartupMetrics.logStage("runtime_database_ready")
        ServerPluginRegistry.onRuntimeDatabaseReady()
        LocalSource.register()
        RuntimeStartupMetrics.logStage("local_manga_source_registered")
    } else {
        databaseUp()
        ServerPluginRegistry.onPersistentDatabaseReady()

        LocalSource.register()

        // create system tray
        serverConfig.subscribeTo(
            serverConfig.systemTrayEnabled,
            { systemTrayEnabled ->
                try {
                    if (systemTrayEnabled) {
                        SystemTray.create()
                    } else {
                        SystemTray.remove()
                    }
                } catch (e: Throwable) {
                    // cover both java.lang.Exception and java.lang.Error
                    logger.error(e) { "Failed to create/remove SystemTray due to" }
                }
            },
            ignoreInitialValue = false,
        )

        runMigrations(applicationDirs)
    }

    setLogLevelFor("org.eclipse.jetty", Level.OFF)
    setLogLevelFor("com.zaxxer.hikari", Level.WARN)

    // Some Windows environments deny getsockopt on non-blocking connect
    // (seen as java.net.SocketException: Permission denied: getsockopt).
    // Force the plain socket implementation there to avoid the affected path.
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    if (isWindows && System.getProperty("jdk.net.usePlainSocketImpl").isNullOrBlank()) {
        System.setProperty("jdk.net.usePlainSocketImpl", "true")
    }

    // socks proxy settings
    if (runtimeOnly) {
        applyProxySettings(
            proxyEnabled = serverConfig.socksProxyEnabled.value,
            proxyVersion = serverConfig.socksProxyVersion.value,
            proxyHost = serverConfig.socksProxyHost.value,
            proxyPort = serverConfig.socksProxyPort.value,
            proxyUsername = serverConfig.socksProxyUsername.value,
            proxyPassword = serverConfig.socksProxyPassword.value,
        )
        RuntimeStartupMetrics.logStage("runtime_only_proxy_seeded")
    } else {
        registerProxySettingsSubscription()
        RuntimeStartupMetrics.logStage("proxy_subscription_ready")

        // AES/CBC/PKCS7Padding Cypher provider for zh.copymanga
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        RuntimeStartupMetrics.logStage("crypto_provider_ready")
    }

    if (!runtimeOnly) {
        // start automated global updates
        val updater = Injekt.get<IUpdater>()
        (updater as Updater).scheduleUpdateTask()

        // start automated backups
        ProtoBackupExport.scheduleAutomatedBackupTask()

        // start DownloadManager and restore + resume downloads
        DownloadManager.restoreAndResumeDownloads()
    }

    if (runtimeOnly) {
        startDeferredRuntimeOnlyServices(applicationDirs, deferEmbeddedLoggerInit)
        RuntimeStartupMetrics.logStage("runtime_only_background_services_started")
    } else if (deferEmbeddedLoggerInit) {
        startDeferredLoggerInitialization(applicationDirs)
        RuntimeStartupMetrics.logStage("logger_init_background_started")
    }
}
