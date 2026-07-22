package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.HttpException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.websocket.WsContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.future.future
import org.eclipse.jetty.server.Connector
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.thread.QueuedThreadPool
import suwayomi.tachidesk.manga.impl.util.ExtensionCompatibilityException
import suwayomi.tachidesk.server.plugin.ServerPluginRegistry
import suwayomi.tachidesk.server.types.AuthMode
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.getUserFromContext
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.util.ServerSubpath
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appLifecycleLock = Any()

    @Volatile
    private var appInstance: Javalin? = null

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = scope.future(block = block)

    fun restartServer(reason: String) {
        synchronized(appLifecycleLock) {
            logger.warn { "Restarting runtime server (reason=$reason)" }
            runCatching {
                appInstance?.stop()
            }.onFailure {
                logger.warn(it) { "Failed to stop previous runtime server before restart" }
            }
            appInstance = null
        }

        runCatching {
            javalinSetup()
            logger.info { "Runtime server restart completed" }
        }.onFailure {
            logger.error(it) { "Runtime server restart failed" }
        }
    }

    fun javalinSetup() {
        synchronized(appLifecycleLock) {
            if (appInstance != null) {
                logger.debug { "Runtime server is already initialized; skipping setup" }
                return
            }

        val app =
            Javalin.create { config ->
                config.jetty.threadPool = QueuedThreadPool(100, 8, 60_000).apply { name = "JettyServerThreadPool" }

                var connectorAdded = false
                config.jetty.modifyServer { server ->
                    if (!connectorAdded) {
                        val connector =
                            ServerConnector(server).apply {
                                host = serverConfig.ip.value
                                port = serverConfig.port.value
                            }
                        server.addConnector(connector)

                        serverConfig.subscribeTo(
                            combine(
                                serverConfig.ip,
                                serverConfig.port,
                            ) { ip, port -> Pair(ip, port) },
                            { (newIp, newPort) ->
                                val oldIp = connector.host
                                val oldPort = connector.port

                                connector.host = newIp
                                connector.port = newPort
                                connector.stop()
                                connector.start()

                                logger.info { "Server ip and/or port changed from $oldIp:$oldPort to $newIp:$newPort " }
                            },
                        )
                        connectorAdded = true
                    }
                }

                config.bundledPlugins.enableCors { cors ->
                    cors.addRule {
                        it.allowCredentials = true
                        it.reflectClientOrigin = true
                    }
                }

                config.router.apiBuilder {
                    path(ServerSubpath.maybeAddAsPrefix("runtime/")) {
                        path("v1/") {
                            ServerPluginRegistry.defineRuntimeV1Routes()
                        }

                        after { ctx ->
                            if (ctx.endpointHandlerPath() == "*") {
                                throw NotFoundResponse()
                            }
                        }
                    }
                }
            }

        app.beforeMatched { ctx ->
            val isPreFlight = ctx.method() == HandlerType.OPTIONS

            val requiresAuthentication = !isPreFlight
            if (!requiresAuthentication) {
                return@beforeMatched
            }

            val authMode = serverConfig.authMode.value

            fun credentialsValid(): Boolean {
                val basicAuthCredentials = ctx.basicAuthCredentials() ?: return false
                val (username, password) = basicAuthCredentials
                return username == serverConfig.authUsername.value &&
                    password == serverConfig.authPassword.value
            }

            fun cookieValid(): Boolean {
                val username = ctx.sessionAttribute<String>("logged-in") ?: return false
                return username == serverConfig.authUsername.value
            }

            if (authMode == AuthMode.BASIC_AUTH && !credentialsValid()) {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedResponse()
            }

            ctx.setAttribute(Attribute.TachideskUser, getUserFromContext(ctx))
            ctx.setAttribute(Attribute.TachideskBasic, credentialsValid())
        }

        app.wsBefore {
            it.onConnect { ctx ->
                ctx.setAttribute(Attribute.TachideskUser, getUserFromWsContext(ctx))
            }
        }

        // when JVM is prompted to shutdown, stop javalin gracefully
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                app.stop()
            },
        )

        app.exception(NullPointerException::class.java) { e, ctx ->
            logger.error(e) { "NullPointerException while handling the request" }
            ctx.status(404)
        }
        app.exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error(e) { "NoSuchElementException while handling the request" }
            ctx.status(404)
        }
        app.exception(HttpException::class.java) { e, ctx ->
            logger.warn(e) { "HttpException while handling the request" }
            ctx.status(e.code)
            ctx.result(e.message ?: "HTTP error ${e.code}")
        }
        app.exception(IOException::class.java) { e, ctx ->
            logger.error(e) { "IOException while handling the request" }
            ctx.status(500)
            ctx.result(e.message ?: "Internal Server Error")
        }

        app.exception(ExtensionCompatibilityException::class.java) { e, ctx ->
            logger.warn(e) { "Incompatible extension package" }
            ctx.status(HttpStatus.BAD_REQUEST)
            ctx.result(e.message ?: "Extension package is incompatible")
        }

        app.exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error(e) { "IllegalArgumentException while handling the request" }
            ctx.status(400)
            ctx.result(e.message ?: "Bad Request")
        }

        app.exception(UnauthorizedException::class.java) { e, ctx ->
            logger.error(e) { "UnauthorizedException while handling the request" }
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.result(e.message ?: "Unauthorized")
        }

        app.exception(ForbiddenException::class.java) { e, ctx ->
            logger.error(e) { "ForbiddenException while handling the request" }
            ctx.status(HttpStatus.FORBIDDEN)
            ctx.result(e.message ?: "Forbidden")
        }

        app.start()
        appInstance = app
        }
    }

    private fun getConnector(): Connector? = appInstance?.jettyServer()?.server()?.connectors?.firstOrNull()

    fun javalinStartSocket() {
        synchronized(appLifecycleLock) {
            val connector = getConnector() ?: return
            if (!connector.isStarted) {
                connector.start()
            }
        }
    }

    fun javalinStopSocket() {
        synchronized(appLifecycleLock) {
            val connector = getConnector() ?: return
            if (connector.isStarted) {
                connector.stop()
            }
        }
    }

    // private fun getOpenApiOptions(): OpenApiOptions {
    //     val applicationInfo =
    //         Info().apply {
    //             version("1.0")
    //             description("Suwayomi-Server Api")
    //         }
    //     return OpenApiOptions(applicationInfo).apply {
    //         path("/api/openapi.json")
    //         swagger(
    //             SwaggerOptions("/api/swagger-ui").apply {
    //                 title("Suwayomi-Server Swagger Documentation")
    //             },
    //         )
    //     }
    // }

    sealed class Attribute<T : Any>(
        val name: String,
    ) {
        data object TachideskUser : Attribute<UserType>("user")

        data object TachideskBasic : Attribute<Boolean>("basicAuthValid")
    }

    private fun <T : Any> Context.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    private fun <T : Any> WsContext.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    fun <T : Any> Context.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttributeOrSet(
        attribute: Attribute<T>,
        replaceIf: (T) -> Boolean = { false },
        set: () -> T,
    ): T {
        var item: T? = attribute(attribute.name)

        if (item != null && replaceIf(item)) {
            item = null
        }

        return item ?: set().also { setAttribute(attribute, it) }
    }
}
