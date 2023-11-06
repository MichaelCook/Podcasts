package com.waxrat.podcasts;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;

/* Represent the contents of a .tag file */
class Tags {
    private final static String TAG = "Podcasts.Tags";

    @NonNull final String ident;     // "Here+Now-74395fa9a0cd0a9b9adb3ab28c27f08d"
    @NonNull String priority;        // "F2942"
    int durMs;                       // duration (milliseconds)
    @NonNull String title;           // "Museum to bring hip-hop history to the Bronx"
    @NonNull String artist;          // "Here+Now"
    long size;                       // audio file size (bytes)
    long when;                       // when the track was downloaded (POSIX time, seconds)
    @Nullable Integer[] quiet;       // offsets of periods of quiet (milliseconds)
    @Nullable String emoji;          // "🚀"
    @Nullable String feed_url;
    @Nullable String track_url;

    @Nullable
    static Tags fromFile(@NonNull Context context, @NonNull String ident) {
        File file = Track.getTagFile(context, ident);
        ArrayList<String> lines = Utilities.readFile(file);
        if (lines == null)
            return null;
        Tags t = new Tags(lines);
        if (!t.ident.equals(ident))
            // The "id" field in the file is supposed to be the same as `ident`
            Note.w(TAG, "Ident mismatch " + t.ident + " != " + ident);
        return t;
    }

    Tags(@NonNull ArrayList<String> lines) {
        String self_ident = null;
        String self_priority = null;
        this.durMs = -1;
        String self_title = null;
        String self_artist = null;
        this.size = -1;
        this.when = -1;
        for (String line: lines) {
            int i = line.indexOf('\t');
            if (i == -1) {
                Note.w(TAG, "No tab in tag line '" + line + '\'');
                continue;
            }
            String name = line.substring(0, i);
            String value = line.substring(i + 1);
            switch (name) {
                case "id":
                    self_ident = value;
                    break;
                case "prio":    // TODO: obsolete
                case "priority":
                    self_priority = value;
                    break;
                case "durms":
                    this.durMs = Integer.parseInt(value);
                    break;
                case "title":
                    self_title = value;
                    break;
                case "artist":
                    self_artist = value;
                    break;
                case "size":
                    this.size = Long.parseLong(value);
                    break;
                case "when":
                    this.when = Long.parseLong(value);
                    break;
                case "quiet":
                    this.quiet = Utilities.integersFromString(value);
                    break;
                case "emoji":
                    this.emoji = value;
                    break;
                case "feed_url":
                    this.feed_url = value;
                    break;
                case "track_url":
                    this.track_url = value;
                    break;
                default:
                    Note.w(TAG, "Unknown tag '" + name + "' = '" + value + '\'');
                    break;
            }
        }

        if (self_ident == null)
            throw new RuntimeException("No 'id' field");
        if (self_priority == null)
            throw new RuntimeException("No 'priority' field");
        if (this.durMs == -1)
            throw new RuntimeException("No 'durms' field");
        if (self_title == null)
            throw new RuntimeException("No 'title' field");
        if (self_artist == null)
            throw new RuntimeException("No 'artist' field");
        if (this.size == -1)
            throw new RuntimeException("No 'size' field");
        if (this.when == -1)
            throw new RuntimeException("No 'when' field");

        this.ident = self_ident;
        this.priority = self_priority;
        this.title = self_title;
        this.artist = self_artist;
    }

    final void writeFile(@NonNull Context context, boolean overwrite) {
        File file = Track.getTagFile(context, ident);
        if (!overwrite && file.exists()) {
            Log.i(TAG, "Not overwriting " + file.getName());
            return;
        }

        StringBuilder sb = new StringBuilder();

        sb.append("id\t");
        sb.append(ident);
        sb.append('\n');

        sb.append("priority\t");
        sb.append(priority);
        sb.append('\n');

        sb.append("durms\t");
        sb.append(durMs);
        sb.append('\n');

        sb.append("title\t");
        sb.append(title);
        sb.append('\n');

        sb.append("artist\t");
        sb.append(artist);
        sb.append('\n');

        sb.append("size\t");
        sb.append(size);
        sb.append('\n');

        sb.append("when\t");
        sb.append(when);
        sb.append('\n');

        if (quiet != null) {
            sb.append("quiet\t");
            Utilities.integersToString(quiet, sb);
            sb.append('\n');
        }

        if (emoji != null) {
            sb.append("emoji\t");
            sb.append(emoji);
            sb.append('\n');
        }

        if (feed_url != null) {
            sb.append("feed_url\t");
            sb.append(feed_url);
            sb.append('\n');
        }

        if (track_url != null) {
            sb.append("track_url\t");
            sb.append(track_url);
            sb.append('\n');
        }

        Utilities.writeFile(file, sb);
    }

    public static void setArtist(@NonNull Context context, @NonNull String ident, @NonNull String artist) {
        Tags tags = Tags.fromFile(context, ident);
        if (tags != null) {
            tags.artist = artist;
            tags.writeFile(context, true);
        }
    }

    public static void setEmoji(@NonNull Context context, @NonNull String ident, @NonNull String emoji) {
        Tags tags = Tags.fromFile(context, ident);
        if (tags != null) {
            tags.emoji = emoji;
            tags.writeFile(context, true);
        }
    }

    public static void setPriority(@NonNull Context context, @NonNull String ident, @NonNull String priority) {
        Tags tags = Tags.fromFile(context, ident);
        if (tags != null) {
            tags.priority = priority;
            tags.writeFile(context, true);
        }
    }

    public static void setTitle(@NonNull Context context, @NonNull String ident, @NonNull String title) {
        Tags tags = Tags.fromFile(context, ident);
        if (tags != null) {
            tags.title = title;
            tags.writeFile(context, true);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return ident;
    }
}
