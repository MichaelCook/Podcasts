package com.waxrat.podcasts;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

final class Track {
    private final static String TAG = "Podcasts.Track";

    @NonNull final String ident;
    @NonNull String title;
    @NonNull String artist;
    @NonNull String priority;
    long size;
    int curMs;
    int durMs;
    long when;
    boolean downloaded;    // True if we have the .mp3 audio file for this track
    volatile boolean deleted;    // Set when the user deletes this track, possibly while it's downloading
    final int skipForwardMs, skipBackwardMs;
    @Nullable String pubdate;
    @Nullable String memo;
    @Nullable String feed_url;
    @Nullable String track_url;
    @Nullable int[] quiet;

    Track(@NonNull String ident) {
        this.ident = ident;
        this.title = ident;
        this.artist = "";
        this.priority = "";
        this.size = 0;
        this.curMs = 0;
        this.durMs = 0;
        this.when = 0;
        this.downloaded = false;
        this.deleted = false;
        this.skipForwardMs = 30000;
        this.skipBackwardMs = 10000;
        this.pubdate = null;
        this.memo = null;
        this.feed_url = null;
        this.track_url = null;
        this.quiet = null;
    }

    // The audio file name relative to Utilities.getFolder
    @NonNull
    String audioFileName() {
        return ident + ".mp3";
    }

    // The tag file name relative to Utilities.getFolder
    @NonNull
    static String tagFileName(@NonNull String ident) {
        return ident + ".tag";
    }

    @NonNull
    File getAudioFile(@NonNull Context context) {
        File folder = Utilities.getFolder(context);
        return new File(folder, audioFileName());
    }

    @NonNull
    File getTagFile(@NonNull Context context) {
        return getTagFile(context, ident);
    }

    @NonNull
    static File getTagFile(@NonNull Context context, @NonNull String ident) {
        File folder = Utilities.getFolder(context);
        return new File(folder, tagFileName(ident));
    }

    /* Delete the files of this track */
    void deleteFiles(@NonNull Context context) {
        deleteFile(getAudioFile(context));
        deleteFile(getTagFile(context));
        TcpService.broadcast(TcpService.NFY_TRACK_DELETED, ident);
    }

    static void deleteFile(@Nullable File file) {
        if (file == null)
            return;
        if (file.delete())
            return;
        if (file.exists())
            Note.w(TAG, "Could not delete " + file);
    }

    // The track's priority class as a character, or -1 if the class has no
    // priority (which would be unusual but strictly speaking possible)
    int priorityClassChar() {
        if (priority.isEmpty())
            return -1;
        return priority.charAt(0);
    }

    int remMs() {
        return durMs - curMs;
    }

    boolean isFinished() {
        return remMs() == 0;
    }

    @NonNull
    @Override
    public String toString() {
        return ident + '@' + curMs + '/' + durMs;
    }

    // Find the next quiet period greater than `wanted`.
    // Return the period (milliseconds) or -1 if there is none.
    int nextQuiet(int wanted) {
        if (quiet == null)
            return -1;

        // ChatGPT says if the array is this small then a linear search
        // is competetive or even faster than a binary search.
        if (quiet.length < 16) {
            for (int ms : quiet)
                if (ms > wanted)
                    return ms;
            return -1;
        }

        int lo = 0;
        int hi = quiet.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (quiet[mid] <= wanted)
                lo = mid + 1;
            else
                hi = mid;
        }
        if (lo == quiet.length)
            return -1;
        return quiet[lo];
    }
}
