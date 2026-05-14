package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

object RuntimeMode {
    fun isRuntimeOnly(): Boolean {
        val sys = System.getProperty("suwayomi.runtimeOnly")?.lowercase()
        if (!sys.isNullOrBlank()) {
            return sys == "1" || sys == "true" || sys == "yes"
        }

        val env = System.getenv("SUWAYOMI_RUNTIME_ONLY")?.lowercase()
        if (!env.isNullOrBlank()) {
            return env == "1" || env == "true" || env == "yes"
        }

        return false
    }
}
