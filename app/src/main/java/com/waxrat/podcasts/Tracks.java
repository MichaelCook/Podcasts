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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

class Tracks {
    private final static String TAG = "Podcasts.Tracks";

    private final static String STATE_FILE_NAME = "_state.txt";

    /** The last modification time of the tracks folder. */
    private static long folderLastMod = -1;

    /** All the tracks we currently know about */
    private static final List<Track> tracks = new ArrayList<>();

    static final String ACTION_TRACKS_LIST_CHANGED = "com.waxrat.podcasts.intent.TRACKS_LIST_CHANGED";

    /** The track currently selected, or -1 if we haven't read _state.txt yet */
    private static int position = -1;

    static synchronized boolean haveTracks() {
        // True if we have already loaded _state.txt and .tag files into `tracks`
        return position != -1;
    }

    static synchronized int getPositionOfTrack(@NonNull Track t)
    {
        int n = tracks.size();

        // Optimize for the common case
        if (position >= 0 && position < n && t == tracks.get(position))
            return position;

        for (int i = 0; i < n; ++i)
            if (t == tracks.get(i))
                return i;
        return -1;
    }

    static synchronized int numDownloadable() {
        return (int) tracks.stream().filter(track -> !track.downloaded).count();
    }

    @Nullable
    static synchronized Track track(int pos) {
        if (pos < 0 || pos >= tracks.size())
            return null;
        return tracks.get(pos);
    }

    @Nullable
    static synchronized Track currentTrack() {
        return track(position);
    }

    static synchronized int currentPosition() {
        return position;
    }

    interface TrackFunction {
        void apply(Track t);
    }

    static synchronized void forEach(@NonNull TrackFunction fun) {
        for (Track t : tracks)
            fun.apply(t);
    }

    @Nullable
    static synchronized Track[] downloadable() {
        List<Track> found = new ArrayList<>();
        for (Track track: tracks)
            if (!track.downloaded)
                found.add(track);
        if (found.isEmpty())
            return null;
        return found.toArray(new Track[0]);
    }

    @Nullable
    static synchronized Track findTrackByIdent(@NonNull String ident) {
        for (Track t : tracks)
            if (ident.equals(t.ident))
                return t;
        return null;
    }

    static synchronized void selectTrackByIdent(@NonNull String ident) {
        int n = tracks.size();
        for (int i = 0; i < n; ++i) {
            Track t = tracks.get(i);
            if (ident.equals(t.ident)) {
                if (position != i) {
                    Log.i(TAG, "selectTrackByIdent: position " + position +
                            " -> " + i + " for " + t);
                    position = i;
                }
                return;
            }
        }
        Note.w(TAG, "selectTrackByIdent: no such track " + ident);
    }

    @Nullable
    static synchronized Track pickNext() {
        /* Pick the next higher-numbered track that hasn't finished yet. */
        int n = tracks.size();
        int k = position;
        for (int i = 0; i < n; ++i) {
            Track t = tracks.get(++k % n);
            if (t.downloaded && !t.isFinished())
                return t;
        }
        Log.i(TAG, "pickNext - none");
        return null;
    }

    @Nullable
    static synchronized Track pickFirst() {
        /* Pick the first track that hasn't finished yet. */
        for (Track t : tracks)
            if (t.downloaded && !t.isFinished())
                return t;
        Log.i(TAG, "pickFirst - none");
        return null;
    }

    static String getTrackState(@NonNull Track t) {
        StringBuilder sb = new StringBuilder();

        sb.append(t.priority);
        sb.append('\t');
        sb.append(t.ident);
        sb.append('\t');
        sb.append(t.curMs);
        sb.append('\t');
        sb.append(t.durMs);
        sb.append('\t');
        sb.append(t.title);
        sb.append('\t');
        sb.append(t.when);
        sb.append('\t');
        sb.append(t.downloaded);
        sb.append('\t');
        sb.append(t.artist);
        sb.append('\t');
        if (t.quiet != null)
            Utilities.integersToString(t.quiet, sb);
        sb.append('\t');
        if (t.emoji != null)
            sb.append(t.emoji);

        return sb.toString();
    }

