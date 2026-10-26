package de.honeybalecta.vorlesepult

import android.app.Application

/**
 * Prozessweite Bestandteile. Engine, Wiedergabe, Vorleser und der lokale Server leben länger als das Fenster,
 * damit das Vorlesen auch im Hintergrund und bei gesperrtem Bildschirm weiterläuft.
 */
class App : Application() {
    lateinit var store: Store
        private set
    lateinit var engine: Engine
        private set
    lateinit var player: Player
        private set
    lateinit var reader: Reader
        private set
    lateinit var server: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        store = Store(this)
        engine = Engine(this)
        player = Player()
        reader = Reader(this, engine, player)
        server = LocalServer(this).start()
    }
}
