package suwayomi.tachidesk.server.database.migration.helpers

import de.neonew.exposed.migrations.helpers.SQLMigration
import org.jetbrains.exposed.sql.transactions.TransactionManager

fun String.toSqlName(): String =
    TransactionManager.current().db.identifierManager.let {
        it.quoteIfNecessary(
            it.inProperCase(this),
        )
    }

abstract class RenameFieldMigration(
    tableName: String,
    originalName: String,
    newName: String,
) : SQLMigration() {
    private val fixedTableName by lazy { tableName.toSqlName() }
    private val fixedOriginalName by lazy { originalName.toSqlName() }
    private val fixedNewName by lazy { newName.toSqlName() }

    fun sqliteRename(): String =
        "ALTER TABLE $fixedTableName " +
            "RENAME COLUMN $fixedOriginalName TO $fixedNewName;"

    override val sql by lazy { sqliteRename() }
}
