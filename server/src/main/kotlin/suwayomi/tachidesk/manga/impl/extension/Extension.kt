package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.bean.Icon
import okhttp3.CacheControl
import okio.buffer
import okio.sink
import okio.source
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList.extensionTableAsDataClass
import suwayomi.tachidesk.manga.impl.extension.github.ExtensionGithubApi
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.PackageTools.EXTENSION_FEATURE
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NSFW
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_SOURCE_CLASS
import suwayomi.tachidesk.manga.impl.util.PackageTools.dex2jar
import suwayomi.tachidesk.manga.impl.util.PackageTools.getPackageInfo
import suwayomi.tachidesk.manga.impl.util.PackageTools.loadExtensionSources
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.clearCachedImage
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.getImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.saveImage
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.RuntimeMode
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo

object Extension {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    suspend fun installExtension(pkgName: String): Int {
        logger.debug { "Installing $pkgName" }
        val extensionRecord = extensionTableAsDataClass().firstOrNull { it.pkgName == pkgName }
        val repo = extensionRecord?.repo

        if (extensionRecord == null || repo == null) {
            val localApk = findLocalExtensionApk(pkgName)
            if (localApk != null) {
                logger.warn { "Installing $pkgName from local apk ${localApk.name}" }
                return installAPK {
                    localApk.absolutePath
                }
            }
        }

        if (extensionRecord == null) {
            throw NoSuchElementException("Extension not found: $pkgName")
        }

        if (repo == null) {
            throw NullPointerException("Could not find extension repo for $pkgName")
        }

        return installAPK {
            val apkURL =
                ExtensionGithubApi.getApkUrl(
                    repo,
                    extensionRecord.apkName,
                )
            val apkName = Uri.parse(apkURL).lastPathSegment!!
            val apkSavePath = "${applicationDirs.extensionsRoot}/$apkName"
            // download apk file
            downloadAPKFile(apkURL, apkSavePath)

            apkSavePath
        }
    }

    suspend fun installExternalExtension(
        inputStream: InputStream,
        apkName: String,
    ): Int =
        installAPK(true) {
            val rootPath = Path(applicationDirs.extensionsRoot)
            val downloadedFile = rootPath.resolve(apkName).normalize()
            check(downloadedFile.startsWith(rootPath) && downloadedFile.parent == rootPath) {
                "File '$apkName' is not a valid extension file"
            }
            logger.info { "Saving extension apk at ${downloadedFile.toAbsolutePath()}" }
            // download apk file
            downloadedFile.outputStream().sink().buffer().use { sink ->
                inputStream.source().use { source ->
                    sink.writeAll(source)
                    sink.flush()
                }
            }
            logger.info {
                "Saved extension apk: ${downloadedFile.toAbsolutePath()} size=${downloadedFile.toFile().length()}"
            }
            downloadedFile.absolutePathString()
        }

    suspend fun installAPK(
        forceReinstall: Boolean = false,
        fetcher: suspend () -> String,
    ): Int {
        val apkFilePath = fetcher()
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            logger.error { "Extension apk missing at $apkFilePath" }
            throw java.io.FileNotFoundException("Extension apk missing at $apkFilePath")
        }
        val apkName = apkFile.name
        logger.info { "Installing extension from apk=$apkName size=${apkFile.length()}" }

        // check if we don't have the extension already installed
        // if it's installed and we want to update, it first has to be uninstalled
        val isInstalled =
            transaction {
                ExtensionTable.selectAll().where { ExtensionTable.apkName eq apkName }.firstOrNull()
            }?.get(ExtensionTable.isInstalled) ?: false

        val fileNameWithoutType = apkName.substringBefore(".apk")

        val dirPathWithoutType = "${applicationDirs.extensionsRoot}/$fileNameWithoutType"
        val jarFilePath = "$dirPathWithoutType.jar"
        val stagedJarFilePath = "$dirPathWithoutType.installing-${UUID.randomUUID()}.jar"

