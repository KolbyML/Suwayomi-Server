package suwayomi.tachidesk.server.settings

import suwayomi.tachidesk.server.types.Settings
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

internal fun Settings.asMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()

    this::class.memberProperties.forEach { property ->
        try {
            @Suppress("UNCHECKED_CAST")
            val value = (property as KProperty1<Settings, *>).get(this)
            map[property.name] = value
        } catch (e: Exception) {
            // Skip properties that can't be accessed
        }
    }

    return map
}
