package com.waxrat.podcasts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

public class MusicIntentReceiver extends BroadcastReceiver {
    private final static String TAG = "Podcasts.MusicIntentReceiver";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (action == null)
            return;
        switch (action) {
            case Intent.ACTION_MEDIA_BUTTON:
                onMediaButton(context, intent);
                break;
            default:
                Note.toastShort(context, "Unexpected action " + action);
                break;
        }
    }

    private void act(@NonNull Context context, @NonNull String action) {
        Intent in = new Intent(action);
        in.setPackage(Utilities.PACKAGE);
        try {
            context.startService(in);
        }
        catch (IllegalStateException exc) {
            // Not allowed to start service Intent: app is in background
            Note.w(TAG, "Couldn't start service " + action + ": " + exc.getMessage());
        }
    }

    private void onMediaButton(@NonNull Context context, @NonNull Intent intent) {
        KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        if (keyEvent == null) {
            Note.w(TAG, "onMediaButton: No key event");
            return;
        }

        Log.i(TAG, "onMediaButton: " + keyEvent.getAction() + ", " + keyEvent.getKeyCode() +
               ", " + keyEvent.getModifiers() + ", " + keyEvent.getMetaState());

        int action = keyEvent.getAction();
        if (action != KeyEvent.ACTION_DOWN)
            return;
        int code = keyEvent.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
                Note.toastShort(context, "Headset hook");
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                Note.toastShort(context, "Play/Pause");
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                Note.toastShort(context, "Play");
                act(context, MusicService.ACTION_PLAY);
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                Note.toastShort(context, "Pause");
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                Note.toastShort(context, "Stop");
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                Note.toastShort(context, "Next");
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                Note.toastShort(context, "Previous");
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                Note.toastShort(context, "Forward");
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                Note.toastShort(context, "Rewind");
                break;
            default:
                Note.toastShort(context, "Key " + code);
                break;
        }
    }
}
