package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Bundle
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import eu.kanade.tachiyomi.util.lang.Hash
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.ApkParsers
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import org.w3c.dom.Element
import org.w3c.dom.Node
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.pm.InstalledPackage.Companion.toList
import xyz.nulldev.androidcompat.pm.toPackageInfo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.relativeTo

object PackageTools {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    const val EXTENSION_FEATURE = "tachiyomi.extension"
    const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    val SUPPORTED_LIB_VERSIONS = setOf(1.4, 1.6)
    internal const val CONVERTER_VERSION = "dex-register-constructors-v2"
    private const val ANIME_METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val ANIME_METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"

    /**
     * Convert dex to jar, a wrapper for the dex2jar library
     */
    fun dex2jar(
        dexFile: String,
        jarFile: String,
        fileNameWithoutType: String,
    ) {
        // adopted from com.googlecode.dex2jar.tools.Dex2jarCmd.doCommandLine
        // source at: https://github.com/DexPatcher/dex2jar/tree/v2.1-20190905-lanchon/dex-tools/src/main/java/com/googlecode/dex2jar/tools/Dex2jarCmd.java

        val sourceBytes = Files.readAllBytes(File(dexFile).toPath())
        val destination = File(jarFile).toPath()
        val cacheDir = Path(applicationDirs.extensionsRoot).resolve(".converted-jars")
        val cacheFile = cacheDir.resolve(convertedJarCacheKey(sourceBytes))
        val staged = destination.resolveSibling("${destination.fileName}.installing-${UUID.randomUUID()}")
        val converting = cacheFile.resolveSibling("${cacheFile.fileName}.converting-${UUID.randomUUID()}")

        clearJarLoader(jarFile)
        Files.createDirectories(cacheDir)
        try {
            if (Files.exists(cacheFile)) {
                runCatching { verifyConvertedJar(cacheFile) }
                    .onFailure {
                        logger.warn(it) { "Discarding invalid converted extension cache $cacheFile" }
                        Files.deleteIfExists(cacheFile)
                    }
            }

            if (!Files.exists(cacheFile)) {
                convertDexBytes(
                    sourceBytes,
                    converting,
                    Path(applicationDirs.extensionsRoot).resolve("$fileNameWithoutType-error.txt"),
                )
                moveAtomically(converting, cacheFile)
            }

            Files.copy(cacheFile, staged, StandardCopyOption.REPLACE_EXISTING)
            promoteVerifiedJar(staged, destination)
        } catch (error: ExtensionCompatibilityException) {
            throw error
        } catch (error: Throwable) {
            throw ExtensionCompatibilityException(error.message ?: error::class.java.simpleName, error)
        } finally {
            Files.deleteIfExists(staged)
            Files.deleteIfExists(converting)
            clearJarLoader(jarFile)
        }
    }

    internal fun convertDexBytes(
        sourceBytes: ByteArray,
        destination: Path,
        errorFile: Path,
    ) {
        val normalized = DexConstructorNormalizer.from(MultiDexFileReader.open(sourceBytes))
        val handler = BaksmaliBaseDexExceptionHandler()
        Dex2jar
            .from(normalized)
            .withExceptionHandler(handler)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .to(destination)
        if (handler.hasException()) {
            handler.dump(errorFile, emptyArray<String>())
            throw ExtensionCompatibilityException(
                "DEX conversion failed; details were written to ${errorFile.fileName}",
            )
        }

        BytecodeEditor.fixAndroidClasses(destination, normalized.forwardingConstructors)
        verifyConvertedJar(destination)
    }

    internal fun convertedJarCacheKey(
        apkBytes: ByteArray,
        converterVersion: String = CONVERTER_VERSION,
    ): String = "${Hash.sha256(apkBytes)}-$converterVersion.jar"

    internal fun verifyConvertedJar(jarFile: Path) {
        val classNames =
            ZipFile(jarFile.toFile()).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .sorted()
                    .toList()
            }
        if (classNames.isEmpty()) throw ExtensionCompatibilityException("converted JAR contains no classes")