        val packageInfo = getPackageInfo(apkFilePath)
        val pkgName = packageInfo.packageName
        if (!isInstalled || forceReinstall) {
            if (!packageInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }) {
                throw Exception("This apk is not a Tachiyomi extension")
            }

            PackageTools.requireSupportedExtensionLibVersion(packageInfo)

            // TODO: allow trusting keys
//            val signatureHash = getSignatureHash(packageInfo)

//            if (signatureHash == null) {
//                throw Exception("Package $pkgName isn't signed")
//            } else if (signatureHash !in trustedSignatures) {
//                throw Exception("This apk is not a signed with the official tachiyomi signature")
//            }

            val isNsfw = packageInfo.applicationInfo.metaData.getString(METADATA_NSFW) == "1"

            val className =
                packageInfo.packageName + packageInfo.applicationInfo.metaData.getString(METADATA_SOURCE_CLASS)

            logger.debug { "Main class for extension is $className" }

            try {
                dex2jar(apkFilePath, stagedJarFilePath, fileNameWithoutType)
                extractAssetsFromApk(apkFilePath, stagedJarFilePath)
                PackageTools.verifyConvertedJar(Path(stagedJarFilePath))

                // Instantiate the staged main class before changing an existing installation.
                // A verifier/linkage/constructor failure therefore leaves the old JAR and DB rows intact.
                loadExtensionSources(stagedJarFilePath, className)
                PackageTools.clearJarLoader(stagedJarFilePath)

                if (isInstalled && forceReinstall) {
                    uninstallExtension(pkgName, apkName)
                }
                PackageTools.moveAtomically(Path(stagedJarFilePath), Path(jarFilePath))
            } catch (error: Throwable) {
                PackageTools.clearJarLoader(stagedJarFilePath)
                Files.deleteIfExists(Path(stagedJarFilePath))
                throw error
            }
            extractAndCacheApkIcon(apkFilePath, apkName)

            // clean up
            if (!RuntimeMode.isRuntimeOnly()) {
                File(apkFilePath).delete()
            }

