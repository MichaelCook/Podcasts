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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class Downloader extends Worker {

    private final static String TAG = "Podcasts.Downloader";

    static final String ACTION_DOWNLOAD_MESSAGE = "com.waxrat.podcasts.intent.DOWNLOAD_MESSAGE";
    static final String COMMAND_EXTRA = "command";
    static final String COMMAND_DOWNLOAD = "download";
    static final String COMMAND_DELETE_TRACK = "delete-track";
    static final String FORCE_EXTRA = "force"; // download even if not on Wi-Fi
    static final String MAX_TRACKS_EXTRA = "max-tracks";
    static final String THEN_START_EXTRA = "then-start";
    static final String IDENT_EXTRA = "ident";

    static final String ACTION_DOWNLOAD_STATUS = "com.waxrat.podcasts.intent.DOWNLOAD_STATUS";
    static final String IDLE_STATUS = "idle";
    static final String POLLING_STATUS = "polling";
    static final String DOWNLOADING_STATUS = "downloading";
    static final String STATUS_STATUS_EXTRA = "status";
    static final String TRACK_NUM_STATUS_EXTRA = "track-num";
    static final String NUM_TRACKS_STATUS_EXTRA = "num-tracks";

    private static void broadcastStatus(@NonNull Context context,
            @NonNull String status, int trackNum, int numTracks) {
        Intent i = new Intent(ACTION_DOWNLOAD_STATUS);
        i.putExtra(STATUS_STATUS_EXTRA, status);
        i.putExtra(TRACK_NUM_STATUS_EXTRA, trackNum);
        i.putExtra(NUM_TRACKS_STATUS_EXTRA, numTracks);
        context.sendBroadcast(i);
    }

    private static void broadcastStatus(@NonNull Context context, @NonNull String status) {
        broadcastStatus(context, status, -1, -1);
    }

    public Downloader(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    /** Keep track of whether we're running. Android wouldn't deliver a second
        intent while the first was still being handled, so this counter
        should only ever be 0 or 1. */
    private static int running = 0;

    public static boolean isRunning() {
        return running != 0;
    }

    private static NotificationManager mNotificationManager;

    static boolean mAutoDownloadOnWifi = true;

    static void downloadNow(@NonNull Context context,
                            @NonNull String label,
                            boolean force, // download even if not on Wi-Fi
                            int maxTracks,
                            boolean thenStart,
                            @Nullable String onlyIdent) {
        Log.i(TAG, "downloadNow...");
        Data.Builder db = new Data.Builder();
        db.putString(COMMAND_EXTRA, COMMAND_DOWNLOAD);
        if (force)
            db.putBoolean(FORCE_EXTRA, true);
        if (maxTracks != -1)
            db.putInt(MAX_TRACKS_EXTRA, maxTracks);
        if (thenStart)
            db.putBoolean(THEN_START_EXTRA, true);
        if (onlyIdent != null)
            db.putString(IDENT_EXTRA, onlyIdent);
        WorkRequest request =
                new OneTimeWorkRequest.Builder(Downloader.class)
                        .setInputData(db.build())
                        .addTag(label)
                        .build();
        WorkManager
                .getInstance(context.getApplicationContext())
                .enqueue(request);
        Log.i(TAG, "downloadNow...done");
    }

    static void deleteTrack(@NonNull Context context,
                            @NonNull String label,
                            @NonNull String ident) {
        Log.i(TAG, "deleteTrack...");
        Data data = new Data.Builder()
                .putString(COMMAND_EXTRA, COMMAND_DELETE_TRACK)
                .putString(IDENT_EXTRA, ident)
                .build();
        WorkRequest request =
                new OneTimeWorkRequest.Builder(Downloader.class)
                        .setInputData(data)
                        .addTag(label)
                        .build();
        WorkManager
                .getInstance(context.getApplicationContext())
                .enqueue(request);
        Log.i(TAG, "deleteTrack...done");
    }

    static final String UNIQUE_WORK_NAME = "DOWNLOAD";

    static void schedule(@NonNull Context context, @NonNull String label) {
        Log.i(TAG, "schedule: " + label);
        WorkManager wm = WorkManager.getInstance(context.getApplicationContext());
        wm.cancelUniqueWork(UNIQUE_WORK_NAME);
        Data data = new Data.Builder()
                .putString(COMMAND_EXTRA, COMMAND_DOWNLOAD)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(Downloader.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .setInputData(data)
                        .addTag(label)
                        .build();
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request);
        Log.i(TAG, "schedule done: " + label);
    }

    private static final String DOWNLOADING_CHANNEL_ID = "downloading-channel";

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(DOWNLOADING_CHANNEL_ID, "Downloading",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Downloading tracks");
        mNotificationManager.createNotificationChannel(channel);
    }

    private static final int NOTIFY_DOWNLOADING = 101;
    private static final int NOTIFY_TROUBLE = 303;

    private void notification(@NonNull Context context, int id, @NonNull String text, int smallIcon) {
        notification(context, id, context.getString(R.string.app_title), text, smallIcon);
    }

    @NonNull
    private NotificationCompat.Builder notification(@NonNull Context context, int id, @NonNull String title, @NonNull String text, int smallIcon) {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, DOWNLOADING_CHANNEL_ID);
        if (id == NOTIFY_DOWNLOADING)
            nb.setOngoing(true); // not cancelable
        else
            nb.setAutoCancel(true);
        nb.setContentTitle(title);
        nb.setContentText(text);
        nb.setSmallIcon(smallIcon);
        nb.setTicker(text);

        // Launch MainActivity when the user selects this notification.
        Intent in = new Intent(context, MainActivity.class);
        PendingIntent pin = PendingIntent.getActivity(context, 0, in, PendingIntent.FLAG_IMMUTABLE);
        nb.setContentIntent(pin);

        mNotificationManager.notify(id, nb.build());
        return nb;
    }

    /* Send a broadcast intent telling our status.  If our activity is running,
       the activity can display the message.  Otherwise, the message is lost
       except in the logs, which is presumed to be okay (e.g., if we're
       running because AlarmManager invoked us). */
    private static void announce(@NonNull Context context, @NonNull String message) {
        Intent i = new Intent(ACTION_DOWNLOAD_MESSAGE);
        i.putExtra("message", message);
        context.sendBroadcast(i);
    }

    private static class TagsComparator implements Comparator<Tags> {
        public int compare(@NonNull Tags a, @NonNull Tags b) {
            String ap = a.priority;
            String bp = b.priority;
            int d = ap.compareTo(bp);
            if (d != 0)
                return d;
            return a.ident.compareTo(b.ident);
        }
    }
    private static final TagsComparator TAGS_COMPARATOR = new TagsComparator();

    private void downloadTags(@NonNull Context context) {
        Tags[] downloads;
        try {
            broadcastStatus(context, POLLING_STATUS);

            mNotificationManager.cancelAll();
            notification(context, NOTIFY_DOWNLOADING, "Checking...",
                    android.R.drawable.stat_sys_download);

            downloads = tagsToDownload(context);
        }
        catch (Exception e) {
            Note.e(TAG, "checkDownloads - exception", e);
            notification(context, NOTIFY_TROUBLE, "Check failed: " + e.getMessage(),
                    android.R.drawable.stat_notify_error);
            return;
        }
        finally {
            broadcastStatus(context, IDLE_STATUS);
            mNotificationManager.cancel(NOTIFY_DOWNLOADING);
        }

        if (downloads == null)
            return;

        Arrays.sort(downloads, TAGS_COMPARATOR);

        for (Tags download : downloads)
            download.writeFile(context, false);
        Tracks.restore(context, true);
    }

    private void downloadAudios(@NonNull Context context,
                                boolean force,
                                int maxToDownload,
                                boolean thenStart,
                                @Nullable String onlyIdent) {
        if (!force && downloadDiscouraged(context)) {
            return;
        }
        try {
            int numToDownload = 0;
            int numDownloaded = 0;
            boolean trouble = false;
            if (onlyIdent != null) {
                numToDownload = 1;
                Track track = Tracks.findTrackByIdent(onlyIdent);
                if (track == null) {
                    Log.w(TAG, "No such track: " + onlyIdent);
                    trouble = true;
                }
                else if (track.downloaded) {
                    Log.i(TAG, "Track is already downloaded: " + track);
                }
                else if (!downloadAudio(context, track, thenStart, numDownloaded + 1, numToDownload)) {
                    trouble = true;
                }
                else {
                    ++numDownloaded;
                }
            }
            else {
                Track[] downloadable = Tracks.downloadable();
                if (downloadable == null)
                    Log.i(TAG, "No tracks to download");
                else {
                    numToDownload = downloadable.length;
                    for (Track track : downloadable) {
                        if (maxToDownload != -1 && numDownloaded >= maxToDownload) {
                            Log.i(TAG, "Reached download limit");
                            break;
                        }
                        if (!force && downloadDiscouraged(context)) {
                            trouble = true; // Lost Wi-Fi while we were downloading
                            break;
                        }
                        if (!downloadAudio(context, track, thenStart, numDownloaded + 1, numToDownload)) {
                            trouble = true;
                            break;
                        }
                        thenStart = false;
                        ++numDownloaded;
                    }
                }
            }

            if (trouble) {
                StringBuilder msg = new StringBuilder();
                msg.append("Downloaded ").append(numDownloaded);
                if (numDownloaded != numToDownload)
                    msg.append(" of ").append(numToDownload);
                msg.append(" track");
                if (numToDownload != 1)
                    msg.append('s');
                notification(context, NOTIFY_TROUBLE, msg.toString(), R.drawable.ic_stat_trouble);
            }
        }
        catch (Exception e) {
            Note.e(TAG, "checkDownloads - exception", e);
            notification(context, NOTIFY_TROUBLE, "Download failed: " + e.getMessage(),
                    android.R.drawable.stat_notify_error);
        }
        finally {
            mNotificationManager.cancel(NOTIFY_DOWNLOADING);
        }
    }

    private void checkDownloads(@NonNull Context context, boolean force, int maxToDownload,
                                boolean thenStart, @Nullable String onlyIdent) {
        Log.i(TAG, "checkDownload: maxToDownload=" + maxToDownload + ", thenStart=" + thenStart);

        downloadTags(context);
        downloadAudios(context, force, maxToDownload, thenStart, onlyIdent);
    }

    private void doCommand() {
        Log.i(TAG, "doCommand...");
        String command = getInputData().getString(COMMAND_EXTRA);
        if (command == null) {
            // TODO: Why doesn't setInputData work for PeriodicWorkRequest
            Log.i(TAG, "doCommand: No command, assuming PeriodicWorkRequest");
            command = COMMAND_DOWNLOAD;
        }
        switch (command) {
            case COMMAND_DOWNLOAD:
                commandDownload(this.getApplicationContext());
                break;
            case COMMAND_DELETE_TRACK:
                commandDeleteTrack(this.getApplicationContext());
                break;
            default:
                Note.e(TAG, "Invalid command: " + command);
                break;
        }
        Log.i(TAG, "doCommand...done");
    }

    @Override
    @NonNull
    public Result doWork() {
        Log.i(TAG, "doWork: Start " + getTags());
        synchronized (Downloader.class) {
            doCommand();
        }
        Log.i(TAG, "doWork: Finished");
        return Result.success();
    }

    private void commandDownload(@NonNull Context context) {
        Data data = getInputData();

        // max-tracks: The maximum number of tracks to download. -1 means no maximum.
        // Otherwise, download no more than this number
        int maxTracks = data.getInt(Downloader.MAX_TRACKS_EXTRA, -1);

        // force: Download tracks even if not on Wi-Fi
        boolean force = data.getBoolean(Downloader.FORCE_EXTRA, false);

        Log.i(TAG, "commandDownload force=" + force + ", maxTracks=" + maxTracks);

        if (!force && downloadDiscouraged(context))
            maxTracks = 0;

        // then-start: If true, then after finishing the download, start playing the 1st track
        boolean thenStart = data.getBoolean(THEN_START_EXTRA, false);

        // ident: If set, then download only this track
        String ident = data.getString(IDENT_EXTRA);

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null)
            return;
        PowerManager.WakeLock wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Podcasts:DownloadService");
        if (wakeLock == null)
            return;
        try {
            ++running;
            if (running != 1)
                Note.w(TAG, "Oops, running=" + running);
            else {
                wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
                checkDownloads(context, force, maxTracks, thenStart, ident);
            }
        }
        finally {
            --running;
            wakeLock.release();
        }
    }

    private void commandDeleteTrack(@NonNull Context context) {
        Data data = getInputData();

        String ident = data.getString(IDENT_EXTRA);
        if (ident == null) {
            Note.w(TAG, "commandDeleteTrack: No ident specified");
            return;
        }
        Track track = Tracks.findTrackByIdent(ident);
        if (track == null) {
            Note.w(TAG, "commandDeleteTrack: No such track: " + ident);
            return;
        }
        Log.i(TAG, "commandDeleteTrack: " + ident);
        if (!track.downloaded)
            removeFromServer(context, ident);
        track.deleteFiles(context);
        Tracks.restore(context, true);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isWifi(@NonNull Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return false; // Don't know, assume no
        return !cm.isActiveNetworkMetered();
    }

    static boolean downloadDiscouraged(@NonNull Context context) {
        if (!isWifi(context)) {
            Log.i(TAG, "Not on Wi-Fi, download discouraged");
            return true;
        }
        if (!mAutoDownloadOnWifi) {
            Log.i(TAG, "Not autoDownloadOnWifi, download discouraged");
            return true;
        }
        Log.i(TAG, "On Wi-Fi, download not discouraged");
        return false;
    }

    private final static String UPDATE_URL = "http://www.waxrat.com/podcasts.php";

    @NonNull
    private static URL makePollUrl(@NonNull Context context, int since) throws MalformedURLException {
        StringBuilder spec = new StringBuilder();
        spec.append(UPDATE_URL);
        spec.append("?p=");
        spec.append(Utilities.urlEncode(Utilities.password(context)));
        if (Tracks.haveTracks()) {
            spec.append("&r=");
            spec.append((Tracks.remMs() + 500) / 1000);
        }
        if (since != -1) {
            spec.append("&s=");
            spec.append(since);
        }
        return new URL(spec.toString());
    }

    @NonNull
    private static URL makeGetUrl(@NonNull Context context, @NonNull String ident) throws MalformedURLException {
        return new URL(UPDATE_URL + "?get=" + Utilities.urlEncode(ident) +
            "&p=" + Utilities.urlEncode(Utilities.password(context)));
    }

    @NonNull
    private static URL makeRemoveUrl(@NonNull Context context, @NonNull String ident) throws MalformedURLException {
        return new URL(UPDATE_URL + "?rm=" + Utilities.urlEncode(ident) +
            "&p=" + Utilities.urlEncode(Utilities.password(context)));
    }

    /* The timestamp (POSIX time - seconds since 1970) of the last .tag file seen on the server */
    private static int since = -1;

    @Nullable
    private Tags[] tagsToDownload(@NonNull Context context) {
        // Check the web server to see if new tracks are available.
        BufferedReader br = null;
        ArrayList<Tags> downloads = new ArrayList<>();
        int tracksOnServer = -1;
        try {
            TcpService.broadcast(TcpService.NFY_POLLING_FOR_TRACKS, "START");
            URL url = makePollUrl(context, since);
            Log.i(TAG, "URL " + url);
            HttpURLConnection h = (HttpURLConnection) url.openConnection();
            h.setRequestMethod("GET");
            h.setReadTimeout(15000);
            h.connect();
            String contentType = h.getContentType();
            if (contentType == null) {
                Log.w(TAG, "No Content-Type");
                announce(context, "Oops, no content type");
                return null;
            }
            if (!contentType.equals("text/plain") && !contentType.startsWith("text/plain;")) {
                /* This might happen if we're connected to a Wi-Fi captive portal
                   and haven't authorized yet. */
                Note.e(TAG, "Wrong Content-Type |" + contentType + '|');
                announce(context, "Oops, wrong content type: " + contentType);
                return null;
            }
            /*
              The podcasts.php script delivers to us the contents of all .tag
              files as constructed by pod-feed.  Consecutive tag files are
              separated by a single blank line.  The end of the output is the
              line "OK" to help us ensure we got the whole output.
             */
            br = new BufferedReader(new InputStreamReader(h.getInputStream()));
            ArrayList<String> tagLines = new ArrayList<>();
            @SuppressWarnings("UnusedAssignment") int newest = -1;
            for (;;) {
                String line = br.readLine();
                if (line == null) {
                    /* This, too, indicates we're not connected to the server we expect */
                    Note.e(TAG, "No OK");
                    announce(context, "Oops, no OK");
                    return null;
                }
                if (line.startsWith("OK\t")) {
                    newest = Integer.parseInt(line.substring(3));
                    line = br.readLine();
                    if (line != null) {
                        /* This, too, indicates we're not connected to the server we expect */
                        Note.e(TAG, "Extra output |" + line + '|');
                        announce(context, "Oops, extra: " + line);
                        return null;
                    }
                    if (!tagLines.isEmpty()) {
                        downloads.add(new Tags(tagLines));
                        tagLines.clear();
                    }
                    break;
                }
                if (line.isEmpty()) {
                    downloads.add(new Tags(tagLines));
                    tagLines.clear();
                    continue;
                }
                tagLines.add(line);
            }

            // If newest==-1, then there are no tracks on the server
            Log.i(TAG, "newest=" + newest);
            since = newest;

            tracksOnServer = downloads.size();
            if (tracksOnServer != 0)
                Log.i(TAG, "New tracks on server: " + tracksOnServer);
            else
                Log.i(TAG, "No tracks on server");
        }
        catch (java.net.UnknownHostException ex) {
            // Likely transient networking problem
            Log.i(TAG, "UnknownHostException");
            announce(context, "Unknown host, try again");
            return null;
        }
        catch (java.net.ConnectException ex) {
            // Likely transient networking problem
            Log.i(TAG, "ConnectException");
            announce(context, "Connect failed, try again");
            return null;
        }
        catch (java.net.SocketException ex) {
            // Likely transient networking problem
            Log.i(TAG, "SocketException");
            announce(context, "Socket failed, try again");
            return null;
        }
        catch (java.net.SocketTimeoutException ex) {
            // Likely transient networking problem
            Log.i(TAG, "SocketTimeoutException");
            announce(context, "Socket timeout, try again");
            return null;
        }
        catch (Exception ex) {
            Note.e(TAG, "Exception getting track list", ex);
            announce(context, "Oops, try again");
            return null;
        }
        finally {
            if (br != null)
                try {
                    br.close();
                }
                catch (IOException ex) {
                    Note.e(TAG, "Exception closing reader", ex);
                }
            TcpService.broadcast(TcpService.NFY_POLLING_FOR_TRACKS, "FINISH", String.valueOf(tracksOnServer));
        }
        Log.i(TAG, "Tags to download: " + downloads.size());
        if (downloads.isEmpty())
            return null;
        return downloads.toArray(new Tags[0]);
    }

    /* Pull the audio file from the server and move it into Tracks.FOLDER.
       If successful, return true. */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean downloadAudio(@NonNull Context context, @NonNull Track track,
                                  boolean thenStart, int downloadIndex, int numDownloads) {
        Log.i(TAG, "Download " + track);

        if (track.downloaded) {
            Log.w(TAG, "Track already downloaded: " + track);
            return false;
        }

        String ident = track.ident;
        File folder = Utilities.getFolder(context);
        File dest = new File(folder, "INCOMING");
        if (!dest.exists())
            Log.d(TAG, "Destination does not already exist: " + dest);
        else if (dest.delete())
            Log.w(TAG, "Deleted pre-existing destination download: " + dest);
        else {
            Note.w(TAG, "Could not delete pre-existing destination file: " + dest);
            return false;
        }

        broadcastStatus(context, DOWNLOADING_STATUS, downloadIndex, numDownloads);

        NotificationCompat.Builder nb = notification(context, NOTIFY_DOWNLOADING, "Get...",
                (downloadIndex) + " of " + numDownloads + ": " + track.artist,
                android.R.drawable.stat_sys_download);

        TcpService.broadcast(TcpService.NFY_DOWNLOADING_TRACK, "START",
                String.valueOf(downloadIndex), String.valueOf(numDownloads),
                Utilities.orElse(track.artist, "(artist)"),
                Utilities.orElse(track.title, "(title)"));

        BufferedInputStream bis = null;
        FileOutputStream out = null;
        long downloadedSize = 0;
        try {
            out = new FileOutputStream(dest);

            URL url = makeGetUrl(context, track.ident);
            Log.i(TAG, "Download track: " + url);
            HttpURLConnection h = (HttpURLConnection) url.openConnection();
            h.setRequestMethod("GET");
            h.setReadTimeout(30000);
            h.connect();
            bis = new BufferedInputStream(h.getInputStream());

            final int chunk = 10240;
            byte[] data = new byte[chunk];
            int count;
            int lastPercentage = 0;
            while ((count = bis.read(data, 0, chunk)) != -1) {
                out.write(data, 0, count);
                downloadedSize += count;

                int percentage = (int) (100 * downloadedSize / track.size);
                if (percentage != lastPercentage) {
                    lastPercentage = percentage;
                    nb.setProgress(100, percentage, false);
                    mNotificationManager.notify(NOTIFY_DOWNLOADING, nb.build());
                }
            }
        }
        catch (java.net.UnknownHostException ex) {
            // Likely transient networking problem
            Log.w(TAG, "UnknownHostException downloading " + track);
            return false;
        }
        catch (java.net.ConnectException ex) {
            // Likely transient networking problem
            Log.w(TAG, "ConnectException downloading " + track);
            return false;
        }
        catch (java.net.SocketTimeoutException ex) {
            // Likely transient networking problem
            Log.w(TAG, "Timeout downloading " + track);
            return false;
        }
        catch (java.net.SocketException ex) {
            // Likely transient networking problem
            Log.w(TAG, "Socket exception downloading " + track);
            return false;
        }
        catch (Exception ex) {
            Note.e(TAG, "Exception downloading " + track, ex);
            return false;
        }
        finally {
            if (bis != null)
                try {
                    bis.close();
                }
                catch (IOException ex) {
                    Note.e(TAG, "Exception closing reader", ex);
                }
            if (out != null)
                try {
                    out.close();
                }
                catch (IOException ex) {
                    Note.e(TAG, "Exception closing output download " + dest, ex);
                }
            broadcastStatus(context, IDLE_STATUS);
            TcpService.broadcast(TcpService.NFY_DOWNLOADING_TRACK, "FINISH",
                    String.valueOf(track.size),
                    String.valueOf(downloadedSize));
        }

        if (downloadedSize != track.size) {
            Note.e(TAG, "Downloaded " + downloadedSize + " bytes, expected " +
                    track.size + " for " + track);
            if (!dest.delete())
                Note.w(TAG, "Couldn't delete " + dest);
            return false;
        }
        Log.i(TAG, "Download successful: " + track);

        File installed = new File(folder, track.ident + ".mp3");
        if (!dest.renameTo(installed)) {
            Note.w(TAG, "Couldn't rename " + dest + " to " + installed);
            if (!dest.delete())
                Note.w(TAG, "Couldn't delete " + dest);
            return false;
        }
        Log.i(TAG, "Renamed " + dest + " to " + installed);

        Tracks.restore(context, true);

        if (thenStart) {
            Intent in = new Intent(MusicService.ACTION_PLAY);
            in.setPackage(Utilities.PACKAGE);
            in.putExtra("ident", ident);
            // TODO: Fix this. Use startForegroundService or some such, don't try/catch
            try {
                context.startService(in);
            }
            catch (IllegalStateException exc) {
                // Not allowed to start service Intent: app is in background
                Note.w(TAG, "downloadFile: Couldn't start service: " + exc.getMessage());
            }
        }
        removeFromServer(context, track.ident);
        return true;
    }

    private void removeFromServer(@NonNull Context context, @NonNull String ident) {
        Log.i(TAG, "removeFromServer " + ident);
        BufferedReader br = null;
        try {
            URL url = makeRemoveUrl(context, ident);
            Log.i(TAG, "removeFromServer " + url);
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
                Note.w(TAG, "removeFromServer: Unexpected line: " + line);
            }
            if (ok)
                Log.i(TAG, "Deleted from server: " + ident);
            else
                Note.w(TAG, "Not OK deleting " + ident);
        }
        catch (java.net.ConnectException ex) {
            // Likely transient networking problem
            Note.w(TAG, "ConnectException in removeFromServer");
        }
        catch (java.net.SocketTimeoutException ex) {
            // Likely transient networking problem
            Note.w(TAG, "Timeout in removeFromServer");
        }
        catch (Exception ex) {
            Note.e(TAG, "Exception in removeFromServer " + ex.getMessage(), ex);
        }
        finally {
            if (br != null)
                try {
                    br.close();
                }
                catch (IOException ex) {
                    Note.e(TAG, "Exception closing reader", ex);
                }
        }
    }
}
