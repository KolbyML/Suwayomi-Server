package suwayomi.tachidesk

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.server.JavalinSetup.javalinSetup
import suwayomi.tachidesk.server.applicationSetup
import suwayomi.tachidesk.server.ocr.OcrServerProcess
import java.io.File
import kotlin.concurrent.thread

fun main() {
    applicationSetup()

    // --- Start OCR Server Sidecar ---
    thread(start = true, name = "OCR-Startup") {
        try {
            // FIX: Use user.home instead of user.dir to ensure we have write permissions.
            // This creates a folder ~/.suwayomi-ocr/ to store the binary.
            val homeDir = System.getProperty("user.home")
            val ocrDir = File(homeDir, ".suwayomi-ocr")
            
            val ocrPort = 3000 
            
            OcrServerProcess(ocrDir, ocrPort).start()
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger("Main").error("Failed to init OCR", e)
        }
    }
    // --------------------------------

    javalinSetup()
}
