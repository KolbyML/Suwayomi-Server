package suwayomi.tachidesk.server.settings

enum class SettingGroup(
    val value: String,
) {
    NETWORK("Network"),
    DATABASE("Database"),
    PROXY("Proxy"),
    DOWNLOADER("Downloader"),
    EXTENSION("Extension/Source"),
    LIBRARY_UPDATES("Library updates"),
    AUTH("Authentication"),
    MISC("Misc"),
    LOCAL_SOURCE("Local source"),
    CLOUDFLARE("Cloudflare"),
    KOREADER_SYNC("KOReader sync"),
    WEB_VIEW("WebView"),
    ;

    override fun toString(): String = value
}
