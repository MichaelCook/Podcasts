package com.waxrat.podcasts;

import static java.lang.Integer.max;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionError;

import java.io.File;

public class MusicService
        extends MediaSessionService
        implements Player.Listener {

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
    public static final String ACTION_SET_SPEED = "com.waxrat.podcasts.action.SET_SPEED";
    public static final String SPEED_EXTRA = "speed";

    /**
     * Canonical playback speeds, slowest to fastest. Shared with MainActivity and TcpService.
     * 0.67f=1/1.5 is intended to undo the default 1.5x speedup of PodPoll/Feed.py (speedup=50%).
     */
    public static final float[] SPEEDS = {0.5f, 0.67f, 1.0f, 1.25f, 1.5f};

    private static final String SPEED_PREFS = "playback_speed";
    private static final String SPEED_KEY = "speed";

    /** When starting a track, backup this amount from where we left off. */
    public static final int OVERLAP_MS = 250;

    public static final String ACTION_PLAY_STATE = "com.waxrat.podcasts.intent.PLAY_STATE";

    // Build the intent to start playing a track, resuming from where it was
    // left off (rewound a little), or from the beginning if it's finished.
    @NonNull
    static Intent makePlayIntent(@NonNull Track t) {
        int curMs = 0;
        if (!t.isFinished()) {
            curMs = t.curMs - OVERLAP_MS;
            if (curMs < 0)
                curMs = 0;
        }
        Intent in = new Intent(ACTION_PLAY);
        in.setPackage(Utilities.PACKAGE);
        in.putExtra("ident", t.ident);
        in.putExtra("ms", curMs);
        return in;
    }
    private final Handler tickHandler = new Handler(Looper.getMainLooper());

    private static final int UPDATE_PERIOD_MS = 1000;

    private final Runnable activityUpdater = new Runnable() {
        public void run() {
            updateActivity();
            tickHandler.postDelayed(this, UPDATE_PERIOD_MS);
        }
    };

    private @Nullable androidx.media3.common.Player mPlayer = null;
    private @Nullable ExoPlayer mUnderlyingPlayer = null;

    // indicates the state our service:
    private enum State {
        Stopped,    // media player is stopped and not prepared to play
        Playing,    // playback active (media player ready)
        Paused,     // playback paused (media player ready)
    }

    // Written from the main thread, read from TcpService.ClientThread
    // background threads (e.g. playing()), so it must be volatile.
    private static volatile State mState = State.Stopped;

    /** Playback speed multiplier. Applied to the player whenever it is
        (re)created and whenever it changes. Volatile: read from TcpService's
        client threads. */
    private static volatile float sPlaybackSpeed = 1.0f;

    /** Get the current playback speed. */
    public static float getPlaybackSpeed() {
        return sPlaybackSpeed;
    }

    /** Get the persisted playback speed, for use at startup. */
    public static float getPersistedSpeed(@NonNull Context context) {
        return context.getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE)
                .getFloat(SPEED_KEY, 1.0f);
    }

    /** Persist the playback speed. */
    private void persistSpeed(float speed) {
        getSharedPreferences(SPEED_PREFS, MODE_PRIVATE)
                .edit().putFloat(SPEED_KEY, speed).apply();
    }

    private MediaSession mSession;
    private int mLastVolume = Integer.MIN_VALUE;

    static boolean playing() {
        return mState == State.Playing;
    }

    private void updateActivity() {
        if (mPlayer == null)
            return;
        Track t = mPlayingTrack;
        Intent i = new Intent(ACTION_PLAY_STATE);
        boolean isPlaying = playing();
        i.putExtra("play", isPlaying);
        if (t != null) {
            if (isPlaying)
                i.putExtra("ident", t.ident);
            if (!mPlayer.isPlaying()) {
                /*
                 * Happens when:
                 * - we press Play to start playing.
                 * - a track has just finished, and we're automatically starting the next.
                 */
                Log.i(TAG, "updateActivity: Not playing " + t);
            } else {
                long curMs = mPlayer.getCurrentPosition();
                if (curMs > t.durMs)
                    curMs = t.durMs;
                t.curMs = (int) curMs;

                TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, t.ident, String.valueOf(t.curMs));
            }
        }
        sendBroadcast(i);

        int stream = AudioManager.STREAM_MUSIC;
        int volume = mAudioManager.getStreamVolume(stream);
        if (volume != mLastVolume) {
            mLastVolume = volume;
            TcpService.broadcast(TcpService.NFY_VOLUME_UPDATED,
                    String.valueOf(volume),
                    String.valueOf(mAudioManager.getStreamMinVolume(stream)),
                    String.valueOf(mAudioManager.getStreamMaxVolume(stream)));
        }
    }

    private void setState(State newState) {
        if (mState == newState)
            return;
        mState = newState;
        if (mState == State.Stopped)
            mPlayingTrack = null;
        tickHandler.removeCallbacks(activityUpdater);
        if (mState == State.Playing)
            tickHandler.postDelayed(activityUpdater, 1000);
        updateActivity();
    }

    // The track currently playing
    private Track mPlayingTrack = null;

    // The ID we use for the notification
    private static final int NOTIFICATION_ID = 1;
    private static final String NOW_PLAYING_CHANNEL_ID = "now-playing-channel";

    @OptIn(markerClass = UnstableApi.class)
    private void createMediaPlayerIfNeeded() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
            mUnderlyingPlayer = null;
        }

        mUnderlyingPlayer = new ExoPlayer.Builder(this).build();
        // Hold a wake lock while playing so the CPU can't sleep mid-playback
        // when the screen is off. ExoPlayer acquires it on play and releases
        // it on pause, covering every resume path (app UI, notification,
        // Bluetooth, lock screen) -- unlike the hand-managed lock this
        // replaces, whose 10-minute acquire() timeout silently expired
        // mid-playback and was never re-acquired.
        mUnderlyingPlayer.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL);

        // Wrap the ExoPlayer in a custom player that intercepts seek commands
        mPlayer = new androidx.media3.common.ForwardingPlayer(mUnderlyingPlayer) {
            @Override
            public void play() {
                Log.d(TAG, "ForwardingPlayer.play");
                super.play();
                sendPlayPauseStatus();
            }

            @Override
            public void pause() {
                Log.d(TAG, "ForwardingPlayer.pause");
                super.pause();
                sendPlayPauseStatus();
            }

            @Override
            public void seekForward() {
                Log.d(TAG, "ForwardingPlayer.seekForward");
                skipForward();
            }

            @Override
            public void seekBack() {
                Log.d(TAG, "ForwardingPlayer.seekBack");
                skipBack();
            }

            @Override
            public void seekTo(long positionMs) {
                long currentPos = getCurrentPosition();
                long diff = positionMs - currentPos;
                //Log.d(TAG, "ForwardingPlayer.seekTo(" + positionMs + "), current=" + currentPos + ", diff=" + diff);

                // Check if this looks like a skip forward or skip back
                // Bluetooth might be calling seekTo with current + increment
                if (diff > 0 && Math.abs(diff - skipForwardMs) < 1000) {
                    // This looks like a skip forward command
                    Log.d(TAG, "Detected skip forward via seekTo");
                    skipForward();
                } else if (diff < 0 && Math.abs(-diff - skipBackwardMs) < 1000) {
                    // This looks like a skip back command
                    Log.d(TAG, "Detected skip back via seekTo");
                    skipBack();
                } else {
                    // Normal seek, let it through
                    Log.d(TAG, "Normal seekTo, passing through");
                    super.seekTo(positionMs);
                }
            }

            @Override
            public void seekToNext() {
                Log.d(TAG, "ForwardingPlayer.seekToNext");
                skipForward();
            }

            @Override
            public void seekToNextMediaItem() {
                Log.d(TAG, "ForwardingPlayer.seekToNextMediaItem");
                super.seekToNextMediaItem();
            }

            @Override
            public void seekToPrevious() {
                Log.d(TAG, "ForwardingPlayer.seekToPrevious");
                skipBack();
            }

            @Override
            public void seekToPreviousMediaItem() {
                Log.d(TAG, "ForwardingPlayer.seekToPreviousMediaItem");
                super.seekToPreviousMediaItem();
            }

            @Override
            public boolean isCommandAvailable(int command) {
                // Force SEEK_TO_NEXT and SEEK_TO_PREVIOUS to always be available
                // We're using them for skip forward/back within a track
                if (command == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT ||
                    command == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS) {
                    //Log.d(TAG, "isCommandAvailable(" + command + ") = TRUE (forced)");
                    return true;
                }

                return super.isCommandAvailable(command);
            }

            @Override
            public @NonNull androidx.media3.common.Player.Commands getAvailableCommands() {
                androidx.media3.common.Player.Commands commands = super.getAvailableCommands();

                // Force SEEK_TO_NEXT and SEEK_TO_PREVIOUS to be available
                // even if there's only one track (we're using them for skip forward/back)
                return commands.buildUpon()
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                        .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                        .build();
            }

            @Override
            public long getSeekBackIncrement() {
                //Log.d(TAG, "getSeekBackIncrement() = " + skipBackwardMs);
                return skipBackwardMs;
            }

            @Override
            public long getSeekForwardIncrement() {
                //Log.d(TAG, "getSeekForwardIncrement = " + skipForwardMs);
                return skipForwardMs;
            }
        };

        mPlayer.addListener(this);
        mPlayer.setPlaybackSpeed(sPlaybackSpeed);

    }

    private BroadcastReceiver mMediaButtonReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MusicService.onCreate");
        sPlaybackSpeed = getPersistedSpeed(this);

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();

        createMediaPlayerIfNeeded();
        assert mPlayer != null;

        // Create a MediaSession with callback that explicitly enables seek commands
        mSession = new MediaSession.Builder(this, mPlayer)
                .setCallback(mMediaSessionCallback)
                .build();
        Log.d(TAG, "MediaSession created");

        // Register broadcast receiver for media button events
        mMediaButtonReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                android.view.KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent.class);
                if (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "MEDIA_BUTTON received - KeyCode: " + event.getKeyCode());

                    switch (event.getKeyCode()) {
                        case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                            Log.d(TAG, "FAST_FORWARD button");
                            skipForward();
                            abortBroadcast();
                            break;
                        case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                            Log.d(TAG, "REWIND button");
                            skipBack();
                            abortBroadcast();
                            break;
                        case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                            Log.d(TAG, "NEXT button");
                            break;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            Log.d(TAG, "PREVIOUS button");
                            break;
                        default:
                            Log.d(TAG, "Other button: " + event.getKeyCode());
                            break;
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mMediaButtonReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private final MediaSession.Callback mMediaSessionCallback = new MediaSession.Callback() {
        @OptIn(markerClass = UnstableApi.class)
        @Override
        public @NonNull androidx.media3.session.MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            //Log.d(TAG, "MediaSession.Callback.onConnect: " + controller.getPackageName());

            // Build available session commands
            androidx.media3.session.SessionCommands sessionCommands =
                new androidx.media3.session.SessionCommands.Builder()
                    .build();

            // Build available player commands - explicitly include seek commands
            androidx.media3.common.Player.Commands playerCommands =
                new androidx.media3.common.Player.Commands.Builder()
                    .addAll(
                        androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
                        androidx.media3.common.Player.COMMAND_PREPARE,
                        androidx.media3.common.Player.COMMAND_STOP,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                        androidx.media3.common.Player.COMMAND_SEEK_BACK,
                        androidx.media3.common.Player.COMMAND_SEEK_FORWARD,
                        androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH,
                        androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE,
                        androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE,
                        androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_GET_TIMELINE,
                        androidx.media3.common.Player.COMMAND_GET_METADATA,
                        androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        androidx.media3.common.Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                        androidx.media3.common.Player.COMMAND_GET_VOLUME,
                        androidx.media3.common.Player.COMMAND_GET_DEVICE_VOLUME,
                        androidx.media3.common.Player.COMMAND_SET_VOLUME,
                        androidx.media3.common.Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        androidx.media3.common.Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                        androidx.media3.common.Player.COMMAND_SET_VIDEO_SURFACE,
                        androidx.media3.common.Player.COMMAND_GET_TRACKS
                    )
                    .build();

            return androidx.media3.session.MediaSession.ConnectionResult.accept(
                sessionCommands, playerCommands);
        }

        @OptIn(markerClass = UnstableApi.class)
        @NonNull
        @Override
        public com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull androidx.media3.session.SessionCommand customCommand,
                @NonNull android.os.Bundle args) {
            Log.d(TAG, "MediaSession.Callback.onCustomCommand: " + customCommand.customAction);

            // Try to handle it as a skip command
            if ("SKIP_FORWARD".equals(customCommand.customAction) ||
                "android.intent.extra.SKIP_FORWARD".equals(customCommand.customAction)) {
                Log.d(TAG, "Handling SKIP_FORWARD custom command");
                skipForward();
                return com.google.common.util.concurrent.Futures.immediateFuture(
                        new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS));
            } else if ("SKIP_BACK".equals(customCommand.customAction) ||
                       "android.intent.extra.SKIP_BACK".equals(customCommand.customAction)) {
                Log.d(TAG, "Handling SKIP_BACK custom command");
                skipBack();
                return com.google.common.util.concurrent.Futures.immediateFuture(
                        new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS));
            }

            // Return error for unknown commands
            return com.google.common.util.concurrent.Futures.immediateFuture(
                    new androidx.media3.session.SessionResult(SessionError.ERROR_NOT_SUPPORTED));
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
        long end = t.durMs - 1000;
        if (end < 0)
            end = 0;
        seekTo(end);
    }

    private void play(Intent intent) {
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
        long seekMs;
        if (intent != null)
            seekMs = intent.getIntExtra("ms", t.curMs);
        else
            seekMs = t.curMs;
        Log.i(TAG, "play " + t + " at " + seekMs);
        playTrack(t, seekMs);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent == null) {
            Log.i(TAG, "onStartCommand: No intent");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (action == null) {
            Log.i(TAG, "onStartCommand: No action");
            return START_NOT_STICKY;
        }
        Log.i(TAG, "onStartCommand " + action);
        switch (action) {
            case ACTION_TOGGLE_PLAYBACK:
                if (mPlayer == null) {
                    play(intent);
                } else if (mPlayer.isPlaying()) {
                    pause();
                } else {
                    play(intent);
                }
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
            case ACTION_GET_STATUS:
                sendPlayPauseStatus();
                break;
            case ACTION_SET_SPEED:
                setPlaybackSpeed(intent.getFloatExtra(SPEED_EXTRA, 1.0f));
                break;
            default:
                Note.w(TAG, "Unexpected action " + action);
                break;
        }

        return START_NOT_STICKY;
    }

    private void sendPlayPauseStatus() {
        if (playing() && mPlayingTrack != null) {
            Log.i(TAG, "sendPlayPauseStatus: playing");
            TcpService.broadcast(TcpService.NFY_PLAYING, mPlayingTrack.ident,
                                 String.valueOf(mPlayingTrack.curMs));
        } else {
            Log.i(TAG, "sendPlayPauseStatus: paused");
            TcpService.broadcast(TcpService.NFY_PAUSED);
        }
        TcpService.broadcast(TcpService.NFY_SPEED_UPDATED, String.valueOf(sPlaybackSpeed));
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
            // Broadcast anyway: a client with a stale "playing" belief
            // (it missed our NFY_PAUSED) corrects itself on its next press.
            TcpService.broadcast(TcpService.NFY_PAUSED);
            return;
        }
        Log.i(TAG, "pause");
        setState(State.Paused);
        assert mPlayer != null;
        mPlayer.pause();
        relaxResources(false);
        Tracks.writeState(this, "pause");
        TcpService.broadcast(TcpService.NFY_PAUSED);
    }

    private void seekTo(long ms) {
        if (mUnderlyingPlayer == null || mState == State.Stopped) {
            Log.i(TAG, "seek while stopped");
            return;
        }
        Log.v(TAG, "seek " + ms);
        // Call the underlying player directly to avoid triggering our ForwardingPlayer interceptor
        mUnderlyingPlayer.seekTo(ms);
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

    private void skipForward() {
        if (mUnderlyingPlayer == null || mState == State.Stopped) {
            Log.i(TAG, "forward while stopped");
            return;
        }
        Log.v(TAG, "forward");
        long curMs = mUnderlyingPlayer.getCurrentPosition();
        long durMs = mUnderlyingPlayer.getDuration();
        long barrierMs = durMs - BARRIER_MS;
        if (barrierMs <= 0)
            return; // track is not longer than the barrier
        if (curMs >= barrierMs)
            return; // current position is already past the barrier
        long newMs = curMs + skipForwardMs;
        if (newMs > barrierMs)
            newMs = barrierMs;
        seekTo(newMs);
    }

    private void skipBack() {
        if (mUnderlyingPlayer == null || mState == State.Stopped) {
            Log.i(TAG, "rewind while stopped");
            return;
        }
        Log.i(TAG, "rewind");
        long curMs = mUnderlyingPlayer.getCurrentPosition();
        long newMs = curMs - skipBackwardMs;
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
        Tracks.writeState(this, "stop");
        stopSelf();
    }

    private void relaxResources(boolean releaseMediaPlayer) {
        // Do NOT stop being a foreground service on pause. Android 17's
        // Background Audio Hardening requires a foreground service with
        // while-in-use (WIU) capabilities for audio focus requests, and WIU
        // is only granted when the service is started while the app is visible
        // or in response to a user-initiated operation. Dropping foreground on
        // pause loses WIU, so a resume while locked gets its focus request
        // denied (silently -- see HardeningEnforcer in dumpsys audio).
        // Only stop foreground when we're really stopping.
        if (releaseMediaPlayer) {
            // stop being a foreground service
            stopForeground(STOP_FOREGROUND_REMOVE);

            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
        }
    }

    private void configAndStartMediaPlayer() {
        if (mPlayer == null) {
            Log.w(TAG, "configAndStartMediaPlayer: no player");
            return;
        }

        // The player's volume is set to a default of 1.0f.
        // ExoPlayer automatically handles audio ducking.

        // Check if the player is ready and not already playing.
        if (mPlayer.getPlaybackState() != Player.STATE_READY) {
            Log.w(TAG, "configAndStartMediaPlayer: not ready");
            return;
        }
        if (mPlayer.isPlaying()) {
            Log.i(TAG, "configAndStartMediaPlayer: already playing");
            return;
        }
        Log.i(TAG, "configAndStartMediaPlayer: start playing");
        mPlayer.play();
    }

    private void playTrack(@NonNull Track t, long seekMs) {
        File audioFile = t.getAudioFile(this);

        skipForwardMs = t.skipForwardMs;
        skipBackwardMs = t.skipBackwardMs;

        try {
            createMediaPlayerIfNeeded();
            assert mPlayer != null;

            androidx.media3.common.AudioAttributes audioAttributes = new androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build();
            mPlayer.setAudioAttributes(audioAttributes, true);

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(audioFile.toURI().toString())
                    .setMediaId(t.ident)
                    .build();

            mPlayer.setMediaItem(mediaItem);
            mPlayer.prepare();
            setState(State.Playing);
            setUpAsForeground('"' + t.title + "\" (play)");

            if (seekMs != -1) {
                mPlayer.seekTo(seekMs);
            }

            mPlayingTrack = t;
            Tracks.selectTrackByIdent(mPlayingTrack.ident);
            Tracks.writeState(this, "playTrack");

            // Recreate the MediaSession with the updated player
            if (mSession != null) {
                mSession.release();
                mSession = null;
            }
            mSession = new MediaSession.Builder(this, mPlayer)
                    .setCallback(mMediaSessionCallback)
                    .build();

        } catch (Exception ex) {
            Note.e(TAG, "Exception playing next song", ex);
        }
        TcpService.broadcast(TcpService.NFY_PLAYING, t.ident, String.valueOf(t.curMs));
    }

    /** Set the playback speed, applying it to the player if there is one. */
    private void setPlaybackSpeed(float speed) {
        sPlaybackSpeed = speed;
        persistSpeed(speed);
        if (mPlayer != null)
            mPlayer.setPlaybackSpeed(speed);
        Log.i(TAG, "setPlaybackSpeed " + speed);
        // Keep MainActivity's speed button in sync when the speed was changed
        // elsewhere (e.g., from QtEdPod via TcpService).
        Intent i = new Intent(ACTION_PLAY_STATE);
        i.putExtra(SPEED_EXTRA, speed);
        sendBroadcast(i);
        // Keep connected QtEdPod clients in sync no matter where the change
        // originated (phone button or a TcpService client).
        TcpService.broadcast(TcpService.NFY_SPEED_UPDATED, String.valueOf(speed));
    }

    @SuppressWarnings("SameParameterValue")
    private void setAfterTrack(MainActivity.AfterTrack afterTrack) {
        Intent i = new Intent(ACTION_PLAY_STATE);
        i.putExtra("setAfterTrack", afterTrack);
        sendBroadcast(i);
    }

    private void playNextTrack() {
        Track track = Tracks.currentTrack();
        if (track == null) {
            Note.w(TAG, "onCompletion: no current track");
        } else {
            track.curMs = track.durMs;
            Tracks.writeState(this, "onCompletion");
            TcpService.broadcast(TcpService.NFY_TRACK_FINISHED, track.ident);
        }

        Track nextTrack = null;
        switch (MainActivity.mAfterTrack) {
            case FIRST:
                nextTrack = Tracks.pickFirst();
                break;
            case STOP:
                setAfterTrack(MainActivity.AfterTrack.FIRST);
                stop();
                nextTrack = Tracks.pickNext();
                if (nextTrack != null) {
                    Tracks.selectTrackByIdent(nextTrack.ident);
                    Tracks.restore(this, true);
                    TcpService.broadcast(TcpService.NFY_TRACK_SELECTED, nextTrack.ident);
                }
                return;
            case NEXT:
                nextTrack = Tracks.pickNext();
                break;
        }

        if (nextTrack == null) {
            Log.i(TAG, "No track to play now, stopping");
            stop();
            return;
        }

        long seekMs = max(0, nextTrack.curMs - OVERLAP_MS);
        Log.i(TAG, "Will play " + nextTrack + " at " + seekMs);
        playTrack(nextTrack, seekMs);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayer == null) {
            Log.w(TAG, "onPlaybackStateChanged: no player");
            return;
        }
        switch (playbackState) {
            case Player.STATE_IDLE:
                Log.i(TAG, "onPlaybackStateChanged: idle");
                setState(State.Stopped);
                break;
            case Player.STATE_BUFFERING:
                Log.i(TAG, "onPlaybackStateChanged: buffering");
                // No need to set the state here. Let onIsPlayingChanged handle it.
                break;
            case Player.STATE_READY:
                Log.i(TAG, "onPlaybackStateChanged: ready");
                configAndStartMediaPlayer();
                break;
            case Player.STATE_ENDED:
                Log.i(TAG, "onPlaybackStateChanged: ended");
                playNextTrack();
                break;
            default:
                Log.w(TAG, "onPlaybackStateChanged: unknown state " + playbackState);
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            Log.i(TAG, "onIsPlayingChanged: playing");
            setState(State.Playing);
        } else {
            Log.i(TAG, "onIsPlayingChanged: paused");
            setState(State.Paused);
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        Note.toastLong(this, "Media player error!");
        Note.e(TAG, "Error: " + error.getMessage());
        setState(State.Stopped);
        relaxResources(true);
    }

    private void setUpAsForeground(@NonNull String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOW_PLAYING_CHANNEL_ID);
        b.setContentTitle("Podcasts");
        b.setContentText(text);
        b.setSmallIcon(R.drawable.ic_stat_playing);

        Intent ni = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                0, ni, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ni.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(pi);
        startForeground(NOTIFICATION_ID, b.build());
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOW_PLAYING_CHANNEL_ID,
                "Now Playing",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("The podcast track currently playing");
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private AudioManager mAudioManager;

    @Override
    public void onDestroy() {
        if (mMediaButtonReceiver != null) {
            unregisterReceiver(mMediaButtonReceiver);
            mMediaButtonReceiver = null;
        }
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        tickHandler.removeCallbacks(activityUpdater);
        setState(State.Stopped);
        relaxResources(true);
        super.onDestroy();
    }

    /**
     * This is the recommended way to provide your MediaSession to the framework.
     * MediaSessionService calls this method when a client wants to connect.
     * You no longer need to manually implement onBind.
     */
    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        //Log.e(TAG, "onGetSession called for controller: " + controllerInfo.getPackageName());
        return mSession;
    }
}
