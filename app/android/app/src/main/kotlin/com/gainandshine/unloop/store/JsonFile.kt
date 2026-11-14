package com.gainandshine.unloop.store

import com.gainandshine.unloop.core.store.Json
import java.io.File

/**
 * Atomic JSON file: write to a temp file then rename. A corrupt or unreadable file reads as null and is
 * kept as `<name>.bak` so a bad parse never silently destroys user data.
 */
class JsonFile(private val file: File) {
    fun read(): Any? {
        if (!file.exists()) return null
        return try {
            Json.parse(file.readText())
        } catch (e: Exception) {
            runCatching { file.copyTo(File(file.path + ".bak"), overwrite = true) }
            null
        }
    }

    fun write(v: Any?) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.write(v))
        if (!tmp.renameTo(file)) { // ponytail: only Windows JVM tests hit this; Android rename replaces atomically
            file.delete()
            check(tmp.renameTo(file)) { "rename failed" }
        }
    }
}
