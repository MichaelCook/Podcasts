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

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class MainActivity extends ListActivity implements OnClickListener,
        OnLongClickListener, OnSeekBarChangeListener {
    private final static String TAG = "Podcasts.MainActivity";

    private Button mPlayButton;
    private Button mForwardButton;
    private Button mRewindButton;
    private SeekBar mSeekBar;
    private TextView mRemainingText;
    private TextView mDownloadableIndicator;
    private TextView mAutoscrollIndicator;
    private Button mAfterTrackButton;
    private CheckBox mKeepScreenOnCheckBox;
    private TrackArrayAdapter mTrackArrayAdapter;

    private final static String AFTER_TRACK_PREF = "afterTrack";
    private final static String AUTO_DOWNLOAD_ON_WIFI_PREF = "autoDownloadOnWifi";

    /* What to do at the end of the current track */
    enum AfterTrack {
        FIRST,   // start playing the first (unfinished) track
        STOP,    // stop playing
        NEXT,    // start playing the next (unfinished) track
    }

    static AfterTrack mAfterTrack = AfterTrack.FIRST;

    private @Nullable ReceiveMessages mReceiver = null;

    private boolean mAutoScroll = true;

    private void setAutoScrollForce(boolean enable) {
        mAutoScroll = enable;
        Log.d(TAG, "auto-scroll: " + enable);
        mAutoscrollIndicator.setText(enable ? "" : "no autoscroll");
    }

    private void setAutoScroll(boolean enable) {
        if (enable != mAutoScroll)
            setAutoScrollForce(enable);
    }

    // ---- download status indicator ----

    private @NonNull String mDownloadStatus = Downloader.IDLE_STATUS;
    private int mDownloadTrackNum = -1;
    private int mDownloadNumTracks = -1;
    private String mDownloadIdent = null;

    private void setDownloadable() {
        Log.d(TAG, "setDownloadable " + mDownloadStatus + " " + mDownloadTrackNum +
              " " + mDownloadNumTracks + " " + mDownloadIdent);
        String msg = "";
        switch (mDownloadStatus) {
        case Downloader.POLLING_STATUS:
            msg = "polling";
            break;
        case Downloader.DOWNLOADING_STATUS:
            msg = "downloading " + mDownloadTrackNum + " of " + mDownloadNumTracks;
            break;
        case Downloader.IDLE_STATUS: {
            int num = Tracks.numDownloadable();
            if (num != 0)
                msg = num + " downloadable";
            break;
        }
        default:
            Note.e(TAG, "setDownloadable: " + mDownloadStatus);
            msg = "error";
            break;
        }
        mDownloadableIndicator.setText(msg);
    }

    // ---- seek bar ----

    private void clearSeekBar() {
        mSeekBar.setMax(0);
        mSeekBar.setProgress(0);
        mRemainingText.setText(getString(R.string.no_remaining_time));
    }

    private void setSeekBar() {
        Track t = Tracks.currentTrack();
        if (t == null) {
            clearSeekBar();
            return;
        }
        if (t.durMs <= 0) {
            clearSeekBar();
            return;
        }
        mSeekBar.setMax(t.durMs);
        mSeekBar.setProgress(t.curMs);
        mRemainingText.setText(Utilities.mmss(t.remMs()));
        if (mTrackArrayAdapter != null)
            mTrackArrayAdapter.notifyDataSetChanged();
    }

    private void showTrack() {
        if (!mAutoScroll) {
            Log.d(TAG, "Not auto-scrolling");
            return;
        }
        int pos = Tracks.currentPosition();
        if (pos == -1)
            return;
        getListView().smoothScrollToPositionFromTop(pos, 0);
    }

    private @Nullable Track lastTrack = null;

    private void onPlayStateIntent(@NonNull Intent intent) {
        Tracks.restore(this, false);

        if (intent.getBooleanExtra("setListItems", false)) {
            setListItems();
            return;
        }

        Serializable ser = intent.getSerializableExtra("setAfterTrack", Serializable.class);
        if (ser != null) {
            setAfterTrack((AfterTrack) ser);
            return;
        }

        if (intent.getBooleanExtra("show", false)) {
            showTrack();
            return;
        }
        String ident = intent.getStringExtra("ident");
        if (ident != null)
            Tracks.selectTrackByIdent(ident);
        setPlayPauseImage();
        setSeekBar();

        Track t = Tracks.currentTrack();
        if (lastTrack != t) {
            lastTrack = t;
            showTrack();
        }
    }

    private void askTo(@NonNull String which) {
        Intent in = new Intent(which);
        in.setPackage(Utilities.PACKAGE);
        startService(in);
    }

    private void onDownloadStatus(@NonNull Intent intent) {
        String status = intent.getStringExtra(Downloader.STATUS_STATUS_EXTRA);
        if (status == null) {
            Note.w(TAG, "No STATUS_STATUS_EXTRA");
            return;
        }
        mDownloadStatus = status;
        mDownloadTrackNum = intent.getIntExtra(Downloader.TRACK_NUM_STATUS_EXTRA, -1);
        mDownloadNumTracks = intent.getIntExtra(Downloader.NUM_TRACKS_STATUS_EXTRA, -1);
        mDownloadIdent = intent.getStringExtra(Downloader.IDENT_EXTRA);
        setDownloadable();
    }

    class ReceiveMessages extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            String action = intent.getAction();
            if (action == null)
                return;
            //Log.i(TAG, "Receiver! " + action);
            switch (action) {
                case MusicService.ACTION_PLAY_STATE:
                    onPlayStateIntent(intent);
                    break;
                case Downloader.ACTION_DOWNLOAD_MESSAGE: {
                    String msg = intent.getStringExtra("message");
                    if (msg != null)
                        Note.toastLong(context, msg);
                    break;
                }
                case Downloader.ACTION_DOWNLOAD_STATUS:
                    onDownloadStatus(intent);
                    break;
                case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                    // e.g., headphones removed
                    Note.toastShort(context, "Noise!");
                    askTo(MusicService.ACTION_MAYBE_PAUSE);
                    break;
                case TelephonyManager.ACTION_PHONE_STATE_CHANGED:
                    onCallState(intent);
                    break;
                case Tracks.ACTION_TRACKS_LIST_CHANGED:
                    Tracks.copyTracks(viewedTracks);
                    setDownloadable();
                    setListItems();
                    break;
                default:
                    Note.e(TAG, "Unexpected action: " + action);
                    break;
            }
        }
    }

    private boolean mIsDriving = false;

    private void setDriving(boolean enable) {
        mIsDriving = enable;
        mKeepScreenOnCheckBox.setChecked(enable);
    }

    private void onCallState(@NonNull Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null)
            return;
        String state = extras.getString(TelephonyManager.EXTRA_STATE);
        if (state == null)
            return;

        // state:
        // TelephonyManager.EXTRA_STATE_IDLE;
        // TelephonyManager.EXTRA_STATE_OFFHOOK;
        // TelephonyManager.EXTRA_STATE_RINGING;
        if (state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            Note.toastShort(this, "Ringing");
            // send an intent to our MusicService to telling it to pause the audio
            askTo(MusicService.ACTION_PAUSE);
        }
    }

    private boolean isLandscape() {
        return Configuration.ORIENTATION_LANDSCAPE == getResources().getConfiguration().orientation;
    }

    private void setKeepScreenOn() {
        boolean kso = MusicService.playing() && mKeepScreenOnCheckBox.isChecked();
        mPlayButton.setKeepScreenOn(kso);
    }

    private int lastPlaying = -1;

    private void setPlayPauseImage() {
        boolean playing = MusicService.playing();
        int nowPlaying = playing ? 1 : 0;
        if (nowPlaying != lastPlaying) {
            lastPlaying = nowPlaying;
            mPlayButton.setBackgroundResource(playing ? R.drawable.btn_pause
                    : R.drawable.btn_play);
            setKeepScreenOn();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");

        setDriving(isLandscape());
        setKeepScreenOn();
        setAutoScrollForce(true);
        Tracks.restore(this, true);
        setDownloadable();
        setListItems();
        showTrack();

        /* As long as we're the foreground activity, the user can lock and
           unlock the screen by simply pressing the power button (no need
           to enter a PIN, for example). But if they lock then unlock the
           screen, then navigate away from this activity (to an activity
           that doesn't use these flags), then they will have to unlock
           the screen (e.g., enter their PIN). */
        setShowWhenLocked(true);

        if (mAskPermissions) {
            mAskPermissions = false;
            int requestCode = 1;  // passed to onRequestPermissionsResult but actually unused
            requestPermissions(mPermissionsToRequest, requestCode);
        }

        Downloader.downloadNow(this, "resume", false, -1, false, null);
    }

    private boolean mAskPermissions = true;
    private static final String[] mPermissionsToRequest = new String[] {
        Manifest.permission.READ_PHONE_STATE,
    };

    private boolean needAnyPermissions() {
        boolean needAny = false;
        for (String permission : mPermissionsToRequest) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                Note.w(TAG, "Permission not yet granted: " + permission);
                needAny = true;
            }
        }
        return needAny;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        for (int i = 0; i < permissions.length; ++i) {
            String permission = permissions[i];
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED)
                Log.i(TAG, "Granted: " + permission);
            else
                Note.w(TAG, "Denied: " + permission);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
        Tracks.writeState(this, "MainActivity.onPause");
    }

    /*
     * Activity life cycle:
     *
     * onCreate => onStart => onResume => (running) => onPause => onStop => onDestroy => (gone)
     *
     * http://developer.android.com/reference/android/app/Activity.html
     */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        setContentView(R.layout.main);

        mPlayButton = findViewById(R.id.playbutton);
        mForwardButton = findViewById(R.id.forwardbutton);
        mRewindButton = findViewById(R.id.rewindbutton);
        mSeekBar = findViewById(R.id.seekBar);
        mRemainingText = findViewById(R.id.remainingText);
        mDownloadableIndicator = findViewById(R.id.downloadable);
        mAutoscrollIndicator = findViewById(R.id.autoscroll);
        mAfterTrackButton = findViewById(R.id.afterTrack);
        mKeepScreenOnCheckBox = findViewById(R.id.keepScreenOn);

        mRemainingText.setOnClickListener(this);
        mRemainingText.setOnLongClickListener(this);
        mPlayButton.setOnClickListener(this);
        mPlayButton.setOnLongClickListener(this);
        mForwardButton.setOnClickListener(this);
        mRewindButton.setOnClickListener(this);
        mForwardButton.setOnLongClickListener(this);
        mRewindButton.setOnLongClickListener(this);
        mSeekBar.setOnSeekBarChangeListener(this);
        mAfterTrackButton.setOnClickListener(this);
        mKeepScreenOnCheckBox.setOnClickListener(this);
        mKeepScreenOnCheckBox.setOnLongClickListener(this);

        setAutoScrollForce(true);

        mAskPermissions = needAnyPermissions();

        mReceiver = new ReceiveMessages();
        registerReceiver(mReceiver, new IntentFilter(MusicService.ACTION_PLAY_STATE));
        registerReceiver(mReceiver, new IntentFilter(Downloader.ACTION_DOWNLOAD_MESSAGE));
        registerReceiver(mReceiver, new IntentFilter(Downloader.ACTION_DOWNLOAD_STATUS));
        registerReceiver(mReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_MEDIA_BUTTON));
        registerReceiver(mReceiver, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
        registerReceiver(mReceiver, new IntentFilter(Tracks.ACTION_TRACKS_LIST_CHANGED));

        SharedPreferences prefs = getPreferences(MODE_PRIVATE);

        String t = prefs.getString(AFTER_TRACK_PREF, AfterTrack.FIRST.name());
        mAfterTrack = AfterTrack.valueOf(t);
        setAfterTrackIcon();

        Downloader.mAutoDownloadOnWifi = prefs.getBoolean(AUTO_DOWNLOAD_ON_WIFI_PREF, true);
        setAutoDownloadOnWifiIndicator();
        Log.i(TAG, "onCreate: " + autoDownloadOnWifiLabel(Downloader.mAutoDownloadOnWifi));

        Downloader.schedule(this, "main");

        getListView().setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                /* onResume calls showTrack which then tends to cause FLING followed by IDLE.
                   We ignore those.  When the user does an actual fling, we'll get TOUCH_SCROLL too
                   which is enough for to know we should set mAutoScroll to false */
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL)
                    setAutoScroll(false);
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                //Log.d(TAG, "onScroll fv=" + firstVisibleItem + " v=" + visibleItemCount + " i=" + totalItemCount);
                // If the playing track is visible, enable autoscroll
                if (!mAutoScroll) {
                    int pos = Tracks.currentPosition();
                    if (pos >= firstVisibleItem && pos < firstVisibleItem + visibleItemCount)
                        setAutoScroll(true);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        unregisterReceiver(mReceiver);
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            askTo(TcpService.ACTION_TCP_BIND);
            Log.i(TAG, "onStart: Sent TCP_BIND");
        }
        catch (IllegalStateException exc) {
            // Not allowed to start service Intent: app is in background
            Note.w(TAG, "onStart: Couldn't send TCP_BIND: " + exc.getMessage());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    private void alert(@NonNull String title, @NonNull String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextIsSelectable(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(text);
        builder.setTitle(title);
        builder.setCancelable(true);
        builder.setNeutralButton("OK", (dialog, id) -> Log.i(TAG, "ok"));

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void confirm(@NonNull String title, @NonNull String message,
            @NonNull DialogInterface.OnClickListener yesAction) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(true);
        alert.setPositiveButton("Yes", yesAction);
        alert.setNegativeButton("No", (dialog, id) -> Log.i(TAG, "No!"));
        AlertDialog alertDialog = alert.create();
        alertDialog.show();
    }

    private void confirm(@NonNull String title, @NonNull DialogInterface.OnClickListener yesAction) {
        confirm(title, "Are you sure?", yesAction);
    }

    public void onClick(@NonNull View target) {
        // Send the correct intent to the MusicService, according to the button
        // that was clicked
        if (target == mPlayButton) {
            if (MusicService.playing()) {
                setListItems();
                askTo(MusicService.ACTION_PAUSE);
                return;
            }
            Track t = Tracks.currentTrack();
            if (t == null) {
                Note.toastLong(this, "No track");
                return;
            }
            playTrack(t, true);
            return;
        }
        if (target == mForwardButton) {
            askTo(MusicService.ACTION_SKIP_FORWARD);
            return;
        }
        if (target == mRewindButton) {
            askTo(MusicService.ACTION_SKIP_BACK);
            return;
        }
        if (target == mAfterTrackButton) {
            AfterTrack a = mAfterTrack;
            AfterTrack[] v = AfterTrack.values();
            a = v[(a.ordinal() + 1) % v.length]; // cycle through the choices
            setAfterTrack(a);
            return;
        }
        if (target == mRemainingText) {
            showMainMenu();
            return;
        }
        if (target == mKeepScreenOnCheckBox) {
            toastShort(mKeepScreenOnCheckBox.isChecked()
                    ? "Keep screen on while playing"
                    : "Don't keep screen on");
            setKeepScreenOn();
            return;
        }
        Note.w(TAG, "Unhandled click: " + target);
    }

    @NonNull
    private String setAfterTrackIcon()
    {
        int r;
        String s;
        switch (mAfterTrack) {
        case FIRST:
            r = R.drawable.btn_after_track_first;
            s = "After this track, play the first track";
            break;
        case STOP:
            r = R.drawable.btn_after_track_stop;
            s = "After this track, stop";
            break;
        case NEXT:
            r = R.drawable.btn_after_track_next;
            s = "After this track, play the next track";
            break;
        default:
            Note.w(TAG, "Oops: " + mAfterTrack);
            return "Oops";
        }
        mAfterTrackButton.setBackgroundResource(r);
        return s;
    }

    private Toast mCancelableToast;

    private void toastShort(@NonNull String msg) {
        if (mCancelableToast != null)
            mCancelableToast.cancel();
        mCancelableToast = Note.toastShort(this, msg);
    }

    private void setAfterTrack(AfterTrack a) {
        if (a == mAfterTrack)
            return;
        mAfterTrack = a;
        toastShort(setAfterTrackIcon());
        SharedPreferences.Editor ed = getPreferences(MODE_PRIVATE).edit();
        ed.putString(AFTER_TRACK_PREF, a.name());
        ed.apply();
    }

    public boolean onLongClick(@NonNull View target) {
        if (target == mForwardButton) {
            askTo(MusicService.ACTION_SKIP_TO_TRACK_END);
            return true; // consumed the click
        }
        if (target == mRewindButton) {
            askTo(MusicService.ACTION_SKIP_TO_TRACK_START);
            return true; // consumed the click
        }
        if (target == mRemainingText) {
            askPriorities();
            return true; // consumed the click
        }
        if (target == mPlayButton) {
            setAutoScroll(true);
            playFirstTrack();
            return true; // consumed the click
        }
        if (target == mKeepScreenOnCheckBox) {
            setAutoScroll(true);
            deleteFinished();
            return true; // consumed the click
        }
        Note.w(TAG, "Unhandled long click: " + target);
        return false; // didn't consume the click
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
        case KeyEvent.KEYCODE_HEADSETHOOK:
            askTo(MusicService.ACTION_TOGGLE_PLAYBACK);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void onProgressChanged(@NonNull SeekBar seekBar, int progress,
            boolean fromUser) {
        //Log.i(TAG, "onProgressChanged(" + progress + ',' + fromUser + ")");
    }

    public void onStartTrackingTouch(@NonNull SeekBar seekBar) {
        //Log.i(TAG, "onStartTrackingTouch");
    }

    public void onStopTrackingTouch(@NonNull SeekBar seekBar) {
        Intent in = new Intent(MusicService.ACTION_SEEK);
        in.setPackage(Utilities.PACKAGE);
        in.putExtra("where", seekBar.getProgress());
        startService(in);
    }

    /* A copy of Tracks.tracks.  We're only allowed to change the ArrayAdapter
       list from the main thread or else we may cause
       java.lang.IllegalStateException: "The content of the adapter has changed
       but ListView did not receive a notification. Make sure the content of
       your adapter is not modified from a background thread, but only from
       the UI thread. Make sure your adapter calls notifyDataSetChanged() when
       its content changes." */
    private static final List<Track> viewedTracks = new ArrayList<>();

    private class TrackArrayAdapter extends ArrayAdapter<Track> {
        private final LayoutInflater inflater = (LayoutInflater)
          MainActivity.this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        TrackArrayAdapter() {
            super(MainActivity.this, R.layout.track_list, Tracks.copyTracks(viewedTracks));
        }

        /* The number of different types of views returned by getView. */
        @Override
        public int getViewTypeCount() {
            return 1;
        }

        /* When tracks have been deleted, the ArrayAdapter thinks it has more
           rows than we have tracks.  We return IGNORE_ITEM_VIEW_TYPE for those
           extra rows. */
        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= Tracks.numTracks())
                return IGNORE_ITEM_VIEW_TYPE;
            return 0;
        }

        @NonNull
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View rowView = convertView;
            if (rowView == null)
                rowView = inflater.inflate(R.layout.track_list, parent, false);
            TextView percentView = rowView.findViewById(R.id.percentDone);
            TextView durationView = rowView.findViewById(R.id.duration);
            TextView nextQuietView = rowView.findViewById(R.id.nextQuiet);
            TextView titleView = rowView.findViewById(R.id.title);
            titleView.setTextSize(mIsDriving ? 36 : 24);
            Track t = Tracks.track(position);
            if (t == null) {
                titleView.setText("Position " + position + " of " + Tracks.numTracks());
                percentView.setText("");
                durationView.setText("");
                nextQuietView.setText("");
            }
            else {
                StringBuilder sb = new StringBuilder();
                sb.append(priorityGlyph(t.priorityClassChar())).append(' ');
                if (t.emoji != null && !t.emoji.isEmpty())
                    sb.append(t.emoji).append(' ');
                sb.append(t.title);
                // "🔹" U+1F539: SMALL BLUE DIAMOND
                sb.append(" \uD83D\uDD39 ");
                sb.append(t.artist);
                titleView.setText(sb.toString());

                if (mDownloadIdent != null && mDownloadIdent.equals(t.ident)) {
                    // "🌀" U+1F300: CYCLONE
                    percentView.setText("\uD83C\uDF00");
                    nextQuietView.setText("");
                }
                else if (!t.downloaded) {
                    // "🚩" U+1F6A9: TRIANGULAR FLAG ON POST
                    percentView.setText("\uD83D\uDEA9");
                    nextQuietView.setText("");
                }
                else if (t.isFinished()) {
                    percentView.setText("Done");
                    nextQuietView.setText("");
                }
                else if (t.curMs == 0) {
                    percentView.setText("");
                    nextQuietView.setText(nextQuiet(t));
                }
                else {
                    double per = 100.0 * t.curMs / t.durMs;
                    percentView.setText(String.format(Locale.US, "%.0f%%", per));
                    nextQuietView.setText(nextQuiet(t));
                }

                durationView.setText(Utilities.mmss(t.durMs));
            }
            if (position != Tracks.currentPosition())
                rowView.setBackgroundColor(Color.BLACK);
            else if (position == 0)
                rowView.setBackgroundColor(Color.BLUE);
            else {
                final int DKGREEN = (255 << 24) | (15 << 16) | (89 << 8) | 35; // ARGB
                rowView.setBackgroundColor(DKGREEN);
            }
            return rowView;
        }
    }

    /* Time (as a string "0:00") until the next quiet period.
       Or the empty string if there is no next quiet period. */
    private static String nextQuiet(@NonNull Track t) {
        if (t.quiet != null)
            for (int q : t.quiet) {
                int ms = q - t.curMs;
                if (ms >= 0)
                    return Utilities.mmss(ms);
            }
        return "";
    }

    private void showTrackInfo(@NonNull Track t) {
        int position = Tracks.getPositionOfTrack(t);
        if (position == -1) {
            Note.toastLong(this, "No such track");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('"')
                .append(t.title)
                .append("\"\n\n")
                .append(t.ident)
                .append("\n\n");
        sb.append("Priority: \"").append(t.priority).append("\"\n\n");
        sb.append("Artist: \"").append(t.artist).append("\"\n\n");
        sb.append(Utilities.mmss(t.curMs))
                .append(" + ")
                .append(Utilities.mmss(t.remMs()))
                .append(" = ")
                .append(Utilities.mmss(t.durMs));

        int remMs = 0;
        switch (mAfterTrack) {
        case FIRST:
            /* Show how long until this track will finish playing if all lower-numbered
               tracks are played first. */
            for (int pos = 0; pos <= position; ++pos) {
                Track u = Tracks.track(pos);
                if (u != null)
                    remMs += u.remMs();
            }
            break;
        case NEXT:
            /* Show how long until the final track will finish playing */
            for (int pos = position; pos < Tracks.numTracks(); ++pos) {
                Track u = Tracks.track(pos);
                if (u != null)
                    remMs += u.remMs();
            }
            break;
        case STOP:
            /* Show how long until the current track will finish playing */
            remMs = t.remMs();
            break;
        }
        sb.append("\n\nFinish in ");
        sb.append(Utilities.hhmmss(remMs));
        sb.append(" on ");
        sb.append(Utilities.timestampStr(System.currentTimeMillis() + remMs));

        sb.append("\n\nDownloaded ").append(Utilities.timestampStr(t.when * 1000));

        alert("Track #" + (position + 1), sb.toString());
    }

    private void setListItems() {
        if (mTrackArrayAdapter == null) {
            mTrackArrayAdapter = new TrackArrayAdapter();
            setListAdapter(mTrackArrayAdapter);
        }
        ListView lv = getListView();
        lv.setOnItemLongClickListener((parent, view, position, id) -> {
            Track t = Tracks.track(position);
            if (t != null)
                playTrack(t, false);
            return true; // click consumed
        });
        setSeekBar();
    }

    private void playTrack(@NonNull Track t, boolean go) {
        if (MusicService.playing())
            go = true;

        if (!t.downloaded) {
            Note.toastLong(this, "Track is not yet downloaded");
            startDownload(t.ident, true);
            return;
        }

        if (!go) {
            Tracks.selectTrackByIdent(t.ident);
            setSeekBar();
            Tracks.writeState(this, "playTrack");
            TcpService.broadcast(TcpService.NFY_TRACK_SELECTED, t.ident);
            return;
        }
        int curMs = 0;
        /* If this track is "done", start from the beginning.
           Otherwise, start from where we left off last but rewound a little. */
        if (!t.isFinished()) {
            curMs = t.curMs - MusicService.OVERLAP_MS;
            if (curMs < 0)
                curMs = 0;
        }
        Intent in = new Intent(MusicService.ACTION_PLAY);
        in.setPackage(Utilities.PACKAGE);
        in.putExtra("ident", t.ident);
        in.putExtra("ms", curMs);
        startService(in);
    }

    @Override
    protected void onListItemClick(ListView list, View v, int position, long id) {
        showTrackMenu(position);
    }

    private void deleteFinished() {
        int numDeleted = Tracks.deleteFinished(this);
        Note.toastLong(this, "Deleted " + numDeleted);
        if (numDeleted != 0) {
            Tracks.restore(this, true);
            setSeekBar();
            askTo(MusicService.ACTION_UPDATE_METADATA);
        }
        showTrack();
    }

    private void rewindAll() {
        int numRewound = Tracks.rewindAll();
        toastShort("Rewound " + numRewound);
        Tracks.writeState(this, "rewindAll");
        setSeekBar();
    }

    private void askRewindAll() {
        int numRewindable = Tracks.numRewindable();
        if (numRewindable == 0)
            toastShort("None to rewind");
        else
            confirm(numRewindable == 1 ? "Rewind 1 track" : "Rewind " + numRewindable + " tracks",
                    (dialog, id) -> rewindAll());
    }

    private void startDownload(int maxTracks, boolean thenStart) {
        Downloader.downloadNow(this, "max-then", true, maxTracks, thenStart, null);
    }

    private void startDownload(@NonNull String onlyIdent, boolean thenStart) {
        Downloader.downloadNow(this, "only", true, -1, thenStart, onlyIdent);
    }

    @NonNull
    private static String priorityGlyph(int c) {
        if (c == -1)
            return "";
        if (c >= 'A' && c <= 'Z') {
            // 🅐 … 🅩 : NEGATIVE CIRCLED LATIN CAPITAL LETTER A-Z
            byte[] b = {        // UTF-8 encoding
                (byte) 0xF0,
                (byte) 0x9F,
                (byte) 0x85,
                (byte) (0x90 + (c & 0x1f) - 1)
            };
            return new String(b, StandardCharsets.UTF_8);
        }
        if (c == '=')
            return "\u29BF";    // ⦿ "CIRCLED BULLET"
        return String.valueOf(c);
    }

    private static void addPriorityItem(@NonNull ArrayList<CharSequence> pcs, int pcc, int numTracks,
                                        int remMs, int numDownloadable, int numFinished) {
        StringBuilder sb = new StringBuilder();
        sb.append(priorityGlyph(pcc));
        sb.append(' ').append(Utilities.hhmmss(remMs));
        sb.append(" [").append(numTracks - numDownloadable - numFinished);
        if (numDownloadable != 0)
            sb.append('+').append(numDownloadable);
        if (numFinished != 0)
            sb.append('-').append(numFinished);
        sb.append(']');
        pcs.add(sb.toString());
    }

    private static CharSequence[] makePriorityItems() {
        int numTracks = 0;
        int remMs = 0;
        int numDownloadable = 0;
        int numFinished = 0;
        int lastPcc = -1;
        ArrayList<CharSequence> pcs = new ArrayList<>();
        for (Track track : Tracks.copyTracks()) {
            int pcc = track.priorityClassChar();
            if (numTracks != 0 && pcc != lastPcc) {
                addPriorityItem(pcs, lastPcc, numTracks, remMs, numDownloadable, numFinished);
                numTracks = 0;
                remMs = 0;
                numDownloadable = 0;
                numFinished = 0;
            }
            numTracks++;
            remMs += track.remMs();
            if (!track.downloaded)
                numDownloadable++;
            if (track.isFinished())
                numFinished++;
            lastPcc = pcc;
        }
        if (numTracks != 0)
            addPriorityItem(pcs, lastPcc, numTracks, remMs, numDownloadable, numFinished);
        return pcs.toArray(new CharSequence[0]);
    }

    private static int findByGlyph(@NonNull String item) {
        // Find the index of the first track that's using this item's priority glyph
        List<Track> tracks = Tracks.copyTracks();
        int i = 0;
        for (Track track : Tracks.copyTracks()) {
            String pg = priorityGlyph(track.priorityClassChar());
            if (item.startsWith(pg))
                return i;
            i++;
        }
        return -1;
    }

    private void askPriorities() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle("Priorities");
        alert.setCancelable(true);
        CharSequence[] items = makePriorityItems();
        alert.setSingleChoiceItems(items, 0,
                (dialog, which) -> {
                    int pos = findByGlyph(items[which].toString());
                    if (pos != -1) {
                        setAutoScroll(false);
                        getListView().smoothScrollToPositionFromTop(pos, 0);
                    }
                    dialog.dismiss();
                });
        alert.show();
    }

    private void maybeAskDownload(int maxTracks, boolean thenStart) {
        if (Downloader.isRunning()) {
            toastShort("Already checking");
            return;
        }
        if (!Downloader.isWifi(this)) {
            confirm("Download", "Not on Wi-Fi, are you sure?",
                    (dialog, id) -> startDownload(maxTracks, thenStart));
            return;
        }
        startDownload(maxTracks, thenStart);
    }

    private void drivingDownload(int maxTracks) {
        if (Downloader.isRunning()) {
            toastShort("Already checking");
            return;
        }
        startDownload(maxTracks, true);
    }

    private void downloadNone() {
        if (Downloader.isRunning()) {
            toastShort("Already checking");
            return;
        }
        startDownload(0, false);
    }

    private boolean isPlaying(@NonNull Track track) {
        return MusicService.playing() && track == Tracks.currentTrack();
    }

    private void askDeleteTrack(@NonNull Track track) {
        if (isPlaying(track)) {
            toastShort("Can't delete the playing track");
            return;
        }

        confirm("Delete \"" + track.title + '"',
                (dialog, id) -> {
                    // Currently playing track may have changed since we checked above...
                    if (isPlaying(track)) {
                        toastShort("Can't delete the playing track");
                        return;
                    }
                    if (track.downloaded) {
                        track.deleteFiles(this);
                        Tracks.restore(this, true);
                    }
                    else
                        Downloader.deleteTrack(this, "ask", track.ident);
                });
    }

    private void askRewindTrack(@NonNull Track track) {
        if (track.curMs == 0) {
            toastShort("Track is already rewound");
            return;
        }
        confirm("Rewind \"" + track.title + '"',
                (dialog, id) -> {
                    if (isPlaying(track)) {
                        Log.i(TAG, "Rewind currently playing track");
                        askTo(MusicService.ACTION_SKIP_TO_TRACK_START);
                    }
                    else {
                        Tracks.seek(this, track, 0);
                    }
                });
    }

    private void askMoveTrackToTop(@NonNull Track track) {
        confirm("Move \"" + track.title + "\" to top",
                (dialog, id) -> {
                    if (Tracks.getPositionOfTrack(track) == -1) {
                        // The track got deleted while the dialog was open
                        Note.toastLong(this, "No such track");
                        return;
                    }
                    Tracks.moveToTop(this, track);
                    Tracks.restore(this, true);
                    setListItems();
                });
    }

    /*
        +--
        |Filesystem       1K-blocks     Used Available Use% Mounted on
        |/dev/block/dm-37 114407404 22094120  92182212  20% /storage/emulated/0/Android/obb
        +--
     */
    private static final Pattern DISK_FREE_RE = Pattern.compile(" (\\d+) +(\\d+)% ");

    @NonNull
    private String diskFree() {
        String free = Utilities.capture("df " + Utilities.getFolder(this));
        if (free == null) {
            Note.w(TAG, "df command failed");
            return "(df failed)";
        }
        Matcher match = DISK_FREE_RE.matcher(free);
        if (!match.find()) {
            Note.w(TAG, "No match: " + free);
            return "(no match)";
        }
        String g = match.group(1);
        long kb = g != null ? Long.parseLong(g) : -1;

        g = match.group(2);
        int usedPercent = g != null ? Integer.parseInt(g) : -1;

        final long M = 1024 * 1024;
        return String.format(Locale.US, "%d%% %dGB", 100 - usedPercent, (kb + M / 2) / M);
    }

    private void showStatus() {
        Log.i(TAG, "showStatus...");
        StringBuilder sb = new StringBuilder();

        sb.append("IP address: ").append(Utilities.getIpAddress());
        sb.append("\nClients: ").append(TcpService.numClients());
        sb.append("\nTracks: ").append(Tracks.numTracks());
        sb.append("\nDownloadable: ").append(Tracks.numDownloadable());
        sb.append("\nHours remaining: ").append(Utilities.hhmmss(Tracks.remMs()));

        sb.append("\nDisk free: ").append(diskFree());

        sb.append("\nBuilt: ");
        sb.append(Utilities.timestampStr(BuildConfig.BUILD_TIME));

        alert("Status", sb.toString());
        Log.i(TAG, "showStatus...done");
    }

    @NonNull
    static String autoDownloadOnWifiLabel(boolean enabled) {
        return enabled
                ? "Auto download on Wi-Fi"
                : "No auto download";
    }

    void setAutoDownloadOnWifiIndicator() {
        mRemainingText.setTextColor(Downloader.mAutoDownloadOnWifi ? Color.WHITE : Color.RED);
    }

    void setAutoDownloadOnWifi(boolean enable) {
        Downloader.mAutoDownloadOnWifi = enable;
        SharedPreferences.Editor ed = getPreferences(MODE_PRIVATE).edit();
        ed.putBoolean(AUTO_DOWNLOAD_ON_WIFI_PREF, enable);
        ed.apply();
        setAutoDownloadOnWifiIndicator();
        Note.toastLong(this, autoDownloadOnWifiLabel(Downloader.mAutoDownloadOnWifi));
    }

    void playFirstTrack() {
        Track t = Tracks.pickFirst();
        if (t != null)
            playTrack(t, true);
    }

    private static final int
        MAIN_MENU_PLAY_FIRST_TRACK = 0,
        MAIN_MENU_DOWNLOAD_NONE = 1,
        MAIN_MENU_DOWNLOAD_ALL = 2,
        MAIN_MENU_DOWNLOAD_ALL_AND_PLAY = 3,
        MAIN_MENU_PRIORITIES = 4,
        MAIN_MENU_DELETE_FINISHED = 5,
        MAIN_MENU_REWIND_ALL = 6,
        MAIN_MENU_AUTO_DOWNLOAD_ON_WIFI = 7,
        MAIN_MENU_STATUS = 8,
        MAIN_MENU_LAUNCH_BROWSER = 9,
        MAIN_MENU_SIZE = 10;

    private void showMainMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Podcasts");
        builder.setCancelable(true);

        CharSequence[] mi = new CharSequence[MAIN_MENU_SIZE];
        mi[MAIN_MENU_DELETE_FINISHED] = Tracks.anyFinishedDeletable()
            ? "Delete finished" : "(Delete finished)";
        mi[MAIN_MENU_DOWNLOAD_ALL] = "Download all";
        mi[MAIN_MENU_DOWNLOAD_ALL_AND_PLAY] = "Download all and play";
        mi[MAIN_MENU_DOWNLOAD_NONE] = "Download none";
        mi[MAIN_MENU_REWIND_ALL] = Tracks.numRewindable() != 0
            ? "Rewind all" : "(Rewind all)";
        mi[MAIN_MENU_AUTO_DOWNLOAD_ON_WIFI] = autoDownloadOnWifiLabel(!Downloader.mAutoDownloadOnWifi);
        mi[MAIN_MENU_PRIORITIES] = "Priorities";
        mi[MAIN_MENU_STATUS] = "Status";
        mi[MAIN_MENU_PLAY_FIRST_TRACK] = "Play first track";
        mi[MAIN_MENU_LAUNCH_BROWSER] = "Launch browser";

        builder.setItems(mi, (dialog, which) -> {
            switch (which) {
                case MAIN_MENU_DELETE_FINISHED:
                    setAutoScroll(true);
                    deleteFinished();
                    break;
                case MAIN_MENU_DOWNLOAD_ALL:
                    maybeAskDownload(-1, false);
                    break;
                case MAIN_MENU_DOWNLOAD_ALL_AND_PLAY:
                    if (mIsDriving)
                        drivingDownload(-1);
                    else
                        maybeAskDownload(-1, true);
                    break;
                case MAIN_MENU_DOWNLOAD_NONE:
                    downloadNone();
                    break;
                case MAIN_MENU_REWIND_ALL:
                    askRewindAll();
                    break;
                case MAIN_MENU_AUTO_DOWNLOAD_ON_WIFI:
                    setAutoDownloadOnWifi(!Downloader.mAutoDownloadOnWifi);
                    break;
                case MAIN_MENU_PRIORITIES:
                    askPriorities();
                    break;
                case MAIN_MENU_STATUS:
                    showStatus();
                    break;
                case MAIN_MENU_PLAY_FIRST_TRACK:
                    setAutoScroll(true);
                    playFirstTrack();
                    break;
                case MAIN_MENU_LAUNCH_BROWSER:
                    // Handy if there's a captive portal blocking our access
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.waxrat.com")));
                    break;
            }
            dialog.dismiss();
        });
        builder.show();
    }

    private static final int
        TRACK_MENU_DOWNLOAD = 0,
        TRACK_MENU_DOWNLOAD_AND_PLAY = 1,
        TRACK_MENU_INFO = 2,
        TRACK_MENU_DELETE = 3,
        TRACK_MENU_MOVE_TO_TOP = 4,
        TRACK_MENU_REWIND = 5,
        TRACK_MENU_EDIT_PRIORITY = 6,
        TRACK_MENU_SIZE = 7;

    private void showTrackMenu(final int position) {
        Track track = Tracks.track(position);
        if (track == null)
            return;

        CharSequence[] mi = new CharSequence[TRACK_MENU_SIZE];
        mi[TRACK_MENU_INFO] = "Info";
        mi[TRACK_MENU_DELETE] = ! isPlaying(track) ?
            "Delete" : "(Delete)";
        mi[TRACK_MENU_MOVE_TO_TOP] = position != 0 || ! track.isTopPriority()
            ? "Move to top" : "(Move to top)";
        mi[TRACK_MENU_REWIND] = track.curMs != 0
            ? "Rewind" : "(Rewind)";
        mi[TRACK_MENU_DOWNLOAD] = ! track.downloaded
            ? "Download" : "(Download)";
        mi[TRACK_MENU_DOWNLOAD_AND_PLAY] = ! track.downloaded
            ? "Download and play" : "(Download and play)";
        mi[TRACK_MENU_EDIT_PRIORITY] = "Edit priority";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Track Menu");
        builder.setCancelable(true);
        builder.setItems(mi, (dialog, which) -> {
            switch (which) {
                case TRACK_MENU_INFO:
                    showTrackInfo(track);
                    break;
                case TRACK_MENU_DELETE:
                    askDeleteTrack(track);
                    break;
                case TRACK_MENU_MOVE_TO_TOP:
                    askMoveTrackToTop(track);
                    break;
                case TRACK_MENU_REWIND:
                    askRewindTrack(track);
                    break;
                case TRACK_MENU_DOWNLOAD:
                    if (track.downloaded)
                        Note.toastLong(this, "Track is already downloaded");
                    else
                        startDownload(track.ident, false);
                    break;
                case TRACK_MENU_DOWNLOAD_AND_PLAY:
                    if (track.downloaded)
                        Note.toastLong(this, "Track is already downloaded");
                    else
                        startDownload(track.ident, true);
                    break;
                case TRACK_MENU_EDIT_PRIORITY:
                    editPriority(track);
                    break;
            }
            dialog.dismiss();
        });
        builder.show();
    }

    private void editPriority(@NonNull Track t) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle("Edit Priority");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(t.priority);
        input.setSelectAllOnFocus(true);
        alert.setView(input);

        alert.setTitle("Edit Priority");
        alert.setCancelable(true);

        alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newPriority = input.getText().toString();
                if (newPriority.length() == 1) {
                    newPriority += t.priority.substring(1);
                }
                if (newPriority.equals(t.priority)) {
                    Note.toastLong(MainActivity.this, "Priority unchanged: " + newPriority);
                    return;
                }
                Note.toastLong(MainActivity.this, "New priority: " + newPriority);
                Tracks.setPriority(MainActivity.this, t.ident, newPriority);
                setListItems();
            }
        });
        alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog alertDialog = alert.create();
        alertDialog.show();
    }
}
