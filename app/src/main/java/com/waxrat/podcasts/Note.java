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

package com.waxrat.podcasts;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@SuppressWarnings("unused")
public class Note {
    private static final String TAG = "Podcasts.Note";

    static final boolean logToDisk = true;

    public static void i(@NonNull String tag, @NonNull String msg) {
        Log.i(tag, msg);
        if (logToDisk)
            put('I', tag, msg, null);
    }

    public static void i(@NonNull String tag, @NonNull String msg, @NonNull Throwable thr) {
        Log.e(tag, msg, thr);
        if (logToDisk)
            put('I', tag, msg + ": " + thr.getMessage(), thr);
    }

    public static void w(@NonNull String tag, @NonNull String msg) {
        Log.w(tag, msg);
        if (logToDisk)
            put('W', tag, msg, null);
    }

    public static void e(@NonNull String tag, @NonNull String msg) {
        Log.e(tag, msg);
        if (logToDisk)
            put('E', tag, msg, null);
    }

    public static void e(@NonNull String tag, @NonNull String msg, @NonNull Throwable thr) {
        Log.e(tag, msg, thr);
        if (logToDisk)
            put('E', tag, msg + ": " + thr.getMessage(), thr);
    }

    // TODO: use getExternalStorageDirectory
    private static final File NOTE_FILE = new File("/storage/emulated/0/Android/data/com.waxrat.podcasts/cache/_NOTE.txt");

    private static @Nullable FileWriter fw;
    private static final SimpleDateFormat sdf =
        new SimpleDateFormat(" yyyy-MM-dd HH:mm:ss.SSS ", Locale.US);

    private synchronized static void put(char level, @NonNull String tag, @NonNull String msg,
                                         @Nullable Throwable thr) {
        try {
            if (fw == null) {
                if (NOTE_FILE.length() >= 1024 * 1024) {
                    /*
                       Could save more versions of this file:
                        move(NOTE_FILE + ".3", NOTE_FILE + ".4");
                        move(NOTE_FILE + ".2", NOTE_FILE + ".3");
                        move(NOTE_FILE + ".1", NOTE_FILE + ".2");
                     */
                    move(NOTE_FILE.toString(), NOTE_FILE + ".1");
                }
                fw = new FileWriter(NOTE_FILE, true); // true: append
            }
            Date d = new Date();
            fw.write(level);
            fw.write(sdf.format(d));
            Thread thread = Thread.currentThread();
            fw.write(thread.getName());
            fw.write('/');
            fw.write(Long.toString(thread.getId()));
            fw.write(' ');
            fw.write(tag);
            fw.write(' ');
            fw.write(msg);
            fw.write('\n');
            if (thr != null)
                thr.printStackTrace(new PrintWriter(fw));
            fw.flush();
        }
        catch (IOException ex) {
            Log.e(tag, "IOException", ex);
            if (fw != null)
            {
                FileWriter fw2 = fw;
                fw = null;
                try {
                    fw2.close();
                }
                catch (IOException ex2) {
                    Log.e(tag, "IOException while closing", ex2);
                }
            }
        }
    }

    private static void move(String from, String to) {
        File f = new File(from);
        File t = new File(to);
        if (f.renameTo(t))
            Log.i(TAG, "Renamed " + f + " to " + t);
        else
            Log.e(TAG, "Could not rename " + f + " to " + t);
    }

    static void toastLong(@NonNull Context context, @NonNull String msg) {
        Log.i(TAG, "Toast long: " + msg);
        Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_LONG)
                .show();
    }

    @NonNull
    static Toast toastShort(@NonNull Context context, @NonNull String msg) {
        Log.i(TAG, "Toast short: " + msg);
        Toast t = Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT);
        t.show();
        return t;
    }
}
