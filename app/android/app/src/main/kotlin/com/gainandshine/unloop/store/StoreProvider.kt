package com.gainandshine.unloop.store

import android.content.Context
import java.io.File

/** Process-wide holder of the file-backed repositories (one instance each, so the locks are meaningful). */
class StoreProvider private constructor(dir: File) {
    val limits = FileLimitsRepository(dir)
    val schedules = FileSchedulesRepository(dir)
    val usage = FileUsageRepository(dir)
    val extensions = FileExtensionsRepository(dir)
    val settings = FileSettingsRepository(dir)
    val tempAccess = FileTempAccessStore(dir)

    companion object {
        @Volatile private var instance: StoreProvider? = null
        fun get(c: Context): StoreProvider =
            instance ?: synchronized(this) {
                instance ?: StoreProvider(File(c.applicationContext.filesDir, "store")).also { instance = it }
            }
    }
}
