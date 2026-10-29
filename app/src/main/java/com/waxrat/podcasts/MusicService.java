package com.waxrat.podcasts;

import static java.lang.Integer.max;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
    public static final String ACTION_SET_SLEEP_TIMER = "com.waxrat.podcasts.action.SET_SLEEP_TIMER";
    public static final String ACTION_USER_INTERACTION = "com.waxrat.podcasts.action.USER_INTERACTION";
    public static final String SLEEP_TIMER_MINUTES_EXTRA = "sleep_timer_minutes";

    /**
     * Canonical playback speeds, slowest to fastest. Shared with MainActivity and TcpService.
     * 0.67f=1/1.5 is intended to undo the default 1.5x speedup of PodPoll/Feed.py (speedup=50%).
     */
    public static final float[] SPEEDS = {0.5f, 0.67f, 1.0f, 1.25f, 1.5f};

    private static final String SPEED_PREFS = "playback_speed";
    private static final String SPEED_KEY = "speed";
    private static final String SLEEP_TIMER_PREFS = "sleep_timer";
    private static final String SLEEP_TIMER_KEY = "minutes";
    private static final String SLEEP_TIMER_LAST_KEY = "last_minutes";

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

    /** Minutes of inactivity before the sleep timer pauses playback, 0 = off.
        Volatile: read from MainActivity's UI thread. */
    private static volatile int sSleepTimerMinutes = 0;

    /** Wall-clock time of the last user interaction, 0 = none yet. */
    private static volatile long sLastInteractionMs = 0;

    /** Playback position at the last user interaction. */
    private static volatile int sLastInteractionCurMs = 0;

    /** Track the last interaction belonged to, null = none yet. */
    private static volatile @Nullable String sLastInteractionIdent = null;

    /** Wall-clock time when the sleep timer's current countdown ends, or 0
        when no countdown is pending. */
    private static volatile long sSleepTimerDeadlineMs = 0;

    /** Get the persisted sleep timer setting, for use at startup and by
        MainActivity's sleep timer menu.
        @param context to read the preferences from.
        @return the persisted setting in minutes, 0 = off. */
    public static int getPersistedSleepTimerMinutes(@NonNull Context context) {
        return context.getSharedPreferences(SLEEP_TIMER_PREFS, Context.MODE_PRIVATE)
                .getInt(SLEEP_TIMER_KEY, 0);
    }

    /** Persist the sleep timer setting.
        @param minutes the setting in minutes, 0 = off. */
    private void persistSleepTimerMinutes(int minutes) {
        getSharedPreferences(SLEEP_TIMER_PREFS, MODE_PRIVATE)
                .edit().putInt(SLEEP_TIMER_KEY, minutes).apply();
    }

    /** Get the last nonzero sleep timer setting, for face-down arming.
        @return the last nonzero setting in minutes, 10 if none recorded. */
    private int getLastSleepTimerMinutes() {
        return getSharedPreferences(SLEEP_TIMER_PREFS, MODE_PRIVATE)
                .getInt(SLEEP_TIMER_LAST_KEY, 10);
    }

    /** Persist the last nonzero sleep timer setting, so face-down arming
        can restore it after the timer was switched off.
        @param minutes the setting in minutes, nonzero. */
    private void persistLastSleepTimerMinutes(int minutes) {
        getSharedPreferences(SLEEP_TIMER_PREFS, MODE_PRIVATE)
                .edit().putInt(SLEEP_TIMER_LAST_KEY, minutes).apply();
    }

    /** Get the current sleep timer setting.
        @return the setting in minutes, 0 = off. */
    public static int sleepTimerMinutes() {
        return sSleepTimerMinutes;
    }

    /** Get the wall-clock time of the last user interaction.
        @return milliseconds since the epoch, 0 = none yet. */
    public static long lastInteractionMs() {
        return sLastInteractionMs;
    }

    /** Get the playback position at the last user interaction.
        @return the position in milliseconds. */
    public static int lastInteractionCurMs() {
        return sLastInteractionCurMs;
    }

    /** Get the track the last interaction belonged to.
        @return the track ident, null = none yet. */
    public static @Nullable String lastInteractionIdent() {
        return sLastInteractionIdent;
    }

    /** Milliseconds until the sleep timer stops playback, or -1 when no
        countdown is pending.
        @return the remaining time in milliseconds, or -1. */
    public static long sleepTimerRemainingMs() {
        if (sSleepTimerDeadlineMs <= 0)
            return -1;
        return Math.max(0L, sSleepTimerDeadlineMs - System.currentTimeMillis());
    }

    /** Apply a new sleep timer setting and persist it.
        @param minutes the setting in minutes, 0 = off. */
    private void setSleepTimer(int minutes) {
        sSleepTimerMinutes = minutes;
        persistSleepTimerMinutes(minutes);
        if (minutes > 0)
            persistLastSleepTimerMinutes(minutes);
        Log.i(TAG, "setSleepTimer " + minutes + " minutes");
        // Keep connected QtEdPod clients in sync no matter where the change
        // originated (phone menu or a TcpService client).
        TcpService.broadcast(TcpService.NFY_SLEEP_TIMER_UPDATED, String.valueOf(minutes));
        // Keep MainActivity's indicator in sync: nothing else repaints it
        // while paused, so a stale play-state broadcast could otherwise
        // leave it blank even though the timer is armed.
        Intent i = new Intent(ACTION_PLAY_STATE);
        sendBroadcast(i);
    }

    private final Runnable sleepTimerRunnable = this::onSleepTimerFired;

    /** Restart the inactivity countdown from now. Does nothing when the
        timer is off. */
    private void resetSleepTimer() {
        tickHandler.removeCallbacks(sleepTimerRunnable);
        if (sSleepTimerMinutes <= 0) {
            sSleepTimerDeadlineMs = 0;
            return;
        }
        sSleepTimerDeadlineMs = System.currentTimeMillis() + sSleepTimerMinutes * 60L * 1000L;
        tickHandler.postDelayed(sleepTimerRunnable, sSleepTimerMinutes * 60L * 1000L);
    }

    /** Record a user interaction: refresh the rewind point and restart the
        inactivity countdown. */
    private void noteUserInteraction() {
        Track t = mPlayingTrack;
        if (t != null) {
            sLastInteractionIdent = t.ident;
            sLastInteractionCurMs = t.curMs;
        }
        sLastInteractionMs = System.currentTimeMillis();
        resetSleepTimer();
    }

    /** Pause playback when the inactivity timer fires. The timer stays
        armed: the next interaction restarts the countdown. */
    private void onSleepTimerFired() {
        // The countdown is spent; the next interaction starts a new one.
        sSleepTimerDeadlineMs = 0;
        if (!playing())
            return;
        Log.i(TAG, "Sleep timer fired after " + sSleepTimerMinutes + " minutes without interaction");
        pause();
    }

    /** How long the phone must lie still and face down before that counts
        as being put down for the night. */
    private final static long FACE_DOWN_STILL_MS = 5000;

    /** Accelerometer Z below this (m/s^2) means face down. */
    private final static float FACE_DOWN_Z_THRESHOLD = -8.0f;

    /** Wall-clock time the current still face-down episode began, 0 = none. */
    private long mFaceDownSinceMs = 0;

    /** True once the current face-down episode has armed the timer. */
    private boolean mFaceDownTriggered = false;

    private SensorManager mSensorManager;

    /** Watch for the phone being put face down for the night: still and
        face down for FACE_DOWN_STILL_MS arms the sleep timer. The trigger
        fires once per episode — a later sample that is not still and face
        down re-arms it — so the countdown is not restarted over and over
        while the phone just lies there. */
    private final SensorEventListener mFaceDownListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float total = (float) Math.sqrt(x * x + y * y + z * z);
            boolean faceDown = z < FACE_DOWN_Z_THRESHOLD
                    && total >= 8.0f && total <= 11.0f;
            long now = System.currentTimeMillis();
            if (!faceDown) {
                mFaceDownSinceMs = 0;
                mFaceDownTriggered = false;
                return;
            }
            if (mFaceDownSinceMs == 0)
                mFaceDownSinceMs = now;
            if (!mFaceDownTriggered && now - mFaceDownSinceMs >= FACE_DOWN_STILL_MS) {
                mFaceDownTriggered = true;
                onFaceDown();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** The phone has lain still and face down long enough to count as put
        down for the night. Arm the sleep timer to the last nonzero
        setting if it is off, and restart the countdown from now either
        way: the user was awake when they put it down, so the countdown
        should run from then, not from an earlier interaction. */
    private void onFaceDown() {
        if (sSleepTimerMinutes == 0) {
            int minutes = getLastSleepTimerMinutes();
            Log.i(TAG, "Face down: arming sleep timer to " + minutes + " minutes");
            setSleepTimer(minutes);
        } else {
            Log.i(TAG, "Face down: restarting sleep timer countdown");
        }
        noteUserInteraction();
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

                TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, t.ident, String.valueOf(t.curMs), String.valueOf(sleepTimerRemainingMs()));
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
                // Resuming a finished track (e.g., after the sleep-timer
                // boundary pause) plays the next unfinished track
                // instead of replaying the finished one. Route through
                // the service's play path, deferred past this session
                // dispatch so playTrack's session rebuild is safe.
                // The interaction is still noted here: the posted play
                // does not go through onStartCommand.
                Track current = Tracks.currentTrack();
                if (current != null && current.isFinished()) {
                    Log.i(TAG, "ForwardingPlayer.play: current track finished, advancing");
                    noteUserInteraction();
                    tickHandler.post(new Runnable() {
                        public void run() {
                            MusicService.this.play(null);
                        }
                    });
                    return;
                }
                super.play();
                // The MediaSession drives the player directly for
                // earbud, notification, and lock-screen buttons, so
                // this is the only place those interactions are seen.
                // Internal starts and pauses use the underlying player
                // directly and do not come through here.
                noteUserInteraction();
                sendPlayPauseStatus();
            }

            @Override
            public void pause() {
                Log.d(TAG, "ForwardingPlayer.pause");
                super.pause();
                // See play() above.
                noteUserInteraction();
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
        sSleepTimerMinutes = getPersistedSleepTimerMinutes(this);

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
                            noteUserInteraction();
                            abortBroadcast();
                            break;
                        case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                            Log.d(TAG, "REWIND button");
                            skipBack();
                            noteUserInteraction();
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

        // Listen for the phone being put face down, to arm the sleep timer.
        // The accelerometer needs no permission, and the listener lives
        // only as long as the service, so it cannot arm anything while
        // the app is fully idle.
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null)
                mSensorManager.registerListener(mFaceDownListener, accelerometer,
                        SensorManager.SENSOR_DELAY_NORMAL);
        }
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
        boolean substituted = false;
        if (ident == null) {
            t = Tracks.currentTrack();
            if (t == null) {
                Note.toastLong(this, "play: no current track");
                return;
            }
            Log.i(TAG, "play: play current track " + t);
            // Resuming a finished track (e.g., after the sleep-timer
            // boundary pause) plays the next track instead of
            // replaying the finished one, following the After setting:
            // the first unfinished track when After is FIRST,
            // otherwise the next unfinished track. An explicitly
            // chosen track (ident) still restarts, so replay stays
            // possible.
            if (t.isFinished()) {
                Track next = MainActivity.mAfterTrack == MainActivity.AfterTrack.FIRST
                        ? Tracks.pickFirst() : Tracks.pickNext();
                if (next != null) {
                    Log.i(TAG, "play: current track finished, playing next " + next);
                    t = next;
                    substituted = true;
                } else {
                    Log.i(TAG, "play: current track finished, no unfinished track; replaying");
                }
            }
        } else {
            t = Tracks.findTrackByIdent(ident);
            if (t == null) {
                Note.toastLong(this, "play: no such track " + ident);
                return;
            }
        }
        Note.toastShort(this, t.artist);
        long seekMs;
        // An intent position belongs to the requested track, not to a
        // substituted next track.
        if (!substituted && intent != null)
            seekMs = intent.getIntExtra("ms", t.curMs);
        else
            seekMs = t.curMs;
        // A finished track restarts from the beginning, matching
        // makePlayIntent: playing it at its stored end position would
        // end instantly.
        if (t.isFinished())
            seekMs = 0;
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
            case ACTION_SET_SLEEP_TIMER:
                setSleepTimer(intent.getIntExtra(SLEEP_TIMER_MINUTES_EXTRA, 0));
                break;
            case ACTION_USER_INTERACTION:
                // Noted below, with the other user actions.
                break;
            default:
                Note.w(TAG, "Unexpected action " + action);
                break;
        }

        // User actions reset the inactivity timer. Deliberately not here:
        // status queries and automatic pauses, which must not keep the
        // timer alive.
        switch (action) {
            case ACTION_PLAY:
            case ACTION_PAUSE:
            case ACTION_TOGGLE_PLAYBACK:
            case ACTION_SEEK:
            case ACTION_SKIP_FORWARD:
            case ACTION_SKIP_BACK:
            case ACTION_SKIP_TO_TRACK_START:
            case ACTION_SKIP_TO_TRACK_END:
            case ACTION_SET_SPEED:
            case ACTION_SET_SLEEP_TIMER:
            case ACTION_USER_INTERACTION:
                noteUserInteraction();
                break;
            default:
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
        // Call the underlying player directly: the ForwardingPlayer
        // interceptor records a user interaction, and pauses from here
        // (timer fire, boundary, automatic) must not reset the timer.
        // User pauses already record via onStartCommand.
        mUnderlyingPlayer.pause();
        relaxResources(false);
        Tracks.writeState(this, "pause");
        TcpService.broadcast(TcpService.NFY_PAUSED);
    }

    private void seekTo(long ms) {
        // Record the position on the track itself, so a later play resumes
        // from here even when there is no live player to move (stopped) or
        // the player is paused: play reads the track's position, not the
        // player's, so a seek that only moved the player would be lost.
        Track t = Tracks.currentTrack();
        if (t != null) {
            t.curMs = (int) ms;
            Tracks.writeState(this, "seek");
            TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, t.ident, String.valueOf(ms), String.valueOf(sleepTimerRemainingMs()));
        }
        if (mUnderlyingPlayer == null || mState == State.Stopped) {
            Log.i(TAG, "seek while stopped");
            return;
        }
        Log.v(TAG, "seek " + ms);
        // Call the underlying player directly to avoid triggering our ForwardingPlayer interceptor
        mUnderlyingPlayer.seekTo(ms);
        updateActivity();
    }

    /** How much to advance. */
    private int skipForwardMs = 30000;

    /** How much to rewind. */
    private int skipBackwardMs = 10000;

    /** Never advance further than this far from the end of the track. */
    private final static int BARRIER_MS = 1000;

    private void skipForward() {
        // A skip attempt is a user interaction whether or not it moves:
        // reset the timer even at the barrier or while stopped. The
        // session-driven paths (earbud double-tap) come through here
        // without passing onStartCommand, so this is where they are seen.
        noteUserInteraction();
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
        // See skipForward above.
        noteUserInteraction();
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
        // Call the underlying player directly: the ForwardingPlayer
        // interceptor records a user interaction, and starts from here
        // include automatic ones (track advance) that must not reset
        // the timer. User starts already record via onStartCommand.
        mUnderlyingPlayer.play();
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

        // With the sleep timer armed, a track ending pauses playback:
        // no next track is selected and After is untouched, so
        // the track that just ended stays current and rewind to the last
        // interaction point remains available. Pausing keeps the
        // service in the foreground, so a later remote play can
        // resume while the phone is locked.
        if (sSleepTimerMinutes > 0) {
            Log.i(TAG, "Sleep timer armed: pausing at end of track");
            pause();
            return;
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
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(mFaceDownListener);
            mSensorManager = null;
        }
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
