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

/*
TODO: (BUG) FATAL EXCEPTION: main

I had copied new tracks into the folder via the MTP client.  A while later the
MusicService finished playing a track and tried to start the next track:

W/AlarmManager(  723): FACTORY_ON= 0
D/AndroidRuntime( 2200): Shutting down VM
W/dalvikvm( 2200): threadid=1: thread exiting with uncaught exception (group=0x415ba438)
E/AndroidRuntime( 2200): FATAL EXCEPTION: main
E/AndroidRuntime( 2200): java.lang.IllegalStateException: The content of the adapter has changed but Lis$
E/AndroidRuntime( 2200):        at android.widget.ListView.layoutChildren(ListView.java:1544)
E/AndroidRuntime( 2200):        at android.widget.AbsListView$FlingRunnable.run(AbsListView.java:4586)
E/AndroidRuntime( 2200):        at android.view.Choreographer$CallbackRecord.run(Choreographer.java:725)
E/AndroidRuntime( 2200):        at android.view.Choreographer.doCallbacks(Choreographer.java:555)
E/AndroidRuntime( 2200):        at android.view.Choreographer.doFrame(Choreographer.java:524)
E/AndroidRuntime( 2200):        at android.view.Choreographer$FrameDisplayEventReceiver.run(Choreographe$
E/AndroidRuntime( 2200):        at android.os.Handler.handleCallback(Handler.java:615)
E/AndroidRuntime( 2200):        at android.os.Handler.dispatchMessage(Handler.java:92)
E/AndroidRuntime( 2200):        at android.os.Looper.loop(Looper.java:137)
E/AndroidRuntime( 2200):        at android.app.ActivityThread.main(ActivityThread.java:4918)
E/AndroidRuntime( 2200):        at java.lang.reflect.Method.invokeNative(Native Method)
E/AndroidRuntime( 2200):        at java.lang.reflect.Method.invoke(Method.java:511)
E/AndroidRuntime( 2200):        at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit$
E/AndroidRuntime( 2200):        at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:771)
E/AndroidRuntime( 2200):        at dalvik.system.NativeStart.main(Native Method)

---

06-22 09:08:10.183: I/Track(6415): No track r-2013-06-21-0400-3642-CarStuff-2013-06-20-carstuff-kit-cars.mp3
06-22 09:08:10.193: D/dalvikvm(722): WAIT_FOR_CONCURRENT_GC blocked 0ms
06-22 09:08:10.213: I/Track(6415): read track count: 765
06-22 09:08:10.233: I/Track(6415): Send ACTION_MEDIA_MOUNTED
06-22 09:08:10.253: I/Choreographer(6415): Skipped 61 frames!  The application may be doing too much work on its main thread.
06-22 09:08:10.253: D/AndroidRuntime(6415): Shutting down VM
06-22 09:08:10.253: W/dalvikvm(6415): threadid=1: thread exiting with uncaught exception (group=0x414dc438)
06-22 09:08:10.253: E/AndroidRuntime(6415): FATAL EXCEPTION: main
06-22 09:08:10.253: E/AndroidRuntime(6415): java.lang.IllegalStateException: The content of the adapter has changed but ListView did not receive a notification. Make sure the content of your adapter is not modified from a background thread, but only from the UI thread. [in ListView(16908298, class android.widget.ListView) with Adapter(class com.waxrat.podcasts.MainActivity$TrackArrayAdapter)]
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.widget.ListView.layoutChildren(ListView.java:1544)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.widget.AbsListView$FlingRunnable.run(AbsListView.java:4586)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.view.Choreographer$CallbackRecord.run(Choreographer.java:725)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.view.Choreographer.doCallbacks(Choreographer.java:555)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.view.Choreographer.doFrame(Choreographer.java:524)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.view.Choreographer$FrameDisplayEventReceiver.run(Choreographer.java:711)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.os.Handler.handleCallback(Handler.java:615)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.os.Handler.dispatchMessage(Handler.java:92)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.os.Looper.loop(Looper.java:137)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at android.app.ActivityThread.main(ActivityThread.java:4918)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at java.lang.reflect.Method.invokeNative(Native Method)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at java.lang.reflect.Method.invoke(Method.java:511)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1004)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:771)
06-22 09:08:10.253: E/AndroidRuntime(6415):     at dalvik.system.NativeStart.main(Native Method)

06-22 08:38:35.683: I/Track(24908): Send ACTION_MEDIA_MOUNTED
06-22 08:38:35.714: I/Choreographer(24908): Skipped 48 frames!  The application may be doing too much work on its main thread.
06-22 08:38:35.714: D/AndroidRuntime(24908): Shutting down VM
06-22 08:38:35.714: W/dalvikvm(24908): threadid=1: thread exiting with uncaught exception (group=0x414dc438)
06-22 08:38:35.714: W/AlarmManager(722): FACTORY_ON= 0
06-22 08:38:35.714: W/AlarmManager(722): FACTORY_ON= 0
06-22 08:38:35.724: E/AndroidRuntime(24908): FATAL EXCEPTION: main
06-22 08:38:35.724: E/AndroidRuntime(24908): java.lang.IllegalStateException: The content of the adapter has changed but ListView did not receive a notification. Make sure the content of your adapter is not modified from a background thread, but only from the UI thread. [in ListView(16908298, class android.widget.ListView) with Adapter(class com.waxrat.podcasts.MainActivity$TrackArrayAdapter)]
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.widget.ListView.layoutChildren(ListView.java:1544)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.widget.AbsListView$FlingRunnable.run(AbsListView.java:4586)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.view.Choreographer$CallbackRecord.run(Choreographer.java:725)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.view.Choreographer.doCallbacks(Choreographer.java:555)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.view.Choreographer.doFrame(Choreographer.java:524)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.view.Choreographer$FrameDisplayEventReceiver.run(Choreographer.java:711)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.os.Handler.handleCallback(Handler.java:615)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.os.Handler.dispatchMessage(Handler.java:92)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.os.Looper.loop(Looper.java:137)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at android.app.ActivityThread.main(ActivityThread.java:4918)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at java.lang.reflect.Method.invokeNative(Native Method)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at java.lang.reflect.Method.invoke(Method.java:511)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1004)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:771)
06-22 08:38:35.724: E/AndroidRuntime(24908):    at dalvik.system.NativeStart.main(Native Method)
06-22 08:38:35.744: E/videowall-TranscodeReceiver(17019): broadcastMSG : android.intent.action.SCREEN_ON

I had just deleted many tracks via the Windows MTP client.

TODO: (BUG) Somehow still possible to end up with multiple instances of
     MainActivity via the notification menu.

     To reproduce:
     1. Launch Podcasts app.
     2. Start a track playing.
     3. Press Home.
     4. Launch Messages app.
     5. From the notification menu, select the Podcasts app.
     6. Press Home.
     7. Launch Podcasts app.

     You'll see the "Yikes! 1" toast.

TODO: Memoize the lookup of track by name.

TODO: (BUG) Seek bar while paused doesn't work.

TODO: (BUG) Rewind to 0 the currently playing track doesn't work.

TODO: Sort by: title, length, reverse length.

TODO: (BUG) Car radio doesn't display the title.  See:
     http://www.beyondpod.com/forum/archive/index.php/t-753.html Audio/Video
     Remote Control Profile (AVRCP) is the protocol for sending track info to a
     Bluetooth-connected device.  Need AVRCP 1.3 or higher.
     Stock media player and Pandora app don't have this problem.

     https://github.com/loganakamatsu/SpotifyAVRCP
     http://stackoverflow.com/questions/7822923/how-to-send-bluetooth-avrcp-vendor-dependent-and-pass-through-commands-from-app
     http://stackoverflow.com/questions/5857783/working-with-bluetooth-in-android-kernel-development-linux
*/

