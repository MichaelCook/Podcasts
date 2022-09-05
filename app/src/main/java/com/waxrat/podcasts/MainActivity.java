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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.telephony.TelephonyManager;
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

    private void setAutoScroll(boolean enable) {
        if (enable != mAutoScroll) {
            mAutoScroll = enable;
            Log.d(TAG, "auto-scroll: " + enable);
        }
    }

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
        int remSec = (t.remMs() + 500) / 1000;
        if (remSec < 0) {
            clearSeekBar();
            return;
        }
        mSeekBar.setMax(t.durMs);
        mSeekBar.setProgress(t.curMs);
        mRemainingText.setText(String.format(Locale.US, "%d:%02d", remSec / 60, remSec % 60));
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
                case Downloader.ACTION_DOWNLOAD_STATE: {
                    String msg = intent.getStringExtra("message");
                    if (msg != null)
                        Note.toastLong(context, msg);
                    break;
                }
                case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                    // e.g., headphones removed
                    Note.toastShort(context, "Noise!");
                    askTo(MusicService.ACTION_MAYBE_PAUSE);
                    break;
                case TelephonyManager.ACTION_PHONE_STATE_CHANGED:
                    onCallState(intent);
                    break;
                case BluetoothDevice.ACTION_ACL_CONNECTED: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) {
                        Log.w(TAG, "No Bluetooth device for " + action);
                        break;
                    }
                    Log.i(TAG, "Connected Bluetooth: " + describeBluetoothDevice(device));
                    setDriving(device, true);
                    break;
                }
                case BluetoothDevice.ACTION_ACL_DISCONNECTED: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) {
                        Log.w(TAG, "No Bluetooth device for " + action);
                        break;
                    }
                    Log.i(TAG, "Disconnected Bluetooth: " + describeBluetoothDevice(device));
                    setDriving(device, false);
                    break;
                }
                case BluetoothAdapter.ACTION_STATE_CHANGED: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) {
                        Log.w(TAG, "No Bluetooth device for " + action);
                        break;
                    }
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    Log.i(TAG, "State " + state + " for Bluetooth " + describeBluetoothDevice(device));
                    break;
                }
                case Tracks.ACTION_TRACKS_LIST_CHANGED:
                    Tracks.copyTracks(viewedTracks);
                    setListItems();
                    break;
                default:
                    Note.e(TAG, "Unexpected action: " + action);
                    break;
            }
        }
    }

    private static final String MY_CAR_BLUETOOTH = "BMW 74998";
    private boolean mIsDriving = false;

    private void setDriving(@NonNull BluetoothDevice dev, boolean enable) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Note.w(TAG, "setDriving: No permission");
            return;
        }
        if (!dev.getName().equals(MY_CAR_BLUETOOTH))
            return;
        if (enable == mIsDriving)
            return;
        mIsDriving = enable;
        if (mIsDriving)
            Note.toastLong(this, "Driving");
        else
            Note.toastLong(this, "Not driving");
        onDrivingChanged();
    }

    private void onDrivingChanged() {
        mKeepScreenOnCheckBox.setChecked(mIsDriving);
    }

    private String describeBluetoothDevice(@NonNull BluetoothDevice dev) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Note.w(TAG, "describeBluetoothDevice: No permission");
            return "(no permission)";
        }
        return '"' + dev.getName() + "\" " + dev.getAddress();
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

        setAutoScroll(true);
        Tracks.restore(this, true);
        setListItems();
        showTrack();

        onDrivingChanged();
        setKeepScreenOn();

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

        /* We pass fromActivity=false here because the user hasn't explicitly
           asked for the download in this situation */
        if (Downloader.okayToDownload(this, false))
            Downloader.downloadNow(this, "resume", false, -1, false, null);
    }

    private boolean mAskPermissions = true;
    private static final String[] mPermissionsToRequest = new String[] {
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_CONNECT,
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
        mAfterTrackButton = findViewById(R.id.afterTrack);
        mKeepScreenOnCheckBox = findViewById(R.id.keepScreenOn);

        mRemainingText.setOnClickListener(this);
        mPlayButton.setOnClickListener(this);
        //mPlayButton.setOnLongClickListener(this);
        mForwardButton.setOnClickListener(this);
        mRewindButton.setOnClickListener(this);
        mForwardButton.setOnLongClickListener(this);
        mRewindButton.setOnLongClickListener(this);
        mSeekBar.setOnSeekBarChangeListener(this);
        mAfterTrackButton.setOnClickListener(this);
        mKeepScreenOnCheckBox.setOnClickListener(this);

        mAutoScroll = true;
        mAskPermissions = needAnyPermissions();

        mReceiver = new ReceiveMessages();
        registerReceiver(mReceiver, new IntentFilter(MusicService.ACTION_PLAY_STATE));
        registerReceiver(mReceiver, new IntentFilter(Downloader.ACTION_DOWNLOAD_STATE));
        registerReceiver(mReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_MEDIA_BUTTON));
        registerReceiver(mReceiver, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
        registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
        registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
        registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
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
                // This callback could be used to keep track of whether the playing track is visible
                //Log.d(TAG, "onScroll fv=" + firstVisibleItem + " v=" + visibleItemCount + " i=" + totalItemCount);
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
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(true);
        alert.setNeutralButton("OK", (dialog, id) -> Log.i(TAG, "ok"));
        AlertDialog alertDialog = alert.create();
        alertDialog.show();
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
            playTrack(Tracks.currentPosition(), true);
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
        Note.w(TAG, "Unexpected onClick target " + target);
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
                String title = t.title;
                if (t.emoji != null && !t.emoji.isEmpty())
                    title = t.emoji + ' ' + title;
                if (!t.priority.isEmpty()) {
                    char c = t.priority.charAt(0);
                    if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                        // Convert c to the Unicode character for a dark circle around the uppercase letter
                        byte[] b = {(byte) 0xF0, (byte) 0x9F, (byte) 0x85, (byte) ((c & 0x1f) - 1 + 0x90)};
                        String s = new String(b, StandardCharsets.UTF_8);
                        title = s + " " + title;
                    }
                }
                title += " \uD83D\uDD39 " + t.artist;      // 🔹
                titleView.setText(title);

                if (!t.downloaded) {
                    percentView.setText("\uD83D\uDEA9");   // 🚩
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

                int dur = t.durMs / 1000;
                durationView.setText(String.format(Locale.US, "%d:%02d", dur / 60, dur % 60));
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
                int n = q - t.curMs;
                if (n >= 0) {
                    n /= 1000;
                    return String.format(Locale.US, "%d:%02d", n / 60, n % 60);
                }
            }
        return "";
    }

    private void showTrackInfo(@NonNull Track t) {
        int position = Tracks.getPositionOfTrack(t);
        if (position == -1) {
            Note.toastLong(this, "No such track");
            return;
        }
        int remMs = t.remMs();

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
                .append(Utilities.mmss(remMs))
                .append(" = ")
                .append(Utilities.mmss(t.durMs));

        /* Show how long until this track will finish playing if all lower-numbered
           tracks are played first. */
        for (int pos = 0; pos < position; ++pos) {
            Track u = Tracks.track(pos);
            if (u != null)
                remMs += u.remMs();
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
            playTrack(position, false);
            return true; // click consumed
        });
        setSeekBar();
    }

    private void playTrack(int position, boolean go) {
        if (MusicService.playing())
            go = true;

        Track t = Tracks.track(position);
        if (t == null) {
            Note.toastLong(this, "playTrack: no track " + position);
            return;
        }
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
            showTrack();
            setSeekBar();
            askTo(MusicService.ACTION_UPDATE_METADATA);
        }
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

    private void askScrollToPriority() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle("Scroll to Priority?");
        alert.setCancelable(true);

        alert.setSingleChoiceItems(Tracks.priorityClasses(), 0,
                (dialog, which) -> {
                    CharSequence[] pcs = Tracks.priorityClasses();
                    String pc = pcs[which].toString();
                    int pos = Tracks.findPriorityClass(pc, false);
                    if (pos != -1)
                        getListView().smoothScrollToPositionFromTop(pos, 0);
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

    private void askDeleteTrack(@NonNull Track track) {
        if (MusicService.playing() && track == Tracks.currentTrack()) {
            toastShort("Can't delete the playing track");
            return;
        }

        confirm("Delete \"" + track.title + '"',
                (dialog, id) -> {
                    // Currently playing track may have changed since we checked above...
                    if (MusicService.playing() && track == Tracks.currentTrack()) {
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
                    if (MusicService.playing() && Tracks.currentTrack() == track) {
                        Log.i(TAG, "Rewind currently playing track");
                        askTo(MusicService.ACTION_SKIP_TO_TRACK_START);
                    }
                    else {
                        Tracks.rewind(this, track);
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
        int remMin = Tracks.remainingSeconds() / 60;
        sb.append(String.format(Locale.US, "\nHours remaining: %d:%02d", remMin / 60, remMin % 60));

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

    void playPriorityA() {
        int pos = Tracks.findPriorityClass("A", true);
        if (pos == -1) {
            Note.toastLong(this, "No such track");
            return;
        }
        playTrack(pos, true);
    }

    static final CharSequence[] mainMenuItems = new CharSequence[] {
        "Delete finished",            // 0
        "Download all",               // 1
        "Download all and play",      // 2
        "Download none",              // 3
        "Rewind all",                 // 4
        autoDownloadOnWifiLabel(true), // 5
        "Scroll to priority",         // 6
        "Status",                     // 7
        "Play priority A",            // 8
    };

    private void showMainMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Podcasts");
        builder.setCancelable(true);
        mainMenuItems[5] = autoDownloadOnWifiLabel(!Downloader.mAutoDownloadOnWifi);
        builder.setItems(mainMenuItems, (dialog, which) -> {
            switch (which) {
                case 0: // "Delete finished"
                    setAutoScroll(true);
                    deleteFinished();
                    break;
                case 1: // "Download all"
                    maybeAskDownload(-1, false);
                    break;
                case 2: // "Download all and play"
                    if (mIsDriving)
                        drivingDownload(-1);
                    else
                        maybeAskDownload(-1, true);
                    break;
                case 3: // "Download none"
                    downloadNone();
                    break;
                case 4: // "Rewind"
                    askRewindAll();
                    break;
                case 5: // "Auto download on Wi-Fi"
                    setAutoDownloadOnWifi(!Downloader.mAutoDownloadOnWifi);
                    break;
                case 6: // "Scroll to priority"
                    askScrollToPriority();
                    break;
                case 7: // "Status"
                    showStatus();
                    break;
                case 8: // "Play priority A"
                    playPriorityA();
                    break;
            }
            dialog.dismiss();
        });
        builder.show();
    }

    static final CharSequence[] trackMenuItems = new CharSequence[] {
        "Info",                 // 0
        "Delete",               // 1
        "Move to top",          // 2
        "Rewind",               // 3
        "Download",             // 4
        "Download and play",    // 5
    };

    private void showTrackMenu(final int position) {
        Track track = Tracks.track(position);
        if (track == null)
            return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Track Menu");
        builder.setCancelable(true);
        builder.setItems(trackMenuItems, (dialog, which) -> {
            switch (which) {
                case 0: // "Info"
                    showTrackInfo(track);
                    break;
                case 1: // "Delete"
                    askDeleteTrack(track);
                    break;
                case 2: // "Move to top"
                    askMoveTrackToTop(track);
                    break;
                case 3: // "Rewind"
                    askRewindTrack(track);
                    break;
                case 4: // "Download"
                    if (track.downloaded)
                        Note.toastLong(this, "Track is already downloaded");
                    else
                        startDownload(track.ident, false);
                    break;
                case 5: // "Download and play"
                    if (track.downloaded)
                        Note.toastLong(this, "Track is already downloaded");
                    else
                        startDownload(track.ident, true);
                    break;
            }
            dialog.dismiss();
        });
        builder.show();
    }
}
