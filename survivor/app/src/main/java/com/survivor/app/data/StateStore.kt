package com.survivor.app.data

import com.survivor.engine.data.SavedState
import com.survivor.engine.data.StateCodec
import java.io.File

/** Atomic JSON file persistence for the whole app state. */
class StateStore(private val file: File) {
    fun load(): SavedState = runCatching { if (file.exists()) StateCodec.decode(file.readText()) else SavedState() }.getOrElse { SavedState() }

    fun save(state: SavedState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StateCodec.encode(state))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun clear() { file.delete() }
}
