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

import java.io.File;
import java.io.IOException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.media.RemoteControlClient;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.widget.Toast;

/**
 * Service that handles media playback. This is the Service through which we
 * perform all the media handling in our application.
 */
public class MusicService extends Service implements OnCompletionListener,
OnPreparedListener, OnErrorListener, OnSeekCompleteListener,
MusicFocusable {

    final static String TAG = "MusicService";

    public static final String ACTION_TOGGLE_PLAYBACK = "com.waxrat.podcasts.action.TOGGLE_PLAYBACK";
    public static final String ACTION_PLAY = "com.waxrat.podcasts.action.PLAY";
    public static final String ACTION_PAUSE = "com.waxrat.podcasts.action.PAUSE";
    public static final String ACTION_STOP = "com.waxrat.podcasts.action.STOP";
    public static final String ACTION_FORWARD = "com.waxrat.podcasts.action.FORWARD";
    public static final String ACTION_REWIND = "com.waxrat.podcasts.action.REWIND";
    public static final String ACTION_PREVIOUS = "com.waxrat.podcasts.action.PREVIOUS";
    public static final String ACTION_NEXT = "com.waxrat.podcasts.action.NEXT";
    public static final String ACTION_SEEK = "com.waxrat.podcasts.action.SEEK";
    public static final String ACTION_DELETE_FINISHED = "com.waxrat.podcasts.action.DELETE_FINISHED";
    public static final String ACTION_REWIND_ALL = "com.waxrat.podcasts.action.REWIND_ALL";
    public static final String ACTION_SAVE = "com.waxrat.podcasts.action.SAVE";
    public static final String ACTION_RESTORE = "com.waxrat.podcasts.action.RESTORE";

    // The volume we set the media player to when we lose audio focus, but are
    // allowed to reduce the volume instead of stopping playback.
    public static final float DUCK_VOLUME = 0.4f;

    /** When starting a track, backup this amount from where we left off. */
    public static final int OVERLAP_MS = 2000;

    public static final String PLAY_STATE_INTENT = "com.waxrat.podcasts.intent.PLAY_STATE";
    private final Handler tickHandler = new Handler();

    private Runnable activityUpdater = new Runnable() {
        public void run() {
            updateActivity();
            tickHandler.postDelayed(this, 1000);
        }
    };

    // our media player
    MediaPlayer mPlayer = null;

    /* Keep the CPU from going to sleep while we're playing.  We don't use
       mPlayer.setWakeMode because the CPU would sometimes go to sleep between
       when we finish playing one track and start playing the next. */
    PowerManager.WakeLock wakeLock = null;

    // our AudioFocusHelper object, if it's available (it's available on SDK
    // level >= 8) If not available, this will be null. Always check for null
    // before using!
    AudioFocusHelper mAudioFocusHelper = null;

    // indicates the state our service:
    enum State {
        Stopped,    // media player is stopped and not prepared to play
        Preparing,  // media player is preparing...
        Playing,    /* playback active (media player ready!). (but the media
                       player may actually be paused in this state if we don't
                       have audio focus. But we stay in this state so that we
                       know we have to resume playback once we get focus
                       back) */
        Paused,     // playback paused (media player ready)
        Completed,  // Stopped automatically because we finished playing a track
    };

    static State mState = State.Stopped;

    public static boolean playing() {
        return mState == State.Preparing || mState == State.Playing;
    }

    void updateActivity() {
        Intent i = new Intent(PLAY_STATE_INTENT);
        boolean isPlaying = playing();
        //if (Log.ok) Log.i(TAG, "updateActivity: playing=" + isPlaying);
        i.putExtra("play", isPlaying);
        if (isPlaying) {
            if (playingPath != null)
                i.putExtra("path", playingPath);
            if (mPlayer != null && mPlayer.isPlaying()) {
                Track t = Tracks.findTrackByName(playingPath);
                if (t != null) {
                    int curMs = mPlayer.getCurrentPosition();
                    int durMs = mPlayer.getDuration();
                    if (curMs > durMs) {
                        if (Log.ok) Log.i(TAG, "updateActivity: adjusted curMs from " + curMs +
                              " to " + durMs);
                        curMs = durMs;
                    }
                    t.currentMs = curMs;
                    int d = durMs - t.durationMs;
                    if (d != 0) {
                        if (d != 1)
                            Log.w(TAG, "Update duration from " + t.durationMs +
                                    " to " + durMs + " (" + d + ')');
                        else
                            if (Log.ok) Log.i(TAG, "Update duration by 1 from " +
                                    t.durationMs + " to " + durMs);
                        t.durationMs = durMs;
                    }
                }
            }
        }
        sendBroadcast(i);

        /*
         * This is a workaround for an Android bug. If Android chooses to switch
         * to Bluetooth speakers while we're playing, the playback starts
         * playing at 10x speed until we press pause then play. The hack is to
         * notice when we switch to Bluetooth speakers and then automatically
         * pause for a couple seconds and then resume play.
         */
        if (isPlaying) {
            int isBluetoothA2dpOn = mAudioManager.isBluetoothA2dpOn() ? 1 : 0;
            if (wasBluetoothA2dpOn != -1 &&
                wasBluetoothA2dpOn != isBluetoothA2dpOn) {
                if (isBluetoothA2dpOn != 0) {
                    toastShort("Bluetooth Headset is now ON");
                    pause();
                    tickHandler.postDelayed(autoPlay, 1000);
                } else {
                    toastShort("Bluetooth Headset is now OFF");
                }
            }
            wasBluetoothA2dpOn = isBluetoothA2dpOn;
        }
    }
    private int wasBluetoothA2dpOn = -1;
    private Runnable autoPlay = new Runnable() {
        public void run() {
            tickHandler.removeCallbacks(autoPlay);
            Track t = Tracks.findTrackByName(playingPath);
            if (t != null) {
                seekMs = t.currentMs - 1000;
                if (seekMs < 0)
                    seekMs = 0;
            }
            tryToGetAudioFocus();
            playNextSong();
        }
    };

    void setState(State newState) {
        if (mState == newState)
            return;
        if (Log.ok) Log.i(TAG, "state " + mState + " => " + newState);
        mState = newState;
        tickHandler.removeCallbacks(activityUpdater);
        if (playing())
            tickHandler.postDelayed(activityUpdater, 1000);
        updateActivity();
    }

    enum PauseReason {
        UserRequest,
        FocusLoss,
    };

    // why did we pause? (only relevant if mState == State.Paused)
    PauseReason mPauseReason = PauseReason.UserRequest;

    // do we have audio focus?
    enum AudioFocus {
        NoFocusNoDuck,  // we don't have audio focus, and can't duck
        NoFocusCanDuck, /* we don't have focus, but can play at a low volume
                           ("ducking") */
        Focused         // we have full audio focus
    };

    static AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

    // File name of the track currently playing
    static String playingPath = null;

    // Title of the track currently playing
    static String playingTitle = null;

    /* What time offset to seek to when prepared. */
    private static int seekMs = -1;

    // The ID we use for the notification (the on-screen alert that appears at
    // the notification area at the top of the screen as an icon -- and as
    // text as well if the user expands the notification area).
    final int NOTIFICATION_ID = 1;

    private static RemoteControlClient remoteControl;

    // The component name of MusicIntentReceiver, for use with media button
    // and remote control APIs
    ComponentName mMediaButtonReceiverComponent;

    AudioManager mAudioManager;
    NotificationManager mNotificationManager;

    Notification mNotification = null;

    /**
     * Makes sure the media player exists and has been reset.  Create
     * the media player if needed, or reset the existing media player if one
     * already exists.
     */
    private final void createMediaPlayerIfNeeded() {
        if (mPlayer != null) {
            if (Log.ok) Log.i(TAG, "mPlayer: stop...");
            mPlayer.stop();
            mPlayer.reset();
            return;
        }
        if (Log.ok) Log.i(TAG, "new mPlayer");
        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);
        mPlayer.setOnErrorListener(this);
        mPlayer.setOnSeekCompleteListener(this);

        if (wakeLock == null) {
          PowerManager power = (PowerManager)
            getSystemService(POWER_SERVICE);
          wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        wakeLock.acquire();
    }

    @Override
    public void onCreate() {
        if (Log.ok) Log.i(TAG, "Creating service");
        mNotificationManager = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);
        mMediaButtonReceiverComponent = new ComponentName(this,
                MusicIntentReceiver.class);
    }

    private void save() {
        Tracks.save(getApplicationContext());
    }

    private void restore() {
        if (Tracks.restore(getApplicationContext()))
            announceRestored();
    }

    private void announceRestored() {
        Intent i = new Intent(PLAY_STATE_INTENT);
        i.putExtra("restored", true);
        sendBroadcast(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (Log.ok) Log.i(TAG, "action " + action);
        if (action.equals(ACTION_TOGGLE_PLAYBACK))
            playPause();
        else if (action.equals(ACTION_PLAY)) {
            if (intent.hasExtra("path")) {
                playingPath = intent.getStringExtra("path");
                playingTitle = intent.getStringExtra("title");
                seekMs = intent.getIntExtra("ms", -1);
                if (Log.ok) Log.i(TAG, "playingPath = " + playingPath + " at " + seekMs);
                tryToGetAudioFocus();
                playNextSong();
            }
            else
                play();
        }
        else if (action.equals(ACTION_PAUSE))
            pause();
        else if (action.equals(ACTION_FORWARD))
            forward();
        else if (action.equals(ACTION_REWIND))
            rewind();
        else if (action.equals(ACTION_NEXT))
            next();
        else if (action.equals(ACTION_PREVIOUS))
            seekTo(0);
        else if (action.equals(ACTION_SEEK))
            seekTo(intent.getIntExtra("where", 0));
        else if (action.equals(ACTION_STOP))
            stop(false);
        else if (action.equals(ACTION_DELETE_FINISHED))
            deleteFinished();
        else if (action.equals(ACTION_REWIND_ALL))
            rewindAll();
        else if (action.equals(ACTION_SAVE))
            save();
        else if (action.equals(ACTION_RESTORE))
            restore();
        else
            Log.w(TAG, "Unexpected action " + action);

        return START_NOT_STICKY; // Don't restart this service if killed
    }

    private void rewindAll() {
        int n = 0;
        for (int i = 0; i <= Tracks.position; ++i) {
            Track t = Tracks.tracks.get(i);
            if (t.currentMs != 0) {
                if (Log.ok) Log.i(TAG, "Rewind " + t);
                t.currentMs = 0;
                ++n;
            }
        }
        toastShort("Rewound " + n);
        save();
        announceRestored();
    }

    private void deleteFinished() {
        int del = 0, keep = 0, remSec = 0;
        for (Track t : Tracks.tracks)
            if (t.durationMs != 0 && t.currentMs == t.durationMs) {
                if (Log.ok) Log.i(TAG, "Delete " + t);
                File f = new File(Tracks.FOLDER, t.pathName);
                if (!f.delete()) {
                    Log.w(TAG, "Can't delete " + f);
                    break;
                }
                ++del;
            }
            else {
                ++keep;
                remSec += (t.durationMs - t.currentMs + 500) / 1000;
            }
        int remMin = remSec / 60;
        toastLong(String.format("%d deleted, %d more (%d:%02d)",
                del, keep, remMin / 60, remMin % 60));
        if (del != 0) {
            save();
            restore();
        }
    }

    void playPause() {
        switch (mState) {
        case Paused:
        case Stopped:
        case Completed:
            play();
            break;
        default:
            pause();
            break;
        }
    }

    void play() {
        if (Log.ok) Log.i(TAG, "play()");
        tryToGetAudioFocus();
        if (mState == State.Stopped || mState == State.Completed)
            playNextSong();
        else if (mState == State.Paused) {
            setState(State.Playing);
            if (playingTitle != null)
                setUpAsForeground('"' + playingTitle + "\" (unpause)");
            configAndStartMediaPlayer();
        }
        if (remoteControl != null)
            remoteControl.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
    }

    void pause() {
        if (Log.ok) Log.i(TAG, "pause()");
        if (mState == State.Playing) {
            setState(State.Paused);
            if (Log.ok) Log.i(TAG, "mPlayer pause...");
            mPlayer.pause();
            if (Log.ok) Log.i(TAG, "mPlayer pause...done");
            relaxResources(false); /* while paused, we always retain the
                                      MediaPlayer */
            // do not give up audio focus
            save();
        }
        if (remoteControl != null)
            remoteControl.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
    }

    void seekTo(int ms) {
        if (mState == State.Playing || mState == State.Paused) {
            if (Log.ok) Log.i(TAG, "mPlayer seekTo " + ms);
            mPlayer.seekTo(ms);
            updateActivity();
        }
    }

    void next() {
        if (mState == State.Playing || mState == State.Paused) {
            tryToGetAudioFocus();
            playNextSong();
        }
    }

    /** How much to advance. */
    final static int FORWARD_MS = 30000;

    /** How much to rewind. */
    final static int REWIND_MS = 10000;

    /** Never advance further than this far from the end of the track. */
    final static int BARRIER_MS = 2000;

    void forward() {
        if (mState != State.Playing && mState != State.Paused)
            return;
        int curMs = mPlayer.getCurrentPosition();
        int durMs = mPlayer.getDuration();
        int barrierMs = durMs - BARRIER_MS;
        if (Log.ok) Log.i(TAG, "forward: at " + curMs + " of " + durMs + ", " + barrierMs);
        if (barrierMs <= 0)
            return; // track is not longer than the barrier
        if (curMs >= barrierMs)
            return; // current position is already past the barrier
        int newMs = curMs + FORWARD_MS;
        if (newMs > barrierMs)
            newMs = barrierMs;
        seekTo(newMs);
    }

    void rewind() {
        if (mState != State.Playing && mState != State.Paused)
            return;
        int curMs = mPlayer.getCurrentPosition();
        if (Log.ok) Log.i(TAG, "rewind: at " + curMs + " of " + mPlayer.getDuration());
        int newMs = curMs - REWIND_MS;
        if (newMs < 0)
            newMs = 0;
        seekTo(newMs);
    }

    void stop(boolean completed) {
        if (Log.ok) Log.i(TAG, "stop(" + completed + ')');
        setState(completed ? State.Completed : State.Stopped);
        relaxResources(true);
        giveUpAudioFocus();
        save();

        // Tell any remote controls that our playback state is 'paused'.
        if (remoteControl != null)
            remoteControl
            .setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);

        // service is no longer necessary. Will be started again if needed.
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
    void relaxResources(boolean releaseMediaPlayer) {
        // stop being a foreground service
        stopForeground(true);

        // stop and release the Media Player, if it's available
        if (releaseMediaPlayer) {
          if (mPlayer != null) {
            if (Log.ok) Log.i(TAG, "mPlayer reset & release");
            mPlayer.reset();
            mPlayer.release();
            mPlayer = null;
          }
          if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
          }
        }
    }

    void giveUpAudioFocus() {
        if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null &&
            mAudioFocusHelper.abandonFocus())
            mAudioFocus = AudioFocus.NoFocusNoDuck;
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
    void configAndStartMediaPlayer() {
        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause,
            // even if mState is State.Playing. But we stay in the Playing
            // state so that we know we have to resume playback once we get
            // the focus back.
            if (mPlayer.isPlaying()) {
                if (Log.ok) Log.i(TAG, "mPlayer pause (duck)");
                mPlayer.pause();
            }
            return;
        }
        if (Log.ok) Log.i(TAG, "mPlayer setVolume (duck)");
        if (mAudioFocus == AudioFocus.NoFocusCanDuck)
            mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME);
        else
            mPlayer.setVolume(1.0f, 1.0f); // we can be loud

        if (!mPlayer.isPlaying())
            mPlayer.start();
    }

    void tryToGetAudioFocus() {
        if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null &&
            mAudioFocusHelper.requestFocus())
            mAudioFocus = AudioFocus.Focused;
    }

    void playNextSong() {
        if (Log.ok) Log.i(TAG, "playNextSong()");
        setState(State.Stopped);
        relaxResources(false); // release everything except MediaPlayer

        if (playingPath == null) {
            toastLong("No track to play");
            stop(false);
            return;
        }

        if (Log.ok) Log.i(TAG, "Play " + playingPath);
        try {
            createMediaPlayerIfNeeded();
            if (Log.ok) Log.i(TAG,  "mPlayer setAudioStreamType");
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setDataSource(new File(Tracks.FOLDER, playingPath).toString());
            setState(State.Preparing);
            setUpAsForeground('"' + playingTitle + "\" (play)");

            mAudioManager.registerMediaButtonEventReceiver
                (mMediaButtonReceiverComponent);

            if (remoteControl == null) {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.setComponent(mMediaButtonReceiverComponent);
                remoteControl = new RemoteControlClient
                  (PendingIntent.getBroadcast(this, 0, intent, 0));
                mAudioManager.registerRemoteControlClient(remoteControl);
            }

            /* Car radio doesn't display the title.  See:
               http://www.beyondpod.com/forum/archive/index.php/t-753.html
               Audio/Video Remote Control Profile (AVRCP) is the protocol for
               sending track info to a Bluetooth-connected device.
               Need AVRCP 1.3 or higher. */

            remoteControl.setTransportControlFlags
                (RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD |
                 RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                 RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                 RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                 RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
                 RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                 RemoteControlClient.FLAG_KEY_MEDIA_REWIND |
                 RemoteControlClient.FLAG_KEY_MEDIA_STOP);

            RemoteControlClient.MetadataEditor e = remoteControl.editMetadata(true);
            e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album");
            e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "AlbumArtist");
            e.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, "Artist");
            e.putString(MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Author");
            e.putString(MediaMetadataRetriever.METADATA_KEY_COMPILATION, "Compilation");
            e.putString(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "Composer");
            e.putString(MediaMetadataRetriever.METADATA_KEY_DATE, "Date");
            e.putString(MediaMetadataRetriever.METADATA_KEY_GENRE, "Genre");
            e.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, "Title: " + playingTitle);
            e.putString(MediaMetadataRetriever.METADATA_KEY_WRITER, "Writer");
            e.putLong(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, 1001);
            e.putLong(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, 1002);
            e.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, 60000);
            //e.putLong(MediaMetadataRetriever.METADATA_KEY_YEAR, 2001);
            e.apply();

            remoteControl.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

            // starts preparing the media player in the background. When it's
            // done, it will call our OnPreparedListener (that is, the
            // onPrepared() method on this class, since we set the listener to
            // 'this').  Until the media player is prepared, we *cannot* call
            // start() on it.
            if (Log.ok) Log.i(TAG, "mPlayer prepareAsync");
            mPlayer.prepareAsync();
        } catch (IOException ex) {
            Log.e(TAG, "IOException playing next song: " + ex.getMessage(), ex);
        }
    }

    /** Called when media player is done playing current song. */
    public void onCompletion(MediaPlayer player) {
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        Track v = Tracks.currentTrack();
        if (v == null)
            Log.w(TAG, "onCompletion: no current track");
        else {
            if (Log.ok) Log.i(TAG, "onCompletion " + v + " cur " + v.currentMs + " -> " + v.durationMs);
            v.currentMs = v.durationMs;
            save();
            /* Don't call updateActivity here. It sometimes causes the track
               that just finished to get rewound. */
            //updateActivity();
        }

        boolean enable = true;
        if (enable) {
            if (Log.ok) Log.i(TAG, "Beep...");
            MediaPlayer mp = MediaPlayer.create(this, R.raw.beep);
            mp.start();
            try {
                while (mp.isPlaying())
                    Thread.sleep(100);
            }
            catch (InterruptedException ex) {
                if (Log.ok) Log.i(TAG, "InterruptedException " + ex.getMessage());
            }
            mp.release();
            if (Log.ok) Log.i(TAG, "Beep...done");
        }

        Track t = Tracks.selectNext();
        if (t == null) {
            Log.i(TAG, "No track to play next");
            stop(false);
        }
        else {
            playingPath = t.pathName;
            playingTitle = t.title;
            seekMs = t.currentMs - OVERLAP_MS;
            if (seekMs < 0)
                seekMs = 0;
            if (Log.ok) Log.i(TAG, "Playing [" + Tracks.position + "] " +
                playingPath + " at " + seekMs);
            tryToGetAudioFocus();
            playNextSong();
        }
    }

    /** Called when media player is done preparing. */
    public void onPrepared(MediaPlayer player) {
        // The media player is done preparing, we can start playing
        if (Log.ok) Log.i(TAG, "onPrepared()");
        setState(State.Playing);
        if (playingTitle != null)
            updateNotification('"' + playingTitle + "\" (prepared)");
        if (Log.ok) Log.i(TAG, "onPrepared, seekMs=" + seekMs);
        if (seekMs != -1) {
            int barrier = player.getDuration() - BARRIER_MS;
            if (barrier < 0)
                barrier = 0;
            if (seekMs > barrier)
                seekMs = barrier;
        }
        if (seekMs > 0)
            player.seekTo(seekMs);
        else
            configAndStartMediaPlayer();
        seekMs = -1;
    }

    /** Updates the notification. */
    @SuppressWarnings("deprecation") // TODO: setLatestEventInfo is deprecated
    void updateNotification(String text) {
        if (Log.ok) Log.i(TAG, "updateNotification " + text);
        Intent ni = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                0, ni, PendingIntent.FLAG_UPDATE_CURRENT);
        ni.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mNotification.setLatestEventInfo(getApplicationContext(),
             getString(R.string.app_title), text, pi);
        mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    /**
     * Configures service as a foreground service.  A foreground service is a
     * service that's doing something the user is actively aware of (such as
     * playing music), and must appear to the user as a notification.  That's
     * why we create the notification here.
     */
    @SuppressWarnings("deprecation") // TODO: setLatestEventInfo is deprecated
    void setUpAsForeground(String text) {
        if (Log.ok) Log.i(TAG, "setUpAsForeground " + text);
        Intent ni = new Intent(getApplicationContext(), MainActivity.class);
        ni.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                0, ni, PendingIntent.FLAG_UPDATE_CURRENT);
        mNotification = new Notification();
        mNotification.tickerText = text;
        mNotification.icon = R.drawable.ic_stat_playing;
        mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
        mNotification.setLatestEventInfo(getApplicationContext(),
             getString(R.string.app_title), text, pi);
        startForeground(NOTIFICATION_ID, mNotification);
    }

    /**
     * Called when there's an error playing media.  When this happens, the
     * media player goes to the Error state.  We warn the user about the error
     * and reset the media player.
     */
    public boolean onError(MediaPlayer mp, int what, int extra) {
        toastLong("Media player error!");
        Log.e(TAG, "Error: what=" + String.valueOf(what) + ", extra="
                + String.valueOf(extra));

        setState(State.Stopped);
        relaxResources(true);
        giveUpAudioFocus();
        return true; // we handled the error
    }

    public void toastLong(String msg) {
        if (Log.ok) Log.i(TAG, "Toast: " + msg);
        Toast t = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    public void toastShort(String msg) {
        if (Log.ok) Log.i(TAG, "Toast: " + msg);
        Toast t = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    public void onGainedAudioFocus() {
        //toastShort("Gained audio focus");
        mAudioFocus = AudioFocus.Focused;

        // restart media player with new focus settings
        if (mState == State.Playing)
            configAndStartMediaPlayer();
    }

    public void onLostAudioFocus(boolean canDuck) {
        //toastShort(canDuck ? "Ducking" : "Can't duck");
        mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck
                : AudioFocus.NoFocusNoDuck;

        // start/restart/pause media player with new focus settings
        if (mPlayer != null && mPlayer.isPlaying())
            configAndStartMediaPlayer();
    }

    public void onSeekComplete(MediaPlayer mp) {
        if (Log.ok) Log.i(TAG, "Seek complete");
        configAndStartMediaPlayer();
    }

    @Override
    public void onDestroy() {
        if (Log.ok) Log.i(TAG, "onDestroy");
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
