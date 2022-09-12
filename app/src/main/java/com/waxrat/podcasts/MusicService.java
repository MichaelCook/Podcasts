/*
 This software is distributed under the "Simplified BSD license":

 Copyright Michael Cook <michael@waxrat.com>. All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

    1. Redistributions of source code must retain the above copyright notice,
       this list of conditions and the following disclaimer.

    2. Redistributions in binary form must reproduce the above copyright
       notice, this list of conditions and the following disclaimer in the
       documentation and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY MICHAEL COOK ''AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 EVENT SHALL MICHAEL COOK OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 The views and conclusions contained in the software and documentation are
 those of the authors and should not be interpreted as representing official
 policies, either expressed or implied, of Michael Cook.
*/

/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.waxrat.podcasts;

import static java.lang.Integer.max;

import java.io.File;
import java.io.IOException;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MusicService
extends Service
implements OnCompletionListener,
           OnPreparedListener,
           OnErrorListener,
           OnSeekCompleteListener,
           AudioManager.OnAudioFocusChangeListener {

    private final static String TAG = "Podcasts.MusicService";

    public static final String ACTION_UPDATE_METADATA = "com.waxrat.podcasts.action.UPDATE_METADATA";
    public static final String ACTION_GET_STATUS = "com.waxrat.podcasts.action.GET_STATUS";
    public static final String ACTION_MAYBE_PAUSE = "com.waxrat.podcasts.action.MAYBE_PAUSE";
    public static final String ACTION_PAUSE = "com.waxrat.podcasts.action.PAUSE";
    public static final String ACTION_PLAY = "com.waxrat.podcasts.action.PLAY";
    public static final String ACTION_SEEK = "com.waxrat.podcasts.action.SEEK";
    public static final String ACTION_SKIP_BACK = "com.waxrat.podcasts.action.SKIP_BACK";
    public static final String ACTION_SKIP_FORWARD = "com.waxrat.podcasts.action.SKIP_FORWARD";
    public static final String ACTION_SKIP_TO_TRACK_END = "com.waxrat.podcasts.action.SKIP_TO_TRACK_END";
    public static final String ACTION_SKIP_TO_TRACK_START = "com.waxrat.podcasts.action.SKIP_TO_TRACK_START";
    public static final String ACTION_STOP = "com.waxrat.podcasts.action.STOP";
    public static final String ACTION_TOGGLE_PLAYBACK = "com.waxrat.podcasts.action.TOGGLE_PLAYBACK";

    /** When starting a track, backup this amount from where we left off. */
    public static final int OVERLAP_MS = 250;

    public static final String ACTION_PLAY_STATE = "com.waxrat.podcasts.intent.PLAY_STATE";
    private final Handler tickHandler = new Handler(Looper.getMainLooper());

    private static final int UPDATE_PERIOD_MS = 1000;

    private final Runnable activityUpdater = new Runnable() {
        public void run() {
            updateActivity();
            tickHandler.postDelayed(this, UPDATE_PERIOD_MS);
        }
    };

    private @Nullable MediaPlayer mPlayer = null;
    private @Nullable PowerManager.WakeLock mWakeLock = null;

    // indicates the state our service:
    private enum State {
        Stopped,    // media player is stopped and not prepared to play
        Preparing,  // media player is preparing...
        Playing,    /* playback active (media player ready!). (but the media
                       player may actually be paused in this state if we don't
                       have audio focus. But we stay in this state so that we
                       know we have to resume playback once we get focus
                       back) */
        Paused,     // playback paused (media player ready)
    }

    private static State mState = State.Stopped;
    private MediaSession mSession;
    private long mLastTcpNotifyMs;

    static boolean playing() {
        return mState == State.Preparing || mState == State.Playing;
    }

    private void updateActivity() {
        if (mPlayer == null)
            return;
        Track t = playingTrack;
        if (t == null)
            return;
        Intent i = new Intent(ACTION_PLAY_STATE);
        boolean isPlaying = playing();
        i.putExtra("play", isPlaying);
        if (isPlaying)
            i.putExtra("ident", t.ident);
        if (!mPlayer.isPlaying()) {
            /*
             * Happens when:
             * - we press Play to start playing.
             * - a track has just finished and we're automatically starting the next.
             */
            Log.i(TAG, "updateActivity: Not playing " + t);
        }
        else {
            int curMs = mPlayer.getCurrentPosition();
            if (curMs > t.durMs)
                curMs = t.durMs;
            t.curMs = curMs;

            long nowMs = System.currentTimeMillis();
            if (nowMs - mLastTcpNotifyMs >= 5 * 1000) {
                mLastTcpNotifyMs = nowMs;
                TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, t.ident, String.valueOf(t.curMs));
            }
        }
        sendBroadcast(i);
        updateMediaSessionPlaybackState();
    }

    private void updateMediaSessionPlaybackState() {
        if (mPlayer == null)
            return;
        PlaybackState.Builder b = new PlaybackState.Builder();
        b.setActions(PlaybackState.ACTION_PLAY
                   | PlaybackState.ACTION_PAUSE
                   | PlaybackState.ACTION_PLAY_PAUSE
                   | PlaybackState.ACTION_SKIP_TO_NEXT
                   | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                   | PlaybackState.ACTION_STOP
                   | PlaybackState.ACTION_SEEK_TO);
        b.setState(playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
            mPlayer.getCurrentPosition(), 1);
        mSession.setPlaybackState(b.build());
    }

    void updateMediaSessionMetaData() {
        Track t = playingTrack;
        if (t == null) {
            mSession.setMetadata(null);
            return;
        }

        final MediaMetadata.Builder b = new MediaMetadata.Builder();
        b.putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist);
        b.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, t.artist);
        b.putString(MediaMetadata.METADATA_KEY_ALBUM, t.artist);
        b.putString(MediaMetadata.METADATA_KEY_TITLE, t.title);
        b.putLong(MediaMetadata.METADATA_KEY_DURATION, t.durMs);
        b.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, Tracks.getPositionOfTrack(t) + 1);
        //b.putLong(MediaMetadata.METADATA_KEY_YEAR, t.year);
        b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, null);
        b.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, Tracks.numTracks());
        mSession.setMetadata(b.build());
    }

    private void setState(State newState) {
        if (mState == newState)
            return;
        mState = newState;
        tickHandler.removeCallbacks(activityUpdater);
        if (mState == State.Playing)
            tickHandler.postDelayed(activityUpdater, 1000);
        updateActivity();
    }

    // The track currently playing
    private Track playingTrack = null;

    // The track currently playing
    private Track preparingTrack = null;

    // What time offset to seek to when prepared
    private int seekMs = -1;

    // The ID we use for the notification (the on-screen alert that appears at
    // the notification area at the top of the screen as an icon -- and as
    // text as well if the user expands the notification area).
    private static final int NOTIFICATION_ID = 1;

    private NotificationManager mNotificationManager;

    private NotificationCompat.Builder mNotificationBuilder = null;

    /**
     * Makes sure the media player exists and has been reset.  Create
     * the media player if needed, or reset the existing media player if one
     * already exists.
     */
    private void createMediaPlayerIfNeeded() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.reset();
            return;
        }
        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);
        mPlayer.setOnErrorListener(this);
        mPlayer.setOnSeekCompleteListener(this);

        if (mWakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) {
                Note.e(TAG, "No power manager");
                return;
            }
            mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Podcasts:MusicService");
        }
        mWakeLock.acquire(10*60*1000L /*10 minutes*/);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();

        mSession = new MediaSession(this, "Podcasts");
        mSession.setCallback(mMediaSessionCallback);
        mSession.setActive(true);
    }

    private final MediaSession.Callback mMediaSessionCallback = new MediaSession.Callback() {

        @Override
        public void onPlay() {
            super.onPlay();
            Note.toastShort(MusicService.this, "Play");
            play(null);
        }

        @Override
        public void onPause() {
            super.onPause();
            Note.toastShort(MusicService.this, "Pause");
            pause();
        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            Note.toastShort(MusicService.this, "Next");
            skipForward();
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            Note.toastShort(MusicService.this, "Previous");
            skipBack();
        }

    };

    private void skipToTrackEnd() {
        Track t = Tracks.currentTrack();
        if (t == null) {
            Note.toastShort(this, "No track");
            return;
        }
        /* Don't skip all the way to the end. The media player acts
           strangely if you do that, sometimes playing a fraction of
           a second of the beginning of the track. */
        int end = t.durMs - 1000;
        if (end < 0)
            end = 0;
        seekTo(end);
    }

    private void play(Intent intent) {
        /* A RemoteControlClient could cause KeyEvent.KEYCODE_MEDIA_PLAY =>
           ACTION_PLAY resulting in "no path & no track" because playingTrack
           hasn't been set yet. */
        Tracks.restore(this, true);

        Track t;
        String ident = null;
        if (intent != null)
            ident = intent.getStringExtra("ident");
        if (ident == null) {
            t = Tracks.currentTrack();
            if (t == null) {
                Note.toastLong(this, "play: no current track");
                return;
            }
            Log.i(TAG, "play: play current track " + t);
        } else {
            t = Tracks.findTrackByIdent(ident);
            if (t == null) {
                Note.toastLong(this, "play: no such track " + ident);
                return;
            }
        }
        Note.toastShort(this, t.artist);
        if (intent != null)
            seekMs = intent.getIntExtra("ms", t.curMs);
        else
            seekMs = t.curMs;
        Log.i(TAG, "play " + t + " at " + seekMs);
        tryToGetAudioFocus();
        playTrack(t);
    }

    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action == null)
            return START_NOT_STICKY;
        Log.i(TAG, "onStartCommand " + action);
        switch (action) {
            case ACTION_TOGGLE_PLAYBACK:
                if (playing())
                    pause();
                else
                    play(intent);
                break;
            case ACTION_PLAY:
                play(intent);
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_MAYBE_PAUSE:
                maybePause();
                break;
            case ACTION_SKIP_FORWARD:
                skipForward();
                break;
            case ACTION_SKIP_BACK:
                skipBack();
                break;
            case ACTION_SKIP_TO_TRACK_START:
                seekTo(0);
                Tracks.writeState(this, ACTION_SKIP_TO_TRACK_START);
                break;
            case ACTION_SEEK:
                seekTo(intent.getIntExtra("where", 0));
                break;
            case ACTION_SKIP_TO_TRACK_END:
                skipToTrackEnd();
                break;
            case ACTION_STOP:
                stop();
                break;
            case ACTION_UPDATE_METADATA:
                updateMediaSessionMetaData();
                break;
            case ACTION_GET_STATUS:
                if (playing() && playingTrack != null)
                {
                    TcpService.broadcast(TcpService.NFY_PLAYING, playingTrack.ident,
                            String.valueOf(playingTrack.curMs));
                }
                else
                {
                    TcpService.broadcast(TcpService.NFY_PAUSED);
                }
                break;
            default:
                Note.w(TAG, "Unexpected action " + action);
                break;
        }

        return START_NOT_STICKY; // Don't restart this service if killed
    }

    private long lastPause = 0;

    /* For some Bluetooth sources, when you disconnect them they generate a
       PAUSE event then (a second or two later) a DISCONNECT event
       (onAudioBecomingNoisy).  We ordinarily handle both events as a pause().
       But when they come one after the other, the second event is annoying,
       so ignore it. */
    private void maybePause() {
        long now = System.currentTimeMillis();
        if (now >= lastPause + 5000)
            pause();
        else
            Log.i(TAG, "Too soon, no pause");
    }

    private void pause() {
        lastPause = System.currentTimeMillis();
        if (mState != State.Playing) {
            Log.i(TAG, "pause while not playing");
            return;
        }
        Log.i(TAG, "pause");
        setState(State.Paused);
        assert mPlayer != null;
        mPlayer.pause();
        relaxResources(false); // while paused, we always retain the MediaPlayer
        // do not give up audio focus
        Tracks.writeState(this, "pause");
        TcpService.broadcast(TcpService.NFY_PAUSED);
    }

    private void seekTo(int ms) {
        if (mState != State.Playing && mState != State.Paused) {
            Log.i(TAG, "seek while not playing and not paused");
            return;
        }
        Log.v(TAG, "seek " + ms);
        assert mPlayer != null;
        mPlayer.seekTo(ms);
        updateActivity();

        Track t = Tracks.currentTrack();
        if (t != null)
            TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, t.ident, String.valueOf(ms));
    }

    /** How much to advance. */
    private int skipForwardMs = 30000;

    /** How much to rewind. */
    private int skipBackwardMs = 10000;

    /** Never advance further than this far from the end of the track. */
    private final static int BARRIER_MS = 1000;

    private Toast mQuietToast;

    private void skipForward() {
        if (mState != State.Playing && mState != State.Paused) {
            Log.i(TAG, "forward while not playing and not paused");
            return;
        }
        Log.v(TAG, "forward");
        assert mPlayer != null;
        int curMs = mPlayer.getCurrentPosition();
        int durMs = mPlayer.getDuration();
        int barrierMs = durMs - BARRIER_MS;
        if (barrierMs <= 0)
            return; // track is not longer than the barrier
        if (curMs >= barrierMs)
            return; // current position is already past the barrier
        int newMs = curMs + skipForwardMs;
        if (newMs > barrierMs)
            newMs = barrierMs;

        if (playingTrack != null && playingTrack.quiet != null) {
            for (int q : playingTrack.quiet) {
                if (curMs < q && newMs >= q) {
                    newMs = q;
                    if (mQuietToast != null)
                        mQuietToast.cancel();
                    mQuietToast = Note.toastShort(this, "Quiet!");
                    break;
                }
            }
        }

        seekTo(newMs);
    }

    private void skipBack() {
        if (mState != State.Playing && mState != State.Paused) {
            Log.i(TAG, "rewind while not playing and not paused");
            return;
        }
        Log.i(TAG, "rewind");
        assert mPlayer != null;
        int curMs = mPlayer.getCurrentPosition();
        int newMs = curMs - skipBackwardMs;
        if (newMs < 0)
            newMs = 0;
        seekTo(newMs);
    }

    private void stop() {
        Log.i(TAG, "stop");
        if (mState != State.Stopped) {
            setState(State.Stopped);
            TcpService.broadcast(TcpService.NFY_STOPPED);
        }
        updateActivity();
        relaxResources(true);
        giveUpAudioFocus();
        Tracks.writeState(this, "stop");
        stopSelf();
    }

    /**
     * Releases resources used by the service for playback. This includes the
     * "foreground service" status and notification, the wake locks and
     * possibly the MediaPlayer.
     *
     * @param releaseMediaPlayer Indicates whether the Media Player should
     *        also be released or not
     */
    private void relaxResources(boolean releaseMediaPlayer) {
        // stop being a foreground service
        stopForeground(STOP_FOREGROUND_REMOVE);

        // stop and release the Media Player, if it's available
        if (releaseMediaPlayer) {
            if (mPlayer != null) {
                mPlayer.reset();
                mPlayer.release();
                mPlayer = null;
            }
            if (mWakeLock != null) {
                if (mWakeLock.isHeld())
                    mWakeLock.release();
                mWakeLock = null;
            }
        }
    }

    /**
     * Reconfigures MediaPlayer according to audio focus settings and
     * starts/restarts it. This method starts/restarts the MediaPlayer
     * respecting the current audio focus state. So if we have focus, it will
     * play normally; if we don't have focus, it will either leave the
     * MediaPlayer paused or set it to a low volume, depending on what is
     * allowed by the current focus settings. This method assumes mPlayer !=
     * null, so if you are calling it, you have to do so from a context where
     * you are sure this is the case.
     */
    private void configAndStartMediaPlayer() {
        if (mPlayer == null)
            return;

        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause,
            // even if mState is State.Playing. But we stay in the Playing
            // state so that we know we have to resume playback once we get
            // the focus back.
            if (mPlayer.isPlaying()) {
                Log.i(TAG, "mPlayer pause");
                mPlayer.pause();
            }
            return;
        }

        float volume = 1.0f;
        if (mAudioFocus == AudioFocus.NoFocusCanDuck)
            volume = 0.4f;
        Log.v(TAG, "setVolume " + volume);
        mPlayer.setVolume(volume, volume);

        if (!mPlayer.isPlaying())
            mPlayer.start();

        updateMediaSessionMetaData();
    }

    private void playTrack(@NonNull Track t) {
        File audioFile = t.getAudioFile(this);

        skipForwardMs = t.skipForwardMs;
        skipBackwardMs = t.skipBackwardMs;

        try {
            createMediaPlayerIfNeeded();
            assert mPlayer != null;
            //mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setDataSource(audioFile.toString());
            setState(State.Preparing);
            setUpAsForeground('"' + t.title + "\" (play)");

            // Start preparing the media player in the background.  It will call our
            // OnPreparedListener.  Then we can call start().
            preparingTrack = t;
            assert mPlayer != null;
            mPlayer.prepareAsync();
        } catch (IOException ex) {
            Note.e(TAG, "IOException playing next song", ex);
        }
        TcpService.broadcast(TcpService.NFY_PLAYING, t.ident, String.valueOf(t.curMs));
    }

    private void setAfterTrack(MainActivity.AfterTrack afterTrack) {
        Intent i = new Intent(ACTION_PLAY_STATE);
        i.putExtra("setAfterTrack", afterTrack);
        sendBroadcast(i);
    }

    /** Called when media player is done playing current song. */
    public void onCompletion(@NonNull MediaPlayer player) {
        // TODO: workaround spurious skip-to-end
        boolean spurious = false;

        // The media player finished playing the current song, so we go ahead
        // and start the next.
        Track track = Tracks.currentTrack();
        if (track == null)
            Note.w(TAG, "onCompletion: no current track");
        else {
            int remMs = track.durMs - track.curMs;
            spurious = remMs > UPDATE_PERIOD_MS;
            if (spurious)
                Log.w(TAG, "Spurious completion of " + track + " with " + remMs);
            else {
                track.curMs = track.durMs;
                Tracks.writeState(this, "onCompletion");
                TcpService.broadcast(TcpService.NFY_TRACK_FINISHED, track.ident);
            }
        }

        MediaPlayer mp = MediaPlayer.create(this, spurious ? R.raw.loser : R.raw.beep);
        if (mp == null)
            Note.w(TAG, "No media player for beep");
        else {
            mp.start();
            while (mp.isPlaying() && Utilities.sleep(100))
                ;
            mp.release();
        }

        if (!spurious) {
            track = null;
            switch (MainActivity.mAfterTrack) {
            case FIRST:
                track = Tracks.pickFirst();
                break;
            case STOP:
                // After stopping, return to FIRST mode
                setAfterTrack(MainActivity.AfterTrack.FIRST);
                break;
            case NEXT:
                track = Tracks.pickNext();
                break;
            }
        }

        if (track == null) {
            Log.i(TAG, "No track to play now, stopping");
            stop();
            return;
        }

        seekMs = max(0, track.curMs - (spurious ? 0 : OVERLAP_MS));
        Log.i(TAG, "Will play " + track + " at " + seekMs);
        tryToGetAudioFocus();
        playTrack(track);
    }

    /** Called when media player is done preparing. */
    public void onPrepared(@NonNull MediaPlayer player) {
        // The media player is done preparing, we can start playing
        if (preparingTrack == null)
            return;
        playingTrack = preparingTrack;
        preparingTrack = null;
        Tracks.selectTrackByIdent(playingTrack.ident);

        setState(State.Playing);
        assert playingTrack != null;
        updateNotification('"' + playingTrack.title + '"');
        if (seekMs != -1) {
            int barrier = player.getDuration() - BARRIER_MS;
            if (barrier < 0)
                barrier = 0;
            if (seekMs > barrier)
                seekMs = barrier;
        }
        if (seekMs > 0)
            player.seekTo(seekMs);
        else {
            configAndStartMediaPlayer();
            Tracks.writeState(this, "onPrepared");
        }
        seekMs = -1;
    }

    private void updateNotification(@NonNull String text) {
        NotificationCompat.Builder b = mNotificationBuilder;
        if (b == null)
            return;
        Intent ni = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                0, ni, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ni.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(pi);
        b.setContentText(text);
        mNotificationManager.notify(NOTIFICATION_ID, b.build());
    }

    private static final String NOW_PLAYING_CHANNEL_ID = "now-playing-channel";

    /**
     * Configures service as a foreground service.  A foreground service is a
     * service that's doing something the user is actively aware of (such as
     * playing music), and must appear to the user as a notification.  That's
     * why we create the notification here.
     */
    private void setUpAsForeground(@NonNull String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOW_PLAYING_CHANNEL_ID);
        b.setContentTitle("Podcasts");
        b.setContentText(text);
        b.setSmallIcon(R.drawable.ic_stat_playing);
        startForeground(NOTIFICATION_ID, b.build());
        mNotificationBuilder = b;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(NOW_PLAYING_CHANNEL_ID, "Now Playing",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("The podcast track currently playing");
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        mNotificationManager.createNotificationChannel(channel);
    }

    /**
     * Called when there's an error playing media.  When this happens, the
     * media player goes to the Error state.  We warn the user about the error
     * and reset the media player.
     */
    public boolean onError(@NonNull MediaPlayer mp, int what, int extra) {
        Note.toastLong(this, "Media player error!");
        Note.e(TAG, "Error: what=" + what + ", extra=" + extra);

        setState(State.Stopped);
        relaxResources(true);
        giveUpAudioFocus();
        return true; // we handled the error
    }

    // -----------------------------------------------------------------------
    /// audio focus

    private enum AudioFocus {
        NoFocusNoDuck,  // we don't have audio focus, and can't duck
        NoFocusCanDuck, // we don't have focus, but can play at a low volume ("ducking")
        Focused         // we have full audio focus
    }

    private AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;
    private AudioManager mAudioManager;

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.i(TAG, "Gained audio focus");
                mAudioFocus = AudioFocus.Focused;
                // restart media player with new focus settings
                if (mState == State.Playing)
                    configAndStartMediaPlayer();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.i(TAG, "Lost audio focus, can't duck");
                Note.toastLong(this, "Lost focus, can't duck");
                mAudioFocus = AudioFocus.NoFocusNoDuck;
                stop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.i(TAG, "Lost audio focus, can duck");
                mAudioFocus = AudioFocus.NoFocusCanDuck;
                // start/restart/pause media player with new focus settings
                if (mPlayer != null && mPlayer.isPlaying())
                    configAndStartMediaPlayer();
                break;
            default:
                Note.w(TAG, "Unknown audio focus change " + focusChange);
                break;
        }
    }

    private AudioFocusRequest audioFocusRequest;

    boolean requestFocus() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(this)
                .setAudioAttributes(attrs)
                .build();
        int result = mAudioManager.requestAudioFocus(audioFocusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Note.w(TAG, "Audio focus request denied: " + result);
            return false;
        }
        Log.i(TAG, "Audio focus request granted");
        return true;
    }

    boolean abandonFocus() {
        int result = mAudioManager.abandonAudioFocusRequest(audioFocusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Note.w(TAG, "Audio focus abandon denied: " + result);
            return false;
        }
        Log.i(TAG, "Audio focus request granted");
        return true;
    }

    private void giveUpAudioFocus() {
        if (mAudioFocus == AudioFocus.Focused && abandonFocus())
            mAudioFocus = AudioFocus.NoFocusNoDuck;
    }

    private void tryToGetAudioFocus() {
        if (mAudioFocus != AudioFocus.Focused && requestFocus())
            mAudioFocus = AudioFocus.Focused;
    }

    // -----------------------------------------------------------------------

    public void onSeekComplete(@NonNull MediaPlayer mp) {
        configAndStartMediaPlayer();
    }

    @Override
    public void onDestroy() {
        if (mSession != null) {
            mSession.setActive(false);
            mSession.release();
        }
        tickHandler.removeCallbacks(activityUpdater);
        setState(State.Stopped);
        relaxResources(true);
        giveUpAudioFocus();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
}
