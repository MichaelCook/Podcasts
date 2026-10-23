package com.waxrat.podcasts;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private final static String DELETED_FILE_NAME = "_deleted.txt";

    /**
     * Idents the user has deleted whose removal from the server has not yet
     * been confirmed.  Persisted in {@link #DELETED_FILE_NAME} so the intent
     * survives process death: without it, a later poll of the server could
     * resurrect a deleted track's .tag file and the track would re-appear.
     */
    private static final Set<String> deletedIdents = new HashSet<>();
    private static boolean deletedIdentsLoaded = false;

    /** The last modification time of the track folder. */
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
        Log.w(TAG, "selectTrackByIdent: no such track " + ident);
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

    private static void loadDeletedIdents(@NonNull Context context) {
        if (deletedIdentsLoaded)
            return;
        deletedIdentsLoaded = true;
        ArrayList<String> lines =
                Utilities.readFile(new File(Utilities.getFolder(context), DELETED_FILE_NAME));
        if (lines != null)
            deletedIdents.addAll(lines);
    }

    private static void saveDeletedIdents(@NonNull Context context) {
        StringBuilder sb = new StringBuilder();
        for (String ident : deletedIdents)
            sb.append(ident).append('\n');
        Utilities.writeFile(context, DELETED_FILE_NAME, sb);
    }

    /** More than this many unconfirmed deletions is worth a warning in the log. */
    private static final int DELETED_WARN_SIZE = 12;

    /** Record that the user deleted `ident`; its server-side removal may still be pending. */
    static synchronized void noteDeleted(@NonNull Context context, @NonNull String ident) {
        loadDeletedIdents(context);
        if (deletedIdents.add(ident)) {
            Log.i(TAG, "noteDeleted: " + ident);
            saveDeletedIdents(context);
            if (deletedIdents.size() > DELETED_WARN_SIZE)
                Note.w(TAG, "_deleted.txt has " + deletedIdents.size()
                        + " unconfirmed entries; server-side removal may be failing");
        }
    }

    /** True if the user deleted `ident` and its server-side removal is unconfirmed. */
    static synchronized boolean isDeleted(@NonNull Context context, @NonNull String ident) {
        loadDeletedIdents(context);
        return deletedIdents.contains(ident);
    }

    /** Forget the deletion of `ident`; its server-side removal is confirmed. */
    static synchronized void clearDeleted(@NonNull Context context, @NonNull String ident) {
        loadDeletedIdents(context);
        if (deletedIdents.remove(ident)) {
            Log.i(TAG, "clearDeleted: " + ident);
            saveDeletedIdents(context);
        }
    }

    /** A copy of the idents whose server-side removal is unconfirmed. */
    @NonNull
    static synchronized Set<String> copyDeletedIdents(@NonNull Context context) {
        loadDeletedIdents(context);
        return new HashSet<>(deletedIdents);
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
        if (t.memo != null)
            sb.append(t.memo);
        sb.append('\t');
        if (t.feed_url != null)
            sb.append(t.feed_url);
        sb.append('\t');
        if (t.track_url != null)
            sb.append(t.track_url);
        sb.append('\t');
        if (t.pubdate != null)
            sb.append(t.pubdate);

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
            if (track.curMs < 20000)    // if not yet started or only barely started, forget it
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

            Tags tags;
            try {
                tags = Tags.fromFile(context, ident);
            }
            catch (RuntimeException ex) {
                Note.e(TAG, "readState: Bad tag file " + tagFile, ex);
                continue;
            }
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
            track.pubdate = tags.pubdate;
            track.memo = tags.memo;
            track.feed_url = tags.feed_url;
            track.track_url = tags.track_url;
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
               thread (e.g., the Downloader delete worker) calls Track.deleteFiles to delete
               foo.tag & foo.mp3.  Then back in this thread again we get to
               this `for` loop and the two files are gone.  We want to avoid
               sending a spurious NFY_TRACK_UPDATED message which would
               confuse QtEdPod into thinking the `foo` track got re-created.
               We could use `synchronized` to avoid this problem, but it's easy
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
        track.pubdate = tags.pubdate;
        track.memo = tags.memo;
        track.feed_url = tags.feed_url;
        track.track_url = tags.track_url;
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

    @NonNull
    static List<Track> copyTracks()
    {
        return copyTracks(new ArrayList<>());
    }

    private static class TrackComparator implements Comparator<Track> {
        public int compare(@NonNull Track a, @NonNull Track b) {
            return Utilities.comparePriorityThenIdent(a.priority, a.ident, b.priority, b.ident);
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

    static synchronized Optional<Boolean> setMemo(@NonNull Context context, @NonNull String ident,
                                                  @NonNull String memo) {
        Track t = findTrackByIdent(ident);
        if (t == null)
            return Optional.empty();

        if (memo.isEmpty()) {
            if (t.memo == null)
                return Optional.of(false); // unchanged
            t.memo = null;
            Log.i(TAG, "setMemo: Cleared for " + ident);
            notifyTrackUpdated(t);
            Tags.setMemo(context, t.ident, t.memo);
            return Optional.of(true);
        }

        if (t.memo != null && t.memo.equals(memo))
            return Optional.of(false); // unchanged
        t.memo = memo;
        Log.i(TAG, "setMemo: Set " + memo + " for " + ident);
        notifyTrackUpdated(t);
        Tags.setMemo(context, t.ident, t.memo);
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

    static synchronized void seek(@NonNull Context context, @NonNull Track track, int whereMs) {
        if (whereMs < 0)
            whereMs = 0;
        else if (whereMs > track.durMs)
            whereMs = track.durMs;
        if (track.curMs == whereMs)
            return;
        Log.i(TAG, "seek: From " + track.curMs + " to " + whereMs + " for " + track.ident);
        track.curMs = whereMs;
        notifyTrackUpdated(track);
        writeState(context, "seek");
    }

    static synchronized int remMs() {
        int ms = 0;
        for (Track t : tracks)
            ms += t.remMs();
        return ms;
    }

    // How much time remains in the given priority class
    static synchronized int remMs(int priorityClass) {
        int ms = 0;
        for (Track t : tracks)
            if (t.priorityClassChar() == priorityClass)
                ms += t.remMs();
        return ms;
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

    static private boolean isFinishedDeletable(@NonNull Track track) {
        if (track.durMs == 0)
            return false;
        return track.isFinished();
    }

    static synchronized boolean anyFinishedDeletable() {
        return tracks.stream().anyMatch(Tracks::isFinishedDeletable);
    }

    static synchronized int deleteFinished(@NonNull Context context) {
        // Don't delete the currently selected track: a track that just
        // finished stays selected until MusicService finishes transitioning
        // off of it, so deleting it here could race with that transition.
        Track current = currentTrack();
        int numDeleted = 0;
        for (Track t : tracks)
            if (t != current && isFinishedDeletable(t)) {
                t.deleteFiles(context);
                ++numDeleted;
            }
        return numDeleted;
    }

    private static final class PriorityClassRange {
        final int startIndex;  // inclusive
        final int endIndex;    // exclusive
        PriorityClassRange(int startIndex, int endIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }

    private static PriorityClassRange getPriorityRange(int priorityClass) {
        int startIndex = -1, endIndex = -1;
        int i = 0;
        for (Track t : tracks) {
            if (t.priorityClassChar() == priorityClass) {
                if (startIndex == -1)
                    startIndex = i;
                endIndex = i + 1;
            }
            i += 1;
        }
        if (startIndex == -1)
            startIndex = endIndex = 0;
        return new PriorityClassRange(startIndex, endIndex);
    }

    static synchronized void moveToTop(@NonNull Context context, @NonNull Track track) {
        boolean moved;
        do {
            switch (track.priorityClassChar()) {
                case 'A': {
                    PriorityClassRange ar = getPriorityRange('A');
                    int pos = getPositionOfTrack(track);
                    if (pos != ar.startIndex)
                        moveTrackToPosition(context, track, 'A', 0);
                    moved = true;  // even if we didn't move the track, we need to stop because no moves are possible
                }
                break;
                case 'B': {
                    PriorityClassRange ar = getPriorityRange('A');
                    moved = moveTrackToPosition(context, track, 'A', ar.endIndex - ar.startIndex);
                }
                break;
                case 'C': {
                    PriorityClassRange cr = getPriorityRange('C');
                    int pos = getPositionOfTrack(track);
                    if (pos == cr.startIndex) {
                        PriorityClassRange ar = getPriorityRange('A');
                        moved = moveTrackToPosition(context, track, 'A', ar.endIndex - ar.startIndex);
                    } else
                        moved = moveTrackToPosition(context, track, 'C', 0);
                }
                break;
                default: {
                    PriorityClassRange cr = getPriorityRange('C');
                    moved = moveTrackToPosition(context, track, 'C', cr.endIndex - cr.startIndex);
                }
                break;
            }
        } while (!moved);
    }

    @SuppressLint("DefaultLocale")
    private static boolean moveTrackToPosition(@NonNull Context context, @NonNull Track track,
                                               int priorityClass, int priorityNum) {
        /* Return true if `track`'s position changes */
        Track wasCurrent = currentTrack();
        if (wasCurrent == null)
            return false;  // this shouldn't ever happen, it would mean `track` was not in `tracks`

        int pos = getPositionOfTrack(track);
        track.priority = String.format("%c%05d", priorityClass, priorityNum);
        Tracks.notifyTrackUpdated(track);
        Tags.setPriority(context, track.ident, track.priority);

        int pNum = 0;
        for (Track t : tracks) {
            if (t == track)
                continue;
            if (t.priorityClassChar() != priorityClass)
                continue;
            if (pNum == priorityNum)
                pNum++;
            t.priority = String.format("%c%05d", priorityClass, pNum++);
            Tracks.notifyTrackUpdated(t);
            Tags.setPriority(context, t.ident, t.priority);
        }

        tracks.sort(trackComparator);
        selectTrackByIdent(wasCurrent.ident);

        if (pos == getPositionOfTrack(track))
            return false;  // position unchanged

        writeState(context, "moveTrackToPosition");
        return true;
    }
}
