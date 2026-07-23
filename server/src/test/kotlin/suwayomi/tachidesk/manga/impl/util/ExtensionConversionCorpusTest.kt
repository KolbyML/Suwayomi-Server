package suwayomi.tachidesk.manga.impl.util

import android.app.Application
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real APK corpus test. CI/release validation supplies a path-separated corpus through
 * `-Dmanatan.extension.corpus=/path/a.apk:/path/b.apk`. Every APK is converted and every emitted
 * class is linked with strict JVM verification by [PackageTools.convertDexBytes].
 */
class ExtensionConversionCorpusTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `converts and strictly loads every class in real extension corpus`() {
        val corpus = corpusPaths()
        assumeTrue(corpus.isNotEmpty(), "set -Dmanatan.extension.corpus to run the real APK corpus")

        corpus.forEachIndexed { index, apk ->
            PackageTools.requireSupportedExtensionLibVersion(PackageTools.getPackageInfo(apk.toString()))
            val output = tempDir.resolve("corpus-$index.jar")
            PackageTools.convertDexBytes(
                Files.readAllBytes(apk),
                output,
                tempDir.resolve("corpus-$index-errors.txt"),
            )
            assertTrue(Files.size(output) > 0, "empty converted JAR for $apk")
            loadExtensionEntryPoint(apk, output)
        }
    }

    @Test
    fun `reproduces Pawchive current API and verifies converted output`() {
        val apk =
            (System.getProperty("manatan.pawchive.apk") ?: System.getenv("MANATAN_PAWCHIVE_APK"))
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
        assumeTrue(apk != null && Files.isRegularFile(apk), "set -Dmanatan.pawchive.apk to the Pawchive APK")
        val apkPath = requireNotNull(apk)

        val packageInfo = PackageTools.getPackageInfo(apkPath.toString())
        assertTrue(PackageTools.requireSupportedExtensionLibVersion(packageInfo) == 1.6)

        val output = tempDir.resolve("pawchive.jar")
        PackageTools.convertDexBytes(
            Files.readAllBytes(apkPath),
            output,
            tempDir.resolve("pawchive-errors.txt"),
        )
        PackageTools.verifyConvertedJar(output)
        loadExtensionEntryPoint(apkPath, output)
    }

    @Test
    fun `reproduces Rawkuma optimized DEX and verifies converted output`() {
        val apk =
            (System.getProperty("manatan.rawkuma.apk") ?: System.getenv("MANATAN_RAWKUMA_APK"))
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
        assumeTrue(apk != null && Files.isRegularFile(apk), "set -Dmanatan.rawkuma.apk to the Rawkuma APK")

        val bytes = Files.readAllBytes(apk!!)
        val normalized = DexConstructorNormalizer.from(MultiDexFileReader.open(bytes))
        assertTrue(
            normalized.forwardingConstructors.any { it.allocatedType != it.invokedOwner },
            "Rawkuma corpus APK no longer contains the optimized constructor shape",
        )

        val unnormalized = tempDir.resolve("rawkuma-unnormalized.jar")
        Dex2jar
            .from(MultiDexFileReader.open(bytes))
            .withExceptionHandler(BaksmaliBaseDexExceptionHandler())
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .to(unnormalized)
        BytecodeEditor.fixAndroidClasses(unnormalized)
        val originalFailure =
            assertThrows(ExtensionCompatibilityException::class.java) {
                PackageTools.verifyConvertedJar(unnormalized)
            }
        assertTrue(
            originalFailure.message.orEmpty().contains("JVM verification failed"),
            "Rawkuma did not reproduce the unnormalized dex2jar verification failure",
        )

        val output = tempDir.resolve("rawkuma.jar")
        PackageTools.convertDexBytes(bytes, output, tempDir.resolve("rawkuma-errors.txt"))
        PackageTools.verifyConvertedJar(output)

        loadExtensionEntryPoint(apk, output) { sources ->
            sources.forEach { source ->
                assertTrue(
                    source.getFilterList().isNotEmpty(),
                    "Rawkuma did not construct its search filters",
                )
            }
        }
    }

    @Test
    fun `reproduces MangaFire optimized filter subclasses and constructs filters`() {
        val apk =
            (System.getProperty("manatan.mangafire.apk") ?: System.getenv("MANATAN_MANGAFIRE_APK"))
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
        assumeTrue(apk != null && Files.isRegularFile(apk), "set -Dmanatan.mangafire.apk to the MangaFire APK")
        val apkPath = requireNotNull(apk)

        val output = tempDir.resolve("mangafire.jar")
        PackageTools.convertDexBytes(
            Files.readAllBytes(apkPath),
            output,
            tempDir.resolve("mangafire-errors.txt"),
        )
        PackageTools.verifyConvertedJar(output)
        loadExtensionEntryPoint(apkPath, output) { sources ->
            sources.forEach { source ->
                assertTrue(
                    source.getFilterList().isNotEmpty(),
                    "MangaFire did not construct its search filters",
                )
            }
        }
    }

    private fun withExtensionDependencies(block: () -> Unit) {
        val ownsKoin = GlobalContext.getOrNull() == null
        if (ownsKoin) {
            startKoin {
                modules(
                    module {
                        single<Application> { mockk(relaxed = true) }
                        single<NetworkHelper> {
                            mockk<NetworkHelper>().also { network ->
                                every { network.defaultUserAgentProvider() } returns "Manatan test"
                                every { network.client } returns
                                    OkHttpClient
                                        .Builder()
                                        .addInterceptor { chain ->
                                            Response
                                                .Builder()
                                                .request(chain.request())
                                                .protocol(Protocol.HTTP_1_1)
                                                .code(503)
                                                .message("Offline corpus test")
                                                .body("".toResponseBody())
                                                .build()
                                        }
                                        .addInterceptor(UncaughtExceptionInterceptor())
                                        .addInterceptor(UserAgentInterceptor { "Manatan test" })
                                        .addInterceptor(CloudflareInterceptor({}, {}))
                                        .build()
                            }
                        }
                    },
                )
            }
        }
        try {
            block()
        } finally {
            if (ownsKoin) stopKoin()
        }
    }

    private fun loadExtensionEntryPoint(
        apk: Path,
        jar: Path,
        validate: (List<Source>) -> Unit = {},
    ) {
        val packageInfo = PackageTools.getPackageInfo(apk.toString())
        val className =
            PackageTools.deriveExtensionClassName(
                apkPath = apk.toString(),
                pkgName = packageInfo.packageName,
                isAnime = false,
            ) ?: error("missing extension entry point metadata for $apk")
        ChildFirstURLClassLoader(arrayOf(jar.toUri().toURL())).use { loader ->
            val entryClass = loader.loadOwnClassAndResolve(className)
            withExtensionDependencies {
                val instance = entryClass.getDeclaredConstructor().newInstance()
                val sources =
                    when (instance) {
                        is Source -> listOf(instance)
                        is SourceFactory -> instance.createSources()
                        else -> error("unsupported extension entry point ${instance.javaClass.name} for $apk")
                    }
                assertTrue(sources.isNotEmpty(), "extension created no sources for $apk")
                validate(sources)
            }
        }
    }

    private fun corpusPaths(): List<Path> =
        (System.getProperty("manatan.extension.corpus") ?: System.getenv("MANATAN_EXTENSION_CORPUS"))
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(Path::of)
            .filter(Files::isRegularFile)
}
