package com.survivor.engine.data

import com.survivor.engine.Game
import com.survivor.engine.Season
import com.survivor.engine.UserState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedState(val season: Season? = null, val user: UserState = UserState(), val savedAtEpochMs: Long = 0L)

/** JSON persistence for the whole app state. Unknown keys are ignored so older files keep loading. */
object StateCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    fun encode(state: SavedState): String = json.encodeToString(SavedState.serializer(), state)
    fun decode(text: String): SavedState = json.decodeFromString(SavedState.serializer(), text)
}

/** Merge helpers for incremental refreshes. */
object SeasonMerge {
    /** Replaces games by id, keeping an existing FPI projection / consensus line when the new copy lacks one. */
    fun mergeGames(existing: List<Game>, incoming: List<Game>): List<Game> {
        val byId = existing.associateBy { it.id }.toMutableMap()
        for (g in incoming) {
            val old = byId[g.id]
            byId[g.id] = if (old == null) g else g.copy(
                fpi = g.fpi ?: old.fpi,
                consensus = g.consensus ?: old.consensus,
                line = g.line ?: old.line,
            )
        }
        return byId.values.sortedWith(compareBy({ it.week }, { it.kickoffEpochMs }, { it.id }))
    }
}