package com.waxrat.podcasts;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends ListActivity implements OnClickListener,
OnLongClickListener, OnSeekBarChangeListener {
    private final static String TAG = "MainActivity";

    private Button mPlayButton;
    private Button mForwardButton;
    private Button mRewindButton;
    private SeekBar mSeekBar;
    private TextView mRemainingText;
    private CheckBox mKeepScreenOnCheckBox;

    private ReceiveMessages receiver = null;

    private void setSeekBar() {
        Track t = Tracks.currentTrack();
        if (t == null) {
            if (Log.ok)Log.i(TAG, "setSeekBar: no track");
        }
        else if (t.durationMs <= 0) {
            if (Log.ok)Log.i(TAG, "setSeekBar: durMs=" + t.durationMs);
        }
        else {
            int remSec = (t.durationMs - t.currentMs + 500) / 1000;
            if (Log.ok)Log.i(TAG, "setSeekBar: remSec=" + remSec);
            if (remSec >= 0) {
                mSeekBar.setMax(t.durationMs);
                mSeekBar.setProgress(t.currentMs);
                mRemainingText.setText(String.format("%d:%02d", remSec / 60,
                        remSec % 60));
                if (trackArrayAdapter != null)
                    trackArrayAdapter.notifyDataSetChanged();
                return;
            }
        }
        mSeekBar.setMax(0);
        mSeekBar.setProgress(0);
        mRemainingText.setText(getString(R.string.no_remaining_time));
    }

    private int lastPosition = -1;

    private void onPlayStateIntent(Intent intent) {
        String path = intent.getStringExtra("path");
        setPlayPauseImage();
        if (path != null) {
            Tracks.selectTrackByName(path);
            setSeekBar();
        }
        if (intent.getBooleanExtra("restored", false) && trackArrayAdapter != null) {
            if (Log.ok) Log.i(TAG, "Got restored notification");
            trackArrayAdapter.notifyDataSetChanged();
            lastPosition = -1;
            setSeekBar();
        }
        if (lastPosition != Tracks.position) {
            makeTrackVisible(Tracks.position);
            lastPosition = Tracks.position;
        }
    }

    private void onDownloadStateIntent(Intent intent) {
        toastLong(intent.getStringExtra("message"));
    }

/*
    private void save() {
        if (Log.ok) Log.i(TAG, "Send intent: save");
        startService(new Intent(MusicService.ACTION_SAVE));
    }
*/

    private void restore() {
        if (Log.ok) Log.i(TAG, "Send intent: restore");
        startService(new Intent(MusicService.ACTION_RESTORE));
    }

    class ReceiveMessages extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            //if (Log.ok) Log.i(TAG, "action is " + action);
            if (action.equals(MusicService.PLAY_STATE_INTENT))
                onPlayStateIntent(intent);
            else if (action.equals(DownloadService.DOWNLOAD_STATE_INTENT))
                onDownloadStateIntent(intent);
            else
                Log.e(TAG, "Unexpected action: " + action);
        }
    }

    /*
    private void selectTrack(int position, boolean go) {
        makeTrackVisible(position);
        playTrack(position, go);
    }
    */

    private boolean isLandscape() {
        return Configuration.ORIENTATION_LANDSCAPE ==
                getResources().getConfiguration().orientation;
    }

    private void setKeepScreenOn() {
        /* If we're in landscape mode, assume we're in the car and so we
        want to keep the screen on while we're driving. */
        //boolean kso = MusicService.playing() && isLandscape();
        boolean kso = MusicService.playing() && mKeepScreenOnCheckBox.isChecked();
        if (Log.ok) Log.i(TAG, "keep screen on: " + kso);
        mPlayButton.setKeepScreenOn(kso);
    }

    private int lastPlaying = -1;

    void setPlayPauseImage() {
        boolean playing = MusicService.playing();
        int nowPlaying = playing ? 1 : 0;
        if (nowPlaying != lastPlaying) {
            if (Log.ok) Log.i(TAG, "playing " + lastPlaying + " -> " + nowPlaying);
            lastPlaying = nowPlaying;
            mPlayButton.setBackgroundResource(playing ? R.drawable.btn_pause
                    : R.drawable.btn_play);
            setKeepScreenOn();
        }
    }

    private void makeTrackVisible(int pos) {
        if (Log.ok) Log.i(TAG, "makeTrackVisible " + pos);
        if (pos != -1)
            //getListView().smoothScrollToPositionFromTop(pos, 64);
            getListView().smoothScrollToPosition(pos);
        if (trackArrayAdapter != null)
            trackArrayAdapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        if (Log.ok) Log.i(TAG, "onResume");
        super.onResume();
        restore();
        mKeepScreenOnCheckBox.setChecked(isLandscape());
        setListItems();
        setPlayPauseImage();
        setSeekBar();
        /* As long as we're the foreground activity, the user can lock and
           unlock the screen by simply pressing the power button (no need
           to enter a PIN, for example). But if they lock then unlock the
           screen, then navigate away from this activity (to an activity
           that doesn't use these flags), then they will have to unlock
           the screen (e.g., enter their PIN). */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    @Override
    public void onPause() {
        if (Log.ok) Log.i(TAG, "onPause");
        super.onPause();
        if (!MusicService.playing())
            startService(new Intent(MusicService.ACTION_STOP));
    }

    private static int numInstances = 0;

    /**
     * Called when the activity is first created. Here, we simply set the event
     * listeners and start the background service ({@link MusicService}) that
     * will handle the actual media playback.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Log.ok) Log.i(TAG, "onCreate " + numInstances);
        if (numInstances != 0)
            toastLong("Yikes! " + numInstances);
        ++numInstances;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        receiver = new ReceiveMessages();

        mPlayButton = (Button) findViewById(R.id.playbutton);
        mForwardButton = (Button) findViewById(R.id.forwardbutton);
        mRewindButton = (Button) findViewById(R.id.rewindbutton);
        mSeekBar = (SeekBar) findViewById(R.id.seekBar);
        mRemainingText = (TextView) findViewById(R.id.remainingText);
        mKeepScreenOnCheckBox = (CheckBox) findViewById(R.id.keepScreenOn);

        mPlayButton.setOnClickListener(this);
        //mPlayButton.setOnLongClickListener(this);
        mForwardButton.setOnClickListener(this);
        mRewindButton.setOnClickListener(this);
        mForwardButton.setOnLongClickListener(this);
        mRewindButton.setOnLongClickListener(this);
        mSeekBar.setOnSeekBarChangeListener(this);
        mKeepScreenOnCheckBox.setOnClickListener(this);

        registerReceiver(receiver, new IntentFilter(MusicService.PLAY_STATE_INTENT));
        registerReceiver(receiver, new IntentFilter(DownloadService.DOWNLOAD_STATE_INTENT));

        DownloadService.schedule(this);
    }

    @Override
    protected void onDestroy() {
        --numInstances;
        if (Log.ok) Log.i(TAG, "onDestroy " + numInstances);
        super.onDestroy();
        unregisterReceiver(receiver);
    }

    @Override
    protected void onStop() {
        if (Log.ok) Log.i(TAG, "onStop");
        //toastShort("onStop");
        super.onStop();
    }

    private void toastShort(String msg) {
        Toast t = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
        Log.i(TAG, "Toast: " + msg);
    }

    private void toastLong(String msg) {
        Toast t = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
        Log.i(TAG, "Toast: " + msg);
    }

    private final Context context = this;

    private void alert(String title, String message) {
        if (Log.ok) Log.i(TAG, "Alert: " + title + " - " + message);
        AlertDialog.Builder alert = new AlertDialog.Builder(context);
        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(true);
        alert.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                if (Log.ok) Log.i(TAG, "ok");
            }
        });
        AlertDialog alertDialog = alert.create();
        alertDialog.show();
    }

    private void confirm(String title, String message,
            DialogInterface.OnClickListener yesAction) {
        if (Log.ok) Log.i(TAG, "Confirm: " + title + " - " + message);
        AlertDialog.Builder alert = new AlertDialog.Builder(context);
        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(true);
        alert.setPositiveButton("Yes", yesAction);
        alert.setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                if (Log.ok) Log.i(TAG, "No!");
            }
        });
        AlertDialog alertDialog = alert.create();
        alertDialog.show();
    }

    public void onClick(View target) {
        if (Log.ok) Log.i(TAG, "onClick");
        // Send the correct intent to the MusicService, according to the button
        // that was clicked
        if (target == mPlayButton) {
            if (MusicService.playing()) {
                setListItems();
                startService(new Intent(MusicService.ACTION_PAUSE));
                return;
            }
            Track t = Tracks.currentTrack();
            if (t == null) {
                toastLong("No track");
                return;
            }
            playTrack(Tracks.position, true);
            return;
        }
        if (target == mForwardButton) {
            startService(new Intent(MusicService.ACTION_FORWARD));
            return;
        }
        if (target == mRewindButton) {
            startService(new Intent(MusicService.ACTION_REWIND));
            return;
        }
        if (target == mKeepScreenOnCheckBox) {
            if (mKeepScreenOnCheckBox.isChecked())
                toastShort("Keep screen on while playing");
            else
                toastShort("Don't keep screen on");
            setKeepScreenOn();
            return;
        }
        Log.w(TAG, "Unexpected onClick target " + target);
    }

    public boolean onLongClick(View target) {
        if (Log.ok) Log.i(TAG, "onLongClick");
        // Send the correct intent to the MusicService, according to the
        // button that was clicked
        if (target == mForwardButton)
            //selectTrack(Tracks.position + 1, false);
            skipToEnd();
        else if (target == mRewindButton)
            startService(new Intent(MusicService.ACTION_PREVIOUS));
        else
            return false; // didn't consume the long click
        return true; // consumed the long click
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Log.ok) Log.i(TAG, "onKeyDown " + keyCode);
        switch (keyCode) {
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
        case KeyEvent.KEYCODE_HEADSETHOOK:
            startService(new Intent(MusicService.ACTION_TOGGLE_PLAYBACK));
            return true;
        }
        /* If the track folder changed by some means other than this app
           (e.g., via the MTP client), we want to eventually notice that.
           So, we check on keyDown events.  The restore() function is assumed
           to be very efficient when the track folder has not actually
           changed. */
/* This tends to cause the ListView to jump around.
        if (keyCode == KeyEvent.KEYCODE_MENU)
            restore();
*/
        return super.onKeyDown(keyCode, event);
    }

    public void onProgressChanged(SeekBar seekBar, int progress,
            boolean fromUser) {
        /*if (Log.ok) Log.i(TAG, "onProgressChanged(" + progress + ',' + fromUser + ") " +
                trackPosition);*/
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
        if (Log.ok) Log.i(TAG, "onStartTrackingTouch");
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        if (Log.ok) Log.i(TAG, "onStopTrackingTouch: " + seekBar.getProgress());
        Intent i = new Intent(MusicService.ACTION_SEEK);
        i.putExtra("where", seekBar.getProgress());
        startService(i);
    }

    //private static boolean bork;

    private class TrackArrayAdapter extends ArrayAdapter<Track> {
        private final LayoutInflater inflater = (LayoutInflater)
          MainActivity.this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        public TrackArrayAdapter() {
            super(MainActivity.this, R.layout.track_list, Tracks.tracks);
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
            if (position < 0 || position >= Tracks.tracks.size())
                return IGNORE_ITEM_VIEW_TYPE;
            return 0;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            //if (Log.ok) Log.i(TAG, "getView " + position);
            View rowView = convertView;
            if (rowView == null)
                rowView = inflater.inflate(R.layout.track_list, parent, false);
            /* TODO cache/memoize the calls to findViewById using
               View.getTag().  See Bootcamp page 198. */
            TextView percentView = (TextView)
                    rowView.findViewById(R.id.percentDone);
            TextView durationView = (TextView)
                    rowView.findViewById(R.id.duration);
            TextView titleView = (TextView)
                    rowView.findViewById(R.id.title);
            if (position < 0 || position >= Tracks.tracks.size()) {
                titleView.setText("Position " + position + " of " +
                        Tracks.tracks.size());
                percentView.setText("");
                durationView.setText("");
            }
            else {
                Track t = Tracks.tracks.get(position);
                titleView.setText(t.title);
                if (t.durationMs == 0)
                    percentView.setText("...");
                else if (t.currentMs == t.durationMs)
                    percentView.setText("Done");
                else if (t.currentMs == 0)
                    percentView.setText("");
                else {
                    double perc = 100.0 * t.currentMs / t.durationMs;
                    /*
                    if (bork)
                        perc = 100;
                    bork = ! bork;
                    */
                    percentView.setText(String.format("%.0f%%", perc));
                }
                int dur = t.durationMs / 1000;
                durationView.setText(String.format("%d:%02d", dur / 60, dur % 60));
            }
            rowView.setBackgroundColor(position == Tracks.position
                    ? Color.BLUE : Color.BLACK);
            return rowView;
        }
    }

    @SuppressLint("DefaultLocale")
    private static String mmss(int ms) {
        int s = ms / 1000;
        return String.format("%d:%02d.%03d", s / 60, s % 60, ms % 1000);
    }

    private TrackArrayAdapter trackArrayAdapter;

    private static final SimpleDateFormat sdf =
            new SimpleDateFormat(" (yyyy-MM-dd HH:mm:ss.SSS)\n\n", Locale.US);

    private void showTrackInfo(int position) {
        String s = null;
        Track t = Tracks.track(position);
        if (t != null)
            s = t.pathName;
        MainActivity.this.alert("Track #" + (position + 1),
                '"' + t.title + "\"\n\n" + s +
                sdf.format(new Date(t.lastMod)) +
                mmss(t.currentMs) + " + " +
                mmss(t.durationMs - t.currentMs) + " = " +
                mmss(t.durationMs));
    }

    private OnItemLongClickListener itemLongClicker =
            new OnItemLongClickListener() {
        public boolean onItemLongClick(AdapterView<?> parent, View view,
                int position, long id) {
            if (itemShortClickPlays)
                showTrackInfo(position);
            else
                playTrack(position, false);
            return true; // click consumed
        }
    };

    private void setListItems() {
        if (trackArrayAdapter == null) {
            trackArrayAdapter = new TrackArrayAdapter();
            setListAdapter(trackArrayAdapter);
        }
        ListView lv = getListView();
        lv.setOnItemLongClickListener(itemLongClicker);
        //makeTrackVisible(Tracks.position);
    }

    private void playTrack(int position, boolean go) {
        Tracks.position = position;
        if (MusicService.playing())
            go = true;
        if (!go) {
            Track t = Tracks.currentTrack();
            Log.i(TAG, "Will play track " + position + ' ' + t);
            setSeekBar();
            startService(new Intent(MusicService.ACTION_SAVE));
            return;
        }
        Track t = Tracks.currentTrack();
        if (t == null) {
            toastLong("playTrack when no track");
            return;
        }
        int curMs = 0;
        /* If this track is "done", start from the beginning.
           Otherwise, start from where we left off last, but rewound a little */
        if (t.currentMs != t.durationMs) {
            curMs = t.currentMs - MusicService.OVERLAP_MS;
            if (curMs < 0)
                curMs = 0;
        }
        Log.i(TAG, "Play track " + position + ' ' + t);
        Intent in = new Intent(MusicService.ACTION_PLAY);
        in.putExtra("path", t.pathName);
        in.putExtra("title", t.title);
        in.putExtra("ms", curMs);
        startService(in);
    }

    static final boolean itemShortClickPlays = false;

    @Override
    protected void onListItemClick(ListView list, View v, int position, long id) {
        if (itemShortClickPlays)
            playTrack(position, false);
        else
            showTrackInfo(position);
    }

    private final OnMenuItemClickListener deleteFinished =
            new OnMenuItemClickListener() {
        public boolean onMenuItemClick(MenuItem item) {
            DownloadService.cancelNotification(MainActivity.this);
            if (Log.ok) Log.i(TAG, "Send intent: delete finished");
            startService(new Intent(MusicService.ACTION_DELETE_FINISHED));
            return true;
        }
    };

    private final DialogInterface.OnClickListener rewindAllConfirmed =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            if (Log.ok) Log.i(TAG, "Send intent: rewind all");
            startService(new Intent(MusicService.ACTION_REWIND_ALL));
        }
    };

    private final OnMenuItemClickListener rewindAll =
            new OnMenuItemClickListener() {
        public boolean onMenuItemClick(MenuItem item) {
            int n = 0;
            for (Track t : Tracks.tracks)
                if (t.currentMs != 0)
                    ++n;
            if (n == 0)
                toastShort("None to rewind");
            else
                confirm(n == 1 ? "Rewind 1 track" : "Rewind " + n + " tracks",
                        "Are you sure?", rewindAllConfirmed);
            return true;
        }
    };

    private void skipToEnd() {
        Track t = Tracks.currentTrack();
        if (t == null) {
            toastShort("No track");
            return;
        }
        if (Log.ok) Log.i(TAG, "Skip to end: " + t);
        Intent i = new Intent(MusicService.ACTION_SEEK);
        /* Don't skip all the way to the end. The media player acts
           strangely if you do that, sometimes playing a fraction of
           a second of the beginning of the track. */
        int end = t.durationMs - 1000;
        if (end < 0)
            end = 0;
        i.putExtra("where", end);
        startService(i);
    }

