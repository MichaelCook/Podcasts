package com.waxrat.podcasts;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// TODO: Delete/disable this extra logging facility eventually.
public class Log {
    private static final String TAG = "Log";

    // true to enable verbose logging (informationals).
    public static boolean ok = false;

    public static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
        put('I', tag, msg, null);
    }
    public static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
        put('W', tag, msg, null);
    }
    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg);
        put('E', tag, msg, null);
    }
    public static void e(String tag, String msg, Throwable thr) {
        android.util.Log.e(tag, msg, thr);
        put('E', tag, msg + ": " + thr.getMessage(), thr);
    }
    private static boolean saveToFileToo = true;
    private static final File LOG_FILE = new File(Tracks.FOLDER, "_log.txt");
    private static FileWriter fw;
    private static final SimpleDateFormat sdf =
            new SimpleDateFormat(" yyyy-MM-dd HH:mm:ss.SSS ", Locale.US);
    private static long lastMs, lastNs;
    private synchronized static void put(char level, String tag, String msg,
                Throwable thr) {
        if (!saveToFileToo)
            return;
        try {
            if (fw == null) {
                if (LOG_FILE.length() >= 1024 * 1024) {
                    //move(LOG_FILE + ".3", LOG_FILE + ".4");
                    //move(LOG_FILE + ".2", LOG_FILE + ".3");
                    //move(LOG_FILE + ".1", LOG_FILE + ".2");
                    move(LOG_FILE.toString(), LOG_FILE + ".1");
                }
                fw = new FileWriter(LOG_FILE, true); // true: append
            }
            Date d = new Date();
            long nowMs = d.getTime();
            long nowNs = System.nanoTime();
            if (lastMs == 0)
                lastMs = nowMs;
            if (lastNs == 0)
                lastNs = nowNs;
            fw.write(level);
            fw.write(sdf.format(d));
            fw.write(Long.toString(nowMs - lastMs));
            fw.write(' ');
            fw.write(Long.toString(nowNs));
            fw.write(' ');
            fw.write(Double.toString((nowNs - lastNs) / 1e6));
            fw.write(' ');
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
            lastMs = nowMs;
            lastNs = nowNs;
        }
        catch (IOException ex) {
            android.util.Log.e(tag, "IOException", ex);
            saveToFileToo = false;
        }
    }
    private static void move(String from, String to) {
        File f = new File(from);
        File t = new File(to);
        if (f.renameTo(t))
            android.util.Log.i(TAG, "Renamed " + f + " to " + t);
        else
            android.util.Log.e(TAG, "Could not rename " + f + " to " + t);
    }
}