            try {
                // collect sources from the extension
                val extensionMainClassInstance = loadExtensionSources(jarFilePath, className)
                val sources: List<CatalogueSource> =
                    when (extensionMainClassInstance) {
                        is Source -> listOf(extensionMainClassInstance)
                        is SourceFactory -> extensionMainClassInstance.createSources()
                        else -> throw RuntimeException("Unknown source class type! ${extensionMainClassInstance.javaClass}")
                    }.map { it as CatalogueSource }

                if (sources.isEmpty()) {
                    logger.warn { "No sources returned for $pkgName class=$className" }
                }

                val langs = sources.map { it.lang }.toSet()
                val extensionLang =
                    when (langs.size) {
                        0 -> ""
                        1 -> langs.first()
                        else -> "all"
                    }

                val extensionName =
                    packageInfo.applicationInfo.nonLocalizedLabel
                        .toString()
                        .substringAfter("Tachiyomi: ")

                // update extension info
                transaction {
                    if (ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.firstOrNull() == null) {
                        ExtensionTable.insert {
                            it[this.apkName] = apkName
                            it[name] = extensionName
                            it[this.pkgName] = packageInfo.packageName
                            it[versionName] = packageInfo.versionName
                            it[versionCode] = packageInfo.versionCode
                            it[lang] = extensionLang
                            it[this.isNsfw] = isNsfw
                        }
                    }

                    ExtensionTable.update({ ExtensionTable.pkgName eq pkgName }) {
                        it[this.apkName] = apkName
                        it[this.isInstalled] = true
                        it[this.classFQName] = className
                        it[versionName] = packageInfo.versionName
                        it[versionCode] = packageInfo.versionCode
                    }

                    val extensionId =
                        ExtensionTable
                            .selectAll()
                            .where { ExtensionTable.pkgName eq pkgName }
                            .first()[ExtensionTable.id]
                            .value

                    sources.forEach { httpSource ->
                        SourceTable.insert {
                            it[id] = httpSource.id
                            it[name] = httpSource.name
                            it[lang] = httpSource.lang
                            it[extension] = extensionId
                            it[SourceTable.isNsfw] = isNsfw
                        }
                        logger.debug { "Installed source ${httpSource.name} (${httpSource.lang}) with id:${httpSource.id}" }
                    }
                }
                return 201 // we installed successfully
            } catch (e: Throwable) {
                // free up the file descriptor if exists
                PackageTools.jarLoaderMap.remove(jarFilePath)?.close()
                File(jarFilePath).delete()

                uninstallExtension(pkgName, if (RuntimeMode.isRuntimeOnly()) apkName else null)
                throw e
            }
        } else {
            return 302 // extension was already installed
        }
    }

    private fun extractAndCacheApkIcon(
        apkFilePath: String,
        apkName: String,
    ) {
        val iconCacheDir = "${applicationDirs.extensionsRoot}/icon"
        try {
            val iconData =
                ApkFile(File(apkFilePath)).use { apk ->
                    apk.allIcons
                        .filterIsInstance<Icon>()
                        .mapNotNull { it.data?.let { data -> data to it.density } }
                        .maxByOrNull { (_, density) -> density }
                        ?.first
                }
            if (iconData == null) {
                logger.warn { "No icon found in APK $apkName" }
                return
            }

            File(iconCacheDir).mkdirs()
            clearCachedImage(iconCacheDir, apkName)
            saveImage("$iconCacheDir/$apkName", iconData.inputStream(), null)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract icon from APK $apkName" }
        }
    }

    private fun extractAssetsFromApk(
        apkPath: String,
        jarPath: String,
    ) {
        val apkFile = File(apkPath)
        val jarFile = File(jarPath)

        val assetsFolder = File("${apkFile.parent}/${apkFile.nameWithoutExtension}_assets")
        assetsFolder.mkdir()
        ZipInputStream(apkFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name.startsWith("assets/") && !zipEntry.isDirectory) {
                    val assetFile = File(assetsFolder, zipEntry.name)
                    assetFile.parentFile.mkdirs()
                    FileOutputStream(assetFile).use { outputStream ->
                        zipInputStream.copyTo(outputStream)
                    }
                }
                zipEntry = zipInputStream.nextEntry
            }
        }

        val tempJarFile = File("${jarFile.parent}/${jarFile.nameWithoutExtension}_temp.jar")
        ZipInputStream(jarFile.inputStream()).use { jarZipInputStream ->
            ZipOutputStream(FileOutputStream(tempJarFile)).use { jarZipOutputStream ->
                var zipEntry = jarZipInputStream.nextEntry
                while (zipEntry != null) {
                    if (!zipEntry.name.startsWith("META-INF/")) {
                        jarZipOutputStream.putNextEntry(ZipEntry(zipEntry.name))
                        jarZipInputStream.copyTo(jarZipOutputStream)
                    }
                    zipEntry = jarZipInputStream.nextEntry
                }
                assetsFolder.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        jarZipOutputStream.putNextEntry(ZipEntry(file.relativeTo(assetsFolder).toString().replace("\\", "/")))
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(jarZipOutputStream)
                        }
                        jarZipOutputStream.closeEntry()
                    }
                }
            }
        }

        jarFile.delete()
        tempJarFile.renameTo(jarFile)

        assetsFolder.deleteRecursively()
    }

    private val network: NetworkHelper by injectLazy()

    private suspend fun downloadAPKFile(
        url: String,
        savePath: String,
    ) {
        val response =
            network.client
                .newCall(
                    GET(url, cache = CacheControl.FORCE_NETWORK),
                ).await()

        val downloadedFile = File(savePath)
        downloadedFile.sink().buffer().use { sink ->
            response.body.source().use { source ->
                sink.writeAll(source)
                sink.flush()
            }
        }
    }

    fun uninstallExtension(
        pkgName: String,
        keepApkName: String? = null,
    ) {
        logger.debug { "Uninstalling $pkgName" }

        val extensionRecord =
            transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.firstOrNull() }
        if (extensionRecord == null) {
            logger.warn { "Extension not found for uninstall: $pkgName" }
            return
        }
        val installedApkName = extensionRecord[ExtensionTable.apkName]
        val fileNameWithoutType = installedApkName.substringBefore(".apk")
        val jarPath = "${applicationDirs.extensionsRoot}/$fileNameWithoutType.jar"
        val apkPath = "${applicationDirs.extensionsRoot}/$installedApkName"
        val sources =
            transaction {
                val extensionId = extensionRecord[ExtensionTable.id].value

                val sources = SourceTable.selectAll().where { SourceTable.extension eq extensionId }.map { it[SourceTable.id].value }

                SourceTable.deleteWhere { SourceTable.extension eq extensionId }

                if (extensionRecord[ExtensionTable.isObsolete]) {
                    ExtensionTable.deleteWhere { ExtensionTable.pkgName eq pkgName }
                } else {
                    ExtensionTable.update({ ExtensionTable.pkgName eq pkgName }) {
                        it[isInstalled] = false
                    }
                }

                sources
            }

        if (File(jarPath).exists()) {
            // free up the file descriptor if exists
            PackageTools.jarLoaderMap.remove(jarPath)?.close()

            // clear all loaded sources
            sources.forEach { GetCatalogueSource.unregisterCatalogueSource(it) }

            File(jarPath).delete()
        }

        val shouldDeleteApk = keepApkName == null || keepApkName != installedApkName
        if (shouldDeleteApk && File(apkPath).exists()) {
            File(apkPath).delete()
        }
    }

    suspend fun updateExtension(pkgName: String): Int {
        val targetExtension = ExtensionsList.updateMap.remove(pkgName)!!
        uninstallExtension(pkgName)
        transaction {
            ExtensionTable.update({ ExtensionTable.pkgName eq pkgName }) {
                it[name] = targetExtension.name
                it[versionName] = targetExtension.versionName
                it[versionCode] = targetExtension.versionCode
                it[lang] = targetExtension.lang
                it[isNsfw] = targetExtension.isNsfw
                it[apkName] = targetExtension.apkName
                it[iconUrl] = targetExtension.iconUrl
                it[hasUpdate] = false
            }
        }
        return installExtension(pkgName)
    }

    suspend fun getExtensionIcon(apkName: String): Pair<InputStream, String>? {
        val iconUrl =
            if (apkName == "localSource") {
                ""
            } else {
                transaction { ExtensionTable.selectAll().where { ExtensionTable.apkName eq apkName }.firstOrNull() }
                    ?.get(ExtensionTable.iconUrl)
                    .orEmpty()
            }

        val cacheSaveDir = "${applicationDirs.extensionsRoot}/icon"
        val hasValidIconUrl = iconUrl.hasHttpScheme()

        if (!hasValidIconUrl) {
            logger.warn { "Invalid icon URL for extension apk=$apkName iconUrl='$iconUrl'" }
            return null
        }

        return runCatching {
            getImageResponse(cacheSaveDir, apkName) {
                network.client
                    .newCall(
                        GET(iconUrl, cache = CacheControl.FORCE_NETWORK),
                    ).await()
            }
        }.getOrElse { error ->
            logger.warn(error) { "Failed to fetch icon for extension apk=$apkName iconUrl='$iconUrl'" }
            null
        }
    }

    private fun String.hasHttpScheme(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun findLocalExtensionApk(pkgName: String): File? {
        val root = File(applicationDirs.extensionsRoot)
        val apks = root.listFiles { file -> file.isFile && file.extension == "apk" }.orEmpty()
        if (apks.isEmpty()) {
            return null
        }
        return apks.firstOrNull { apk ->
            runCatching {
                PackageTools.getPackageInfo(apk.absolutePath).packageName == pkgName
            }.getOrElse {
                logger.warn(it) { "Failed reading extension metadata for ${apk.name}" }
                false
            }
        }
    }
}