    static void notifyTrackUpdated(@NonNull Track t) {
        TcpService.broadcast(TcpService.NFY_TRACK_UPDATED, getTrackState(t));
    }

    static synchronized void writeState(@NonNull Context context, @NonNull String why) {
        StringBuilder sb = new StringBuilder();

        Track selected = currentTrack();
        if (selected != null)
            sb.append(selected.ident);
        sb.append('\t');
        sb.append(BuildConfig.BUILD_TIME);
        sb.append('\n');

        for (Track track : tracks) {
            if (track.curMs == 0)
                continue;
            sb.append(track.ident);
            sb.append('\t');
            sb.append(track.curMs);
            sb.append('\n');
        }

        Utilities.writeFile(context, STATE_FILE_NAME, sb);
        Log.i(TAG, "writeState " + why);
    }

    private static boolean readState(@NonNull Context context) {
        if (haveTracks())
            // We have already loaded _state.txt into `tracks`
            return false; // unchanged

        File folder = Utilities.getFolder(context);

        tracks.clear();

        File[] tagFiles = folder.listFiles(f -> f.getName().endsWith(".tag"));
        if (tagFiles == null) {
            Note.w(TAG, "readState: Can't access directory: " + folder);
            return false;
        }
        for (File tagFile: tagFiles) {
            String ident = Utilities.removeSuffix(tagFile.getName(), ".tag");

            Tags tags = Tags.fromFile(context, ident);
            if (tags == null)
                continue;

            Track track = new Track(ident);
            track.title = tags.title;
            track.artist = tags.artist;
            track.priority = tags.priority;
            track.size = tags.size;
            // track.curMs - from _state.txt
            track.durMs = tags.durMs;
            track.when = tags.when;
            // track.downloaded - default
            // this.skipForwardMs - default
            // this.skipBackwardMs - default
            track.emoji = tags.emoji;
            track.quiet = tags.quiet;
            tracks.add(track);
        }

        position = 0;
        try {
            BufferedReader br = null;
            try {
                File file = new File(folder, STATE_FILE_NAME);
                br = new BufferedReader(new FileReader(file));

                // First line is the ident of the selected track
                String selected = null;
                long buildTime = 0;
                String line = br.readLine();
                if (line != null) {
                    String[] f = line.split("\t", -1);
                    int i = 0;
                    if (f.length > i)
                        selected = f[i];
                    if (f.length > ++i)
                        buildTime = Long.parseLong(f[i]);
                    if (f.length > ++i)
                        Log.w(TAG, "Extra fields in first line of state file: " + i + " " + line);
                }

                // Remaining lines are "ident \t curMs" for each track where curMs!=0
                while ((line = br.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length != 2) {
                        Note.w(TAG, "Wrong number of columns " + f.length + " in |" + line + '|');
                        continue;
                    }
                    String ident = f[0];
                    int curMs = Integer.parseInt(f[1]);

                    Track track = findTrackByIdent(ident);
                    if (track != null)
                        track.curMs = curMs;
                }
                if (selected != null)
                    selectTrackByIdent(selected);

                // Warn if the state file was saved by a newer version of the app.
                // That means somehow we reverted to an older build.
                // This seems to happen after an update of Android on the phone.
                Log.i(TAG, "State file saved by: " + Utilities.timestampStr(buildTime));
                Log.i(TAG, "Current build: " + Utilities.timestampStr(BuildConfig.BUILD_TIME));
                if (BuildConfig.BUILD_TIME < buildTime)
                    waitForOkay(context, "State file was saved by a newer build."
                            + "\nSaved by: " + Utilities.timestampStr(buildTime)
                            + "\nCurrent build: " + Utilities.timestampStr(BuildConfig.BUILD_TIME));
            }
            finally {
                if (br != null)
                    br.close();
            }
        }
        catch (FileNotFoundException ex) {
            Note.e(TAG, "readState: File not found, permissions?", ex);
        }
        catch (IOException ex) {
            Note.e(TAG, "readState: I/O", ex);
        }
        return true; // changed
    }