/*
    private final DialogInterface.OnClickListener skipToEndConfirmed =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            skipToEnd();
        }
    };

    private final OnMenuItemClickListener skipToEnd =
            new OnMenuItemClickListener() {
        public boolean onMenuItemClick(MenuItem item) {
            confirm("Skip to end", "Are you sure?", skipToEndConfirmed);
            return true;
        }
    };
*/

    private final void startDownload() {
        DownloadService.cancelNotification(MainActivity.this);
        Intent in = new Intent(MainActivity.this, DownloadService.class);
        in.putExtra("from-activity", true);
        startService(in);
    }

    private final DialogInterface.OnClickListener downloadConfirmed =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            if (Log.ok) Log.i(TAG, "Yes - download");
            startDownload();
        }
    };

    private final OnMenuItemClickListener checkDownloads =
            new OnMenuItemClickListener() {
        public boolean onMenuItemClick(MenuItem item) {
            if (Log.ok) Log.i(TAG, "Send intent: check downloads");
            if (DownloadService.isRunning())
                toastShort("Already checking");
            else if (!DownloadService.isWifi(MainActivity.this, false)) {
                confirm("Download", "Not on Wi-Fi, are you sure?",
                        downloadConfirmed);
            }
            /* As long as we're on Wi-Fi, we don't care where we
               are for on-demand checks. */
/*
            else if (!DownloadService.isHome(MainActivity.this, false)) {
                confirm("Download", "On Wi-Fi but not home, are you sure?",
                        downloadConfirmed);
            }
*/
            else {
                startDownload();
            }
            return true;
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (Log.ok) Log.i(TAG, "onCreateOptionsMenu");
        menu.add("Delete").setOnMenuItemClickListener(deleteFinished);
        menu.add("Rewind").setOnMenuItemClickListener(rewindAll);
        //menu.add("Skip to End").setOnMenuItemClickListener(skipToEnd);
        menu.add("Download").setOnMenuItemClickListener(checkDownloads);
        return true;
    }
}
