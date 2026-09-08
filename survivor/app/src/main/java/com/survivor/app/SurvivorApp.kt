package com.survivor.app

import android.app.Application
import com.survivor.app.data.EspnClient
import com.survivor.app.data.OddsApiClient
import com.survivor.app.data.OkHttpFetcher
import com.survivor.app.data.StateStore
import com.survivor.app.data.SurvivorRepository
import java.io.File

class SurvivorApp : Application() {
    lateinit var repository: SurvivorRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpFetcher()
        repository = SurvivorRepository(
            store = StateStore(File(filesDir, "survivor_state.json")),
            espn = EspnClient(http),
            oddsApi = OddsApiClient(http),
        )
    }
}
