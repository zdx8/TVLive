package com.tvlive.tv.player

import android.app.PendingIntent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tvlive.tv.R

/**
 * PlaybackService — a [MediaSessionService] that owns an [ExoPlayer]
 * and exposes it to the system as a MediaSession.
 *
 * Why we need this: on Android TV the system wants to know which app is
 * currently playing audio so it can show media controls in Quick
 * Settings and in the TV's media notifications, and so the remote's
 * play/pause keys keep working while the activity is backgrounded.
 *
 * Lifecycle:
 *   - onCreate()      builds the ExoPlayer + MediaSession
 *   - onGetSession()  called by the Media3 framework when an external
 *                     controller (system UI, Wear, Auto) wants a handle
 *   - onDestroy()     releases the player
 *
 * Note: MainActivity and PlaybackActivity currently own their own
 * ExoPlayer instances via [PlayerController]. Fully moving playback into
 * this service is tracked as a follow-up; for now the service exists so
 * the system can find us and so the manifest entry is justified.
 *
 * `@UnstableApi`: `DefaultMediaNotificationProvider` — the provider that
 * creates the notification channel and paints the media notification —
 * is still marked unstable by Media3. Any code touching it has to opt in
 * explicitly; there is no stable replacement yet.
 */
@androidx.media3.common.util.UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // Give Media3 a notification provider we control. Two reasons:
        //
        //  1. DefaultMediaNotificationProvider creates the notification
        //     channel itself (DEFAULT_CHANNEL_ID), which fixes the
        //     "no channel with id itellytv_playback" crash we used to
        //     get from posting to a channel nobody had registered.
        //  2. setSmallIcon() lets us use our own monochrome silhouette
        //     instead of the multi-colour app banner, which the system
        //     would otherwise flatten into a white blob.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_playback)
            }
        )

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
        player = exo

        // Tapping the notification should bring the user back into the
        // app. getLaunchIntentForPackage() is non-null for our own
        // package in practice, but it is declared @Nullable — and
        // setSessionActivity() rejects null — so we only set it when we
        // actually got an intent (rather than force-unwrapping and
        // risking a startup crash).
        val sessionBuilder = MediaSession.Builder(this, exo)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val sessionActivity = PendingIntent.getActivity(
                this,
                /* requestCode = */ 0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        mediaSession = sessionBuilder.build()

        Log.i(TAG, "PlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * NOTE: we deliberately do **not** override onBind().
     *
     * MediaSessionService.onBind() hands out the binder that the Media3
     * controller (system UI, notification, bluetooth stack, Android
     * Auto) talks to. The previous `override fun onBind(...) = null`
     * returned null to every caller, which silently broke every
     * external controller while still compiling cleanly — the service
     * bound, then immediately disconnected. Letting the superclass
     * implementation run is the whole point of extending
     * MediaSessionService.
     *
     * NOTE: we deliberately do **not** override onUpdateNotification()
     * either. MediaSessionService already starts and stops the
     * foreground service as playback starts and stops, using the
     * MediaNotification.Provider installed above. Re-implementing it by
     * hand (as an earlier revision did) meant calling startForeground()
     * on a channel that had never been created, and hard-coding
     * FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK on a notification the
     * framework was also managing.
     */

    override fun onDestroy() {
        Log.i(TAG, "PlaybackService destroyed")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TVLive.PlaybackSvc"
    }
}
