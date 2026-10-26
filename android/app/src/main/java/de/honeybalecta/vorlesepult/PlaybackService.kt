package de.honeybalecta.vorlesepult

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Hält das Vorlesen im Hintergrund am Leben (Vordergrunddienst für Medienwiedergabe) und meldet es als
 * Mediensitzung an: Steuerung in der Benachrichtigungsleiste, auf dem Sperrbildschirm und über
 * Kopfhörertasten. Den Ablauf selbst steuert [Reader]; der Dienst spiegelt nur dessen Zustand.
 */
class PlaybackService : Service(), Reader.Listener {
    companion object {
        private const val TAG = "VP.Service"
        private const val CHANNEL = "wiedergabe"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "de.honeybalecta.vorlesepult.TOGGLE"
        const val ACTION_NEXT = "de.honeybalecta.vorlesepult.NEXT"
        const val ACTION_PREV = "de.honeybalecta.vorlesepult.PREV"
        const val ACTION_STOP = "de.honeybalecta.vorlesepult.STOP"
    }

    private lateinit var reader: Reader
    private lateinit var session: MediaSession
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Kopfhörer abgezogen: nicht über den Lautsprecher weiterlesen
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) reader.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        reader = (application as App).reader
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wiedergabe", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Steuerung des Vorlesens in der Benachrichtigungsleiste und auf dem Sperrbildschirm"
                setShowBadge(false)
            })
        session = MediaSession(this, "Vorlesepult").apply {
            setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0,
                Intent(this@PlaybackService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { reader.resume() }
                override fun onPause() { reader.pause() }
                override fun onSkipToNext() { reader.next() }
                override fun onSkipToPrevious() { reader.prev() }
                override fun onStop() { reader.stop() }
                override fun onCustomAction(action: String, extras: Bundle?) { if (action == ACTION_STOP) reader.stop() }
            })
        }
        runCatching {
            ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }.onFailure { Log.w(TAG, "Empfänger für Kopfhörer", it) }
        reader.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nach startForegroundService() zuerst in den Vordergrund, dann den Wunsch ausführen
        foreground()
        when (intent?.action) {
            ACTION_TOGGLE -> reader.toggle()
            ACTION_NEXT -> reader.next()
            ACTION_PREV -> reader.prev()
            ACTION_STOP -> reader.stop()
        }
        if (!reader.isActive) finish()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onReaderChanged(reader: Reader) {
        if (reader.isActive) foreground() else finish()
    }

    /** Benachrichtigung und Mediensitzung auf den Stand des Vorlesers bringen. */
    private fun foreground() {
        val st = reader.state
        updateSession(st)
        val notification = buildNotification(st)
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure {
            Log.w(TAG, "Vordergrund nicht möglich, zeige nur die Benachrichtigung", it)
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun finish() {
        runCatching { session.isActive = false }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    private fun updateSession(st: Reader.State) {
        val playback = when (st) {
            Reader.State.PLAYING -> PlaybackState.STATE_PLAYING
            Reader.State.LOADING -> PlaybackState.STATE_BUFFERING
            Reader.State.PAUSED -> PlaybackState.STATE_PAUSED
            Reader.State.IDLE -> PlaybackState.STATE_STOPPED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (st == Reader.State.PLAYING) 1f else 0f)
            .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_STOP, "Stopp", R.drawable.ic_stop).build())
            .build())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titleText())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, progressText())
            .build())
        session.isActive = st != Reader.State.IDLE
    }

    private fun titleText() = reader.title.ifBlank { "Vorlesepult" }

    private fun progressText(): String {
        val total = reader.queue.size
        val pos = if (total > 0) "Satz ${reader.index + 1} von $total" else "Vorlesen"
        return "$pos · ${reader.voiceName()}"
    }

    private fun buildNotification(st: Reader.State): Notification {
        val playing = st == Reader.State.PLAYING || st == Reader.State.LOADING
        val text = reader.currentText.let { if (it.length > 90) it.take(88).trimEnd() + "…" else it }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vorlesen)
            .setContentTitle(titleText())
            .setContentText(if (st == Reader.State.LOADING) "Bereite vor …" else text.ifBlank { progressText() })
            .setSubText(progressText())
            .setContentIntent(session.controller.sessionActivity)
            .setDeleteIntent(pending(ACTION_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(action(R.drawable.ic_prev, "Vorheriger Satz", ACTION_PREV))
            .addAction(if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE) else action(R.drawable.ic_play, "Weiterlesen", ACTION_TOGGLE))
            .addAction(action(R.drawable.ic_next, "Nächster Satz", ACTION_NEXT))
            .addAction(action(R.drawable.ic_stop, "Stopp", ACTION_STOP))
            .build()
    }

    private fun pending(action: String): PendingIntent = PendingIntent.getForegroundService(
        this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(icon: Int, label: String, act: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pending(act)).build()

    override fun onDestroy() {
        reader.removeListener(this)
        if (noisyRegistered) runCatching { unregisterReceiver(noisy) }
        runCatching { session.isActive = false; session.release() }
        super.onDestroy()
    }
}
