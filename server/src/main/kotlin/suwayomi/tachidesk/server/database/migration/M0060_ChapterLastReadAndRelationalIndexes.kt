package suwayomi.tachidesk.server.database.migration

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0060_ChapterLastReadAndRelationalIndexes : SQLMigration() {
    override val sql: String =
        """
        CREATE INDEX IF NOT EXISTS idx_chapter_last_read_at ON Chapter(last_read_at);
        CREATE INDEX IF NOT EXISTS Chapter_idx_manga ON Chapter(manga);
        CREATE INDEX IF NOT EXISTS Manga_idx_in_library ON Manga(in_library);
        CREATE INDEX IF NOT EXISTS Manga_idx_source ON Manga(source);
        CREATE INDEX IF NOT EXISTS Page_idx_chapter ON Page(chapter);
        CREATE INDEX IF NOT EXISTS CategoryManga_idx_manga ON CategoryManga(manga);
        CREATE INDEX IF NOT EXISTS CategoryManga_idx_category ON CategoryManga(category);
        CREATE INDEX IF NOT EXISTS CategoryMeta_idx_category_ref ON CategoryMeta(category_ref);
        CREATE INDEX IF NOT EXISTS ChapterMeta_idx_chapter_ref ON ChapterMeta(chapter_ref);
        CREATE INDEX IF NOT EXISTS MangaMeta_idx_manga_ref ON MangaMeta(manga_ref);
        """.trimIndent()
}
