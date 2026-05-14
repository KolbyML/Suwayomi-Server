package suwayomi.tachidesk.server.util

object ServerSubpath {
    fun isDefined(): Boolean = false

    fun normalized(): String = "/"

    fun maybeAddAsPrefix(path: String): String = path

    fun maybeAddAsSuffix(path: String): String = path

    fun asRootPath(): String = "/"
}