        ChildFirstURLClassLoader(arrayOf(jarFile.toUri().toURL())).use { loader ->
            ZipFile(jarFile.toFile()).use { zip ->
                classNames.forEach { className ->
                    try {
                        val entry =
                            zip.getEntry(className.replace('.', '/') + ".class")
                                ?: throw ExtensionCompatibilityException("missing class entry for $className")
                        val diagnostics = StringWriter()
                        zip.getInputStream(entry).use { input ->
                            CheckClassAdapter.verify(ClassReader(input), loader, false, PrintWriter(diagnostics))
                        }
                        if (diagnostics.toString().isNotBlank()) {
                            val firstDiagnostic =
                                diagnostics
                                    .toString()
                                    .lineSequence()
                                    .first(String::isNotBlank)
                            throw ExtensionCompatibilityException(
                                "JVM verification failed for $className: $firstDiagnostic",
                            )
                        }
                        loader.loadOwnClassAndResolve(className)
                    } catch (error: ExtensionCompatibilityException) {
                        throw error
                    } catch (error: Throwable) {
                        throw ExtensionCompatibilityException(
                            "JVM verification failed for $className: ${error.message ?: error::class.java.simpleName}",
                            error,
                        )
                    }
                }
            }
        }
    }

    internal fun promoteVerifiedJar(
        staged: Path,
        destination: Path,
    ) {
        verifyConvertedJar(staged)
        moveAtomically(staged, destination)
    }

    internal fun moveAtomically(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** A modified version of `xyz.nulldev.androidcompat.pm.InstalledPackage.info` */
    fun getPackageInfo(apkFilePath: String): PackageInfo {
        val apk = File(apkFilePath)
        return ApkParsers.getMetaInfo(apk).toPackageInfo(apk).apply {
            val parsed = ApkFile(apk)
            val dbFactory = DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc =
                parsed.manifestXml.byteInputStream().use {
                    dBuilder.parse(it)
                }

            logger.trace { parsed.manifestXml }

            applicationInfo.metaData =
                Bundle().apply {
                    val appTag = doc.getElementsByTagName("application").item(0)

                    appTag
                        ?.childNodes
                        ?.toList()
                        .orEmpty()
                        .asSequence()
                        .filter {
                            it.nodeType == Node.ELEMENT_NODE
                        }.map {
                            it as Element
                        }.filter {
                            it.tagName == "meta-data"
                        }.forEach {
                            putString(
                                it.attributes.getNamedItem("android:name").nodeValue,
                                it.attributes.getNamedItem("android:value").nodeValue,
                            )
                        }
                }

            signatures =
                (
                    parsed.apkSingers.flatMap { it.certificateMetas }
                    // + parsed.apkV2Singers.flatMap { it.certificateMetas }
                ) // Blocked by: https://github.com/hsiafan/apk-parser/issues/72
                    .map { Signature(it.data) }
                    .toTypedArray()
        }
    }

    /**
     * Return the extension API version declared by the package.
     *
     * Current packages declare this independently from their release version. Older packages only
     * encoded it in the first two components of versionName, so retain that as a compatibility
     * fallback.
     */
    internal fun extensionLibVersion(packageInfo: PackageInfo): Double? =
        packageInfo.applicationInfo.metaData
            ?.get(METADATA_EXTENSION_LIB)
            ?.toString()
            ?.toDoubleOrNull()
            ?: packageInfo.versionName
                ?.substringBeforeLast('.')
                ?.toDoubleOrNull()

    internal fun requireSupportedExtensionLibVersion(packageInfo: PackageInfo): Double {
        val libVersion = extensionLibVersion(packageInfo)
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            throw ExtensionCompatibilityException(
                "extension API version is ${libVersion ?: "missing"}; supported versions are " +
                    SUPPORTED_LIB_VERSIONS.sorted().joinToString(),
            )
        }
        return libVersion
    }

    fun deriveExtensionClassName(
        apkPath: String,
        pkgName: String,
        isAnime: Boolean,
    ): String? {
        val apkFile = File(apkPath)
        if (!apkFile.isFile) {
            return null
        }

        val packageInfo =
            runCatching { getPackageInfo(apkFile.absolutePath) }
                .onFailure { error ->
                    logger.warn(error) { "Failed to derive class name from ${apkFile.name}" }
                }.getOrNull() ?: return null
        val metaData = packageInfo.applicationInfo.metaData ?: return null
        val classNameSuffix =
            if (isAnime) {
                metaData.getString(ANIME_METADATA_SOURCE_CLASS)
                    ?: metaData.getString(ANIME_METADATA_SOURCE_FACTORY)
                    ?: metaData.getString(METADATA_SOURCE_CLASS)
                    ?: metaData.getString(METADATA_SOURCE_FACTORY)
            } else {
                metaData.getString(METADATA_SOURCE_CLASS)
                    ?: metaData.getString(METADATA_SOURCE_FACTORY)
            }?.trim().orEmpty()
        if (classNameSuffix.isBlank()) {
            return null
        }

        return normalizeExtensionClassName(pkgName, classNameSuffix)
    }

    fun normalizeExtensionClassName(
        pkgName: String,
        className: String,
    ): String {
        val normalizedPkgName = pkgName.trim()
        val normalizedClassName = className.trim()
        if (normalizedPkgName.isBlank() || normalizedClassName.isBlank()) {
            return normalizedClassName
        }

        val duplicatePrefix = normalizedPkgName + normalizedPkgName
        return when {
            normalizedClassName.startsWith(duplicatePrefix) -> normalizedClassName.removePrefix(normalizedPkgName)
            normalizedClassName.startsWith(normalizedPkgName) -> normalizedClassName
            else -> normalizedPkgName + normalizedClassName
        }
    }

    fun getSignatureHash(pkgInfo: PackageInfo): String? {
        val signatures = pkgInfo.signatures
        return if (signatures != null && signatures.isNotEmpty()) {
            Hash.sha256(signatures.first().toByteArray())
        } else {
            null
        }
    }

    val jarLoaderMap = ConcurrentHashMap<String, URLClassLoader>()

    fun clearJarLoader(jarPath: String) {
        val loader = jarLoaderMap.remove(jarPath)
        try {
            loader?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * loads the extension main class called [className] from the jar located at [jarPath]
     * It may return an instance of HttpSource or SourceFactory depending on the extension.
     */
    fun loadExtensionSources(
        jarPath: String,
        className: String,
    ): Any {
        try {
            logger.debug { "loading jar with path: $jarPath" }
            val classLoader =
                jarLoaderMap.computeIfAbsent(jarPath) {
                    ChildFirstURLClassLoader(arrayOf<URL>(Path(jarPath).toUri().toURL()))
                }
            val classToLoad = Class.forName(className, false, classLoader)

            return classToLoad.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            logger.error(e) { "Failed to load jar with path: $jarPath" }
            throw e
        }
    }
}