    private static void waitForOkay(@NonNull Context context, @NonNull String msg) {
        Log.w(TAG, "Alert: " + msg);
        AlertDialog.Builder alert = new AlertDialog.Builder(context);
        alert.setTitle("Alert!");
        alert.setMessage(msg);
        alert.setCancelable(true);
        alert.setPositiveButton("OK", null);
        AlertDialog alertDialog = alert.create();
        alertDialog.show();
    }

    // Check the folder for new .tag and .mp3 files.
    // Returns true if the list of files changes.
    private static boolean findFiles(@NonNull Context context, @NonNull File folder) {
        // Get the names of all .tag files currently in the folder
        File[] files = folder.listFiles(f -> f.getName().endsWith(".tag"));
        if (files == null) {
            Note.w(TAG, "findFiles: Can't access directory: " + folder);
            return false;
        }

        Map<String, Track> oldTracks = new HashMap<>();
        for (Track t : tracks)
            oldTracks.put(t.ident, t);

        boolean changed = false;
        Track cur = currentTrack();

        for (File file : files) {
            String fileName = file.getName();
            String ident = Utilities.removeSuffix(fileName, ".tag");
            if (oldTracks.remove(ident) == null) {
                // wasn't in oldTracks, it's a new track
                Track track = new Track(ident);
                fillMetaData(context, track);
                insertTrack(track);
                notifyTrackUpdated(track);
                changed = true;
            }
        }

        // For each track still in `oldTracks`, the file no longer exists in
        // the file system, so remove it from `tracks`
        for (Iterator<Track> it = tracks.listIterator(); it.hasNext();) {
            Track t = it.next();
            if (cur == null) {
                cur = t;
                Log.i(TAG, "findFiles: New current track: " + cur);
            }
            if (oldTracks.remove(t.ident) != null) {
                Log.i(TAG, "findFiles: Track is gone: " + t);
                it.remove();
                changed = true;
                if (cur == t) {
                    Log.i(TAG, "findFiles: Was current track: " + cur);
                    cur = null;
                }
            }
        }

        // Update the track.downloaded fields
        for (Track track: tracks) {
            File audioFile = track.getAudioFile(context);
            if (track.downloaded == audioFile.exists())
                continue;

            /* There's a potential race here.  Near the top of this function
               we see foo.tag.  Then before we get to this `for` loop, another
               thread (e.g., TcpService) calls Track.deleteTracks to delete
               foo.tag & foo.mp3.  Then back in this thread again we get to
               this `for` loop and the two files are gone.  We want to avoid
               sending a spurious NFY_TRACK_UPDATED message which would
               confuse QtEdPod into thinking the `foo` track got re-created.
               We could use `synchronized` to avoid this problem but it's easy
               enough to just check if foo.tag still exists */
            File tagFile = track.getTagFile(context);
            if (!tagFile.exists()) {
                Log.i(TAG, "Race averted " + track.ident);
                continue;
            }

            track.downloaded = !track.downloaded;
            if (!track.downloaded)
                Log.i(TAG, "Not yet downloaded: " + track.ident);
            notifyTrackUpdated(track);
        }

        if (changed && cur != null)
            selectTrackByIdent(cur.ident);

        return changed;
    }

    private static void fillMetaData(@NonNull Context context, @NonNull Track track) {
        Tags tags = Tags.fromFile(context, track.ident);
        if (tags == null)
            return;
        track.title = tags.title;
        track.emoji = tags.emoji;
        track.artist = tags.artist;
        track.priority = tags.priority;
        track.size = tags.size;
        track.durMs = tags.durMs;
        track.when = tags.when;
        track.quiet = tags.quiet;
    }

    @NonNull
    static synchronized List<Track> copyTracks(@NonNull List<Track> copy)
    {
        copy.clear();
        copy.addAll(tracks);
        return copy;
    }

