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
        // Use a persistent directory so the binary isn't re-extracted unnecessarily if you add logic for that
        // System.getProperty("user.dir") usually points to where Suwayomi is running
        val ocrDir = File(System.getProperty("user.dir"), "ocr-server")
        val ocrPort = 3000 // Or read from config if possible
        
        OcrServerProcess(ocrDir, ocrPort).start()
    }
    // --------------------------------

    javalinSetup()
}
