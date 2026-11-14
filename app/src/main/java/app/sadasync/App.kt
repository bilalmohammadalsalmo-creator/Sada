package app.sadasync

import android.app.Application
import app.sadasync.data.Prefs
import app.sadasync.data.SourceRepository
import app.sadasync.player.PlayerController
import app.sadasync.sync.AutoSyncEngine

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var sources: SourceRepository
        private set
    lateinit var player: PlayerController
        private set
    lateinit var autoSync: AutoSyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sources = SourceRepository(this, prefs)
        player = PlayerController(this, prefs, sources)
        autoSync = AutoSyncEngine(this, player)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
