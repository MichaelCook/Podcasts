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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Receives broadcasted intents. In particular, we are interested in the
 * android.media.AUDIO_BECOMING_NOISY and android.intent.action.MEDIA_BUTTON intents, which is
 * broadcast, for example, when the user disconnects the headphones. This class works because we are
 * declaring it in a &lt;receiver&gt; tag in AndroidManifest.xml.
 */
public class MusicIntentReceiver extends BroadcastReceiver {
    private final static String TAG = "MusicIntentReceiver";

    public void toastShort(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        //toastShort(context, "Zoiks! " + action);
        if (action.equals(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            onAudioBecomingNoisy(context);
        else if (action.equals(Intent.ACTION_MEDIA_BUTTON))
            onMediaButton(context, intent);
        else
            Log.e(TAG, "Unexpected action " + action);
    }

    private final void onAudioBecomingNoisy(Context context) {
        toastShort(context, "Noise!");
        //toastShort(context, "Headphones disconnected");

        // send an intent to our MusicService to telling it to pause the audio
        //context.startService(new Intent(MusicService.ACTION_PAUSE));
    }

    private void onMediaButton(Context context, Intent intent) {
        KeyEvent keyEvent =
                (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
        if (Log.ok) Log.i(TAG, "Media button " + keyEvent.getAction() +
                " + " + keyEvent.getKeyCode());
        if (keyEvent.getAction() != KeyEvent.ACTION_DOWN)
            return;
        int code = keyEvent.getKeyCode();
        switch (code) {
        case KeyEvent.KEYCODE_HEADSETHOOK:
            toastShort(context, "Headset hook");
            context.startService(new Intent(MusicService.ACTION_TOGGLE_PLAYBACK));
            break;
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            toastShort(context, "Play/Pause");
            context.startService(new Intent(MusicService.ACTION_TOGGLE_PLAYBACK));
            break;
        case KeyEvent.KEYCODE_MEDIA_PLAY:
            toastShort(context, "Play");
            context.startService(new Intent(MusicService.ACTION_PLAY));
            break;
        case KeyEvent.KEYCODE_MEDIA_PAUSE:
            toastShort(context, "Pause");
            context.startService(new Intent(MusicService.ACTION_PAUSE));
            break;
        case KeyEvent.KEYCODE_MEDIA_STOP:
            toastShort(context, "Stop");
            context.startService(new Intent(MusicService.ACTION_STOP));
            break;
        case KeyEvent.KEYCODE_MEDIA_NEXT: // Steering wheel: Up
            toastShort(context, "Forward (Up)");
            context.startService(new Intent(MusicService.ACTION_FORWARD));
            break;
        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: // Steering wheel: Down
            toastShort(context, "Rewind (Down)");
            context.startService(new Intent(MusicService.ACTION_REWIND));
            break;
        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: // Steering wheel: Up Up
            toastShort(context, "Forward (Up Up)");
            context.startService(new Intent(MusicService.ACTION_FORWARD));
            break;
        case KeyEvent.KEYCODE_MEDIA_REWIND: // Steering wheel: Down Down
            toastShort(context, "Play/Pause (Down Down)");
            context.startService(new Intent(MusicService.ACTION_TOGGLE_PLAYBACK));
            break;
        default:
            toastShort(context, "Key " + code);
        }
    }
}