    private static class TrackComparator implements Comparator<Track> {
        // Compare two tracks for sort order.
        // See also Track.order() in Track.py
        public int compare(@NonNull Track a, @NonNull Track b) {
            String ap = a.priority;
            String bp = b.priority;
            int d = ap.compareTo(bp);
            if (d != 0)
                return d;
            return a.ident.compareTo(b.ident);
        }
    }
    private static final TrackComparator trackComparator = new TrackComparator();

    private static void sortTracks() {
        Track wasCurrent = currentTrack();
        tracks.sort(trackComparator);
        if (wasCurrent != null)
            selectTrackByIdent(wasCurrent.ident);
    }

    // Insert `track` into `tracks` in the right place
    private static void insertTrack(@NonNull Track newTrack) {
        // TODO: use Collections.binarySearch
        // https://stackoverflow.com/questions/16764007/insert-into-an-already-sorted-list/16764413
        int i = 0;
        for (Track t: tracks) {
            if (trackComparator.compare(newTrack, t) <= 0) {
                tracks.add(i, newTrack);
                return;
            }
            ++i;
        }
        // add at end
        tracks.add(newTrack);
    }

    private static boolean isFolderModified(@NonNull File folder) {
        /* The granularity of the last-modified time depends on the
           file system.  Although the units are milliseconds, the granularity
           seems to be seconds on my phone's file system.  So, for two
           calls to restore() in rapid succession with changes in between,
           the changes won't be noticed. */
        long lm = folder.lastModified();
        if (lm == folderLastMod)
            return false;
        Log.i(TAG, "Folder changed from " + folderLastMod + " to " + lm);
        folderLastMod = lm;
        return true;
    }

    static synchronized void restore(@NonNull Context context, boolean force) {
        // If we haven't read _state.txt yet, read it now to populate `tracks`
        boolean changed = readState(context);

        File folder = Utilities.getFolder(context);
        if (isFolderModified(folder) || force) {
            // Folder has been modified since the last time we scanned it
            // Scan for new tracks
            if (findFiles(context, folder))
                changed = true;
        }

        if (changed)
            sortTracks();
        if (force || changed)
            context.sendBroadcast(new Intent(ACTION_TRACKS_LIST_CHANGED));
    }

    static synchronized Optional<Boolean> setPriority(@NonNull Context context, @NonNull String ident,
                                                      @NonNull String priority) {
        Track t = findTrackByIdent(ident);
        if (t == null)
            return Optional.empty();
        if (t.priority.equals(priority))
            return Optional.of(false);       // unchanged
        Log.i(TAG, "setPriority: Changed " + t.priority + " to " + priority + " for " + ident);
        t.priority = priority;
        sortTracks();
        notifyTrackUpdated(t);
        Tags.setPriority(context, t.ident, t.priority);
        return Optional.of(true);
    }

    static synchronized Optional<Boolean> setEmoji(@NonNull Context context, @NonNull String ident,
                                                   @NonNull String emoji) {
        Track t = findTrackByIdent(ident);
        if (t == null)
            return Optional.empty();

        if (emoji.isEmpty()) {
            if (t.emoji == null)
                return Optional.of(false); // unchanged
            t.emoji = null;
            Log.i(TAG, "setEmoji: Cleared for " + ident);
            notifyTrackUpdated(t);
            Tags.setEmoji(context, t.ident, t.emoji);
            return Optional.of(true);
        }

        if (t.emoji != null && t.emoji.equals(emoji))
            return Optional.of(false); // unchanged
        t.emoji = emoji;
        Log.i(TAG, "setEmoji: Set " + emoji + " for " + ident);
        notifyTrackUpdated(t);
        Tags.setEmoji(context, t.ident, t.emoji);
        return Optional.of(true);
    }

    static synchronized Optional<Boolean> setTitle(@NonNull Context context, @NonNull String ident,
                                                   @NonNull String title) {
        Track t = findTrackByIdent(ident);
        if (t == null)
            return Optional.empty();
        if (title.isEmpty())
            title = ident;
        if (t.title.equals(title))
            return Optional.of(false); // unchanged
        t.title = title;
        Log.i(TAG, "setTitle: Set " + title + " for " + ident);
        notifyTrackUpdated(t);
        Tags.setTitle(context, t.ident, t.title);
        return Optional.of(true);
    }

