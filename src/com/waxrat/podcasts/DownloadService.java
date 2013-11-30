package com.waxrat.podcasts;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;

public final class DownloadService extends IntentService {

    final static String TAG = "DownloadService";

    final static File INCOMING_FOLDER = Tracks.FOLDER;
    final static String HOME_NETWORK = "montana";
    final static String UPDATE_URL = "http://marcy.waxrat.com:1030/podcasts.php";
    final static String PASSWORD = "sekret";
    final static long INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    public static final String DOWNLOAD_STATE_INTENT =
        "com.waxrat.podcasts.intent.DOWNLOAD_STATE";

    public DownloadService() {
        super(TAG);
    }

    /** Keep track of whether we're running. Android wouldn't deliver a second
        intent while the first was still being handled, so this counter
        should only ever be 0 or 1. */
    private static int running = 0;

    public static boolean isRunning() {
        return running != 0;
    }

    static void schedule(Context context) {
        AlarmManager mgr = (AlarmManager)
            context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, DownloadService.class);
        PendingIntent pi = PendingIntent.getService(context, 0, i,
            PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            //if (Log.ok) Log.i(TAG, "schedule: PendingIntent already exists");
            return;
        }
        pi = PendingIntent.getService(context, 0, i, 0);
        if (Log.ok) Log.i(TAG, "schedule: New PendingIntent: " + pi);
        mgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pi);
    }

    private final class Download {
        String name;
        long size;

        Download(String name, long size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public String toString() {
            return name.toString();
        }
    }

    private final int NOTIFY_ID = 10789;

    private void notification(String text, int smallIcon) {
        if (Log.ok) Log.i(TAG, "Notification: " + text);
        Notification.Builder nb = new Notification.Builder(this);
        nb.setAutoCancel(true);
        nb.setContentTitle(getString(R.string.app_title));
        nb.setContentText(text);
        nb.setSmallIcon(smallIcon);
        nb.setTicker(text);
        NotificationManager mgr = (NotificationManager)
            getSystemService(NOTIFICATION_SERVICE);
        mgr.notify(NOTIFY_ID, nb.build());
    }

    /* Send a broadcast intent telling our status.  If our activity is running,
       the activity can display the message.  Otherwise, the message is lost
       except in the logs, which is presumed to be okay (e.g., if we're
       running because AlarmManager invoked us). */
    private static void announce(Context context, boolean fromActivity, String message) {
        if (Log.ok) Log.i(TAG, "Announce: " + message);
        if (fromActivity) {
            Intent i = new Intent(DOWNLOAD_STATE_INTENT);
            i.putExtra("message", message);
            context.sendBroadcast(i);
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (Log.ok) Log.i(TAG, "onHandleIntent entry " + running);
        boolean fromActivity = intent.getBooleanExtra("from-activity", false);

        PowerManager.WakeLock wakeLock = null;
        try {
            ++running;
            if (!checkWifi(this, fromActivity))
                return;

            PowerManager power = (PowerManager)
                getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire();

            Download[] downloads = filesToDownload(fromActivity);
            if (downloads == null)
                return;
            notification(downloads.length == 1 ?
                "One podcast to download" :
                downloads.length + " podcasts to download",
                android.R.drawable.stat_sys_download);
            INCOMING_FOLDER.mkdirs();
            int numDownloaded = 0;
            for (Download download : downloads) {
                if (!checkWifi(this, fromActivity))
                    break;
                // TODO: Check available disk space
                if (!downloadFile(download))
                    break;
                removeFromServer(download.name);
                numDownloaded++;
            }
            if (numDownloaded == downloads.length)
                notification(numDownloaded == 1 ?
                            "Downloaded 1 podcast" :
                            "Downloaded " + numDownloaded + " podcasts",
                            R.drawable.ic_stat_downloaded);
            else
                notification("Downloaded " + numDownloaded + " of " +
                        downloads.length + " podcasts, had trouble",
                        R.drawable.ic_stat_downloaded);
            if (Log.ok) Log.i(TAG, "Telling MusicService to re-scan the folder");
            startService(new Intent(MusicService.ACTION_RESTORE));
        }
        catch (Exception e) {
            Log.e(TAG, "Exception " + e.getMessage(), e);
            notification("Download failed: " + e.getMessage(),
                    android.R.drawable.stat_notify_error);
        }
        finally {
            --running;
            if (wakeLock != null)
                wakeLock.release();
            if (Log.ok) Log.i(TAG, "onHandleIntent exit " + running);
        }
    }

    public static boolean isWifi(Context context, boolean announceOk) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) {
            announce(context, announceOk, "Won't download, no Wi-Fi info");
            return false;
        }
        String ssid = info.getSSID();
        if (ssid == null || ssid.length() == 0) {
            announce(context, announceOk, "Won't download, no Wi-Fi network");
            return false;
        }
        if (Log.ok) Log.i(TAG, "On Wi-Fi - okay");
        return true;
    }

    public static boolean isHome(Context context, boolean announceOk) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) {
            announce(context, announceOk, "Won't download, no Wi-Fi info");
            return false;
        }
        String ssid = info.getSSID();
        if (ssid == null || ssid.length() == 0) {
            announce(context, announceOk, "Won't download, no Wi-Fi network");
            return false;
        }
        if (!ssid.equals(HOME_NETWORK)) {
            announce(context, announceOk, "Won't download, Wi-Fi network " + ssid +
                    " is not " + HOME_NETWORK);
            return false;
        }
        if (Log.ok) Log.i(TAG, "On home Wi-Fi - okay");
        return true;
    }

    private boolean checkWifi(Context context, boolean fromActivity) {
        if (fromActivity) {
            if (Log.ok) Log.i(TAG, "Invoked from activity, okay to proceed regardless of Wi-Fi");
            return true;
        }
        return isHome(context, false);
    }

    private Download[] filesToDownload(boolean fromActivity) {
        // Check the web server to see if new tracks are available.
        if (Log.ok) Log.i(TAG, "Checking server for new tracks");
        BufferedReader br = null;
        ArrayList<Download> ret = new ArrayList<Download>();
        try {
            URL url = new URL(UPDATE_URL + "?p=" + urlEncode(PASSWORD));
            HttpURLConnection h = (HttpURLConnection) url.openConnection();
            h.setRequestMethod("GET");
            h.setReadTimeout(15000);
            h.connect();
            br = new BufferedReader(new InputStreamReader(h.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (Log.ok) Log.i(TAG, "Line |" + line + '|');
                if (line.equals("OK"))
                    continue;
                String[] fields = line.split("\t");
                if (fields.length != 2) {
                    if (Log.ok) Log.i(TAG, "Wrong number of fields " + fields.length +
                            " in |" + line + '|');
                    continue;
                }
                long size = Long.valueOf(fields[0]);
                String name = fields[1];
                ret.add(new Download(name, size));
            }
            if (Log.ok) Log.i(TAG, "New tracks on server: " + ret.size());
        }
        catch (Exception ex) {
            Log.e(TAG, "Exception retrieving update info", ex);
            announce(this, fromActivity, "Oops, try again");
            return null;
        }
        finally {
            if (br != null)
                try {
                    br.close();
                }
                catch (IOException ex) {
                    Log.e(TAG, "Exception closing reader", ex);
                }
        }
        if (ret.isEmpty()) {
            announce(this, fromActivity, "No podcasts to download");
            return null;
        }
        return ret.toArray(new Download[ret.size()]);
    }

    /* Pull the file from the server and move it into Tracks.FOLDER.
       If successful, return true. */
    private boolean downloadFile(Download file) {
        File dest = new File(INCOMING_FOLDER, file.name + ".INCOMING");
        if (!dest.exists())
            if (Log.ok) Log.i(TAG, "Destination does not already exist: " + dest);
        else if (dest.delete())
            if (Log.ok) Log.i(TAG, "Deleted pre-existing destination file: " + dest);
        else {
            if (Log.ok) Log.i(TAG, "Could not delete pre-existing destination file: " + dest);
            return false;
        }

        BufferedInputStream in = null;
        FileOutputStream out = null;
        long downloadedSize = 0;
        try {
            out = new FileOutputStream(dest);

            URL url = new URL(UPDATE_URL + "?get=" + urlEncode(file.name) +
                    "&p=" + urlEncode(PASSWORD));
            if (Log.ok) Log.i(TAG, "Download track: " + url);
            HttpURLConnection h = (HttpURLConnection) url.openConnection();
            h.setRequestMethod("GET");
            h.setReadTimeout(15000);
            h.connect();
            in = new BufferedInputStream(h.getInputStream());

            byte data[] = new byte[1024];
            int count;
            while ((count = in.read(data, 0, 1024)) != -1) {
                out.write(data, 0, count);
                downloadedSize += count;
            }
        }
        catch (Exception ex) {
            Log.e(TAG, "Exception downloading " + file, ex);
            return false;
        }
        finally {
            if (in != null)
                try {
                    in.close();
                }
                catch (IOException ex) {
                    Log.e(TAG, "Exception closing reader", ex);
                    return false;
                }
            if (out != null)
                try {
                    out.close();
                }
                catch (IOException ex) {
                    Log.e(TAG, "Exception closing output file " + dest, ex);
                    return false;
                }
        }

        if (downloadedSize != file.size) {
            Log.e(TAG, "Downloaded " + downloadedSize + " bytes, expected " +
                    file.size + " for " + file);
            if (!dest.delete())
                Log.w(TAG, "Couldn't delete " + dest);
            return false;
        }
        if (Log.ok) Log.i(TAG, "Download successful: " + file);

        File installed = new File(Tracks.FOLDER, file.name);
        if (!dest.renameTo(installed)) {
            Log.w(TAG, "Couldn't rename " + dest + " to " + installed);
            if (!dest.delete())
                Log.w(TAG, "Couldn't delete " + dest);
            return false;
        }
        if (Log.ok) Log.i(TAG, "Renamed " + dest + " to " + installed);
        return true;
    }

    private void removeFromServer(String name) {
        if (Log.ok) Log.i(TAG, "removeFromServer " + name);
        BufferedReader br = null;
        try {
            URL url = new URL(UPDATE_URL + "?rm=" + urlEncode(name) +
                    "&p=" + urlEncode(PASSWORD));
            if (Log.ok) Log.i(TAG, "removeFromServer " + url);
            HttpURLConnection h = (HttpURLConnection) url.openConnection();
            h.setRequestMethod("GET");
            h.setReadTimeout(15000);
            h.connect();
            br = new BufferedReader(new InputStreamReader(h.getInputStream()));
            String line;
            boolean ok = false;
            while ((line = br.readLine()) != null) {
                if (line.equals("OK")) {
                    ok = true;
                    continue;
                }
                Log.w(TAG, "removeFromServer: Unexpected line: " + line);
            }
            if (ok) {
                if (Log.ok) Log.i(TAG, "Deleted from server: " + name);
            } else
                Log.w(TAG, "Not OK deleting " + name);
        }
        catch (Exception ex) {
            Log.e(TAG, "Exception in removeFromServer " + ex.getMessage(), ex);
        }
        finally {
            if (br != null)
                try {
                    br.close();
                }
                catch (IOException ex) {
                    Log.e(TAG, "Exception closing reader", ex);
                }
        }
    }

    private String urlEncode(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "ISO-8859-1");
    }
}
