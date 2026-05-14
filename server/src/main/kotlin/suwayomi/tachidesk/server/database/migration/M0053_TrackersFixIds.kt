@file:Suppress("ktlint:standard:property-naming")

package suwayomi.tachidesk.server.database.migration

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.server.database.migration.helpers.MAYBE_TYPE_PREFIX
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

@Suppress("ClassName", "unused")
class M0053_TrackersFixIds : SQLMigration() {
    private val TrackRecordTable by lazy { "TrackRecord".toSqlName() }
    private val SyncIdColumn by lazy { "sync_id".toSqlName() }
    private val LibraryIdColumn by lazy { "library_id".toSqlName() }
    private val RemoteIdColumn by lazy { "remote_id".toSqlName() }
    private val RemoteUrlColumn by lazy { "remote_url".toSqlName() }

    override val sql by lazy {
        """
        -- Save the current remote_id as library_id, since old Kitsu tracker did not use this correctly
        UPDATE $TrackRecordTable SET $LibraryIdColumn = $RemoteIdColumn WHERE $SyncIdColumn = 3;

        -- Kitsu isn't using the remote_id field properly, but the ID is present in the URL
        -- This parses a url and gets the ID from the trailing path part, e.g. https://kitsu.app/manga/<id>
        UPDATE $TrackRecordTable SET $RemoteIdColumn = ${toNumber(rightMost(RemoteUrlColumn, '/'))} WHERE $SyncIdColumn = 3;
        """.trimIndent()
    }

    fun sqliteRightMost(
        field: String,
        sep: Char,
    ): String = field

    fun sqliteToNumber(expr: String): String = "CAST($expr AS INTEGER)"

    fun rightMost(
        field: String,
        sep: Char,
    ) = sqliteRightMost(field, sep)

    fun toNumber(expr: String) = sqliteToNumber(expr)
}