    static synchronized Optional<Boolean> setArtist(@NonNull Context context, @NonNull String ident,
                                                    @NonNull String artist) {
        Track t = findTrackByIdent(ident);
        if (t == null)
            return Optional.empty();

        if (t.artist.equals(artist))
            return Optional.of(false); // unchanged
        t.artist = artist;
        Log.i(TAG, "setArtist: Set " + artist + " for " + ident);
        notifyTrackUpdated(t);
        Tags.setArtist(context, t.ident, t.artist);
        return Optional.of(true);
    }

    static synchronized void rewind(@NonNull Context context, @NonNull Track track) {
        if (track.curMs == 0)
            return;
        Log.i(TAG, "rewind: From " + track.curMs + " for " + track.ident);
        track.curMs = 0;
        notifyTrackUpdated(track);
        writeState(context, "rewind");
    }

    static synchronized int remainingSeconds() {
        int remMs = 0;
        for (Track t : tracks)
            remMs += t.remMs();
        return (remMs + 500) / 1000;
    }

    static synchronized int numTracks() {
        return tracks.size();
    }

    static int numRewindable() {
        return (int) tracks.stream().filter(track -> track.curMs != 0).count();
    }

    static synchronized int rewindAll() {
        int numRewound = 0;
        for (int i = 0; i <= Tracks.position; ++i) {
            Track t = tracks.get(i);
            if (t.curMs != 0) {
                t.curMs = 0;
                ++numRewound;
            }
        }
        return numRewound;
    }

    // Tracks with this emoji won't be automatically deleted
    private static final String KEEP_EMOJI = "🌟";

    static synchronized int deleteFinished(@NonNull Context context) {
        int numDeleted = 0;
        for (Track t : tracks) {
            if (t.emoji != null && t.emoji.contains(KEEP_EMOJI))
                continue;
            if (t.durMs == 0)
                continue;
            if (!t.isFinished())
                continue;
            t.deleteFiles(context);
            ++numDeleted;
        }
        return numDeleted;
    }

    @SuppressLint("DefaultLocale")
    static synchronized void moveToTop(@NonNull Context context, @NonNull Track track)
    {
        Track wasCurrent = currentTrack();
        if (wasCurrent == null)
            return;

        int place = 0;
        if (track.priorityClassChar() == '=') {
            // `track` is already in the top group (where priority class is '=').
            // Move `track` to the very top
            int i = 0;
            for (Track t : tracks) {
                if (t == track)
                    continue;
                if (t.priority.isEmpty())
                    continue;
                if ('=' != t.priority.charAt(0))
                    continue;
                ++i;
                t.priority = String.format("=%04d", i);
                Tracks.notifyTrackUpdated(t);
                Tags.setPriority(context, t.ident, t.priority);
            }
        }
        else {
            // `track` is not already in the top group (where the priority class is '=').
            // Move `track` to the bottom of the top group
            int i = 0;
            for (Track t : tracks) {
                ++i;
                if (t.priorityClassChar() == '=')
                    place = i;
            }
        }
        track.priority = String.format("=%04d", place);
        Tracks.notifyTrackUpdated(track);
        Tags.setPriority(context, track.ident, track.priority);

        tracks.sort(trackComparator);
        selectTrackByIdent(wasCurrent.ident);
        writeState(context, "moveToTop");
    }

    static synchronized CharSequence[] priorityClasses() {
        ArrayList<CharSequence> pcs = new ArrayList<>();
        for (Track track : tracks) {
            String pc = track.priorityClass();
            if (Utilities.find(pcs, pc) == -1)
                pcs.add(pc);
        }
        return pcs.toArray(new CharSequence[0]);
    }

    static synchronized int findPriorityClass(@NonNull String pc, boolean unfinished) {
        int pos = 0;
        for (Track track : tracks) {
            if (unfinished && track.isFinished())
                continue;
            if (pc.equals(track.priorityClass()))
                return pos;
            ++pos;
        }
        return -1;
    }
}
