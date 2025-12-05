package suwayomi.tachidesk.server.ocr

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OcrServerProcess(
    private val workingDir: File,
    private val port: Int
) {
    private val logger = LoggerFactory.getLogger("OcrServer")
    private var process: Process? = null

    fun start() {
        // 1. Detect OS
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        
        val binaryName = when {
            osName.contains("win") -> "ocr-server-win.exe"
            osName.contains("mac") -> {
                if (osArch.contains("aarch64") || osArch.contains("arm")) "ocr-server-macos-arm64"
                else "ocr-server-macos"
            }
            else -> "ocr-server-linux"
        }

        // 2. Prepare Extraction
        if (!workingDir.exists()) workingDir.mkdirs()
        val executable = File(workingDir, binaryName)

        // 3. Extract from JAR Resources
        // The path must match where the CI puts it: src/main/resources/ocr-binaries/
        val resourcePath = "/ocr-binaries/$binaryName"
        val resourceStream = this.javaClass.getResourceAsStream(resourcePath)

        if (resourceStream == null) {
            logger.error("OCR binary not found in JAR at: $resourcePath")
            return
        }

        try {
            // Overwrite existing to ensure updates apply
            Files.copy(resourceStream, executable.toPath(), StandardCopyOption.REPLACE_EXISTING)
            executable.setExecutable(true)
        } catch (e: Exception) {
            logger.error("Failed to extract OCR binary", e)
            return
        }

        // 4. Launch
        try {
            val command = listOf(executable.absolutePath, "--port", port.toString())
            val builder = ProcessBuilder(command)
            builder.directory(workingDir)
            builder.redirectErrorStream(true) 

            process = builder.start()
            logger.info("OCR Server started on port $port")

            // Log Piping
            thread(start = true, name = "OCR-Log") {
                process?.inputStream?.bufferedReader()?.use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logger.info("[Deno] $line")
                    }
                }
            }

            // Shutdown Hook
            Runtime.getRuntime().addShutdownHook(Thread { stop() })

        } catch (e: Exception) {
            logger.error("Failed to launch OCR Server", e)
        }
    }

    fun stop() {
        process?.let { proc ->
            if (proc.isAlive) {
                logger.info("Stopping OCR Server...")
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
    }
}