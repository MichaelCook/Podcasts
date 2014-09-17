package com.waxrat.podcasts;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.farng.mp3.MP3File;
import org.farng.mp3.id3.AbstractID3v2;
import org.farng.mp3.id3.ID3v1;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;

class Tracks {
    private final static String TAG = "Track";

    private final static String TMP_NAME = "_state.txt~";
    private final static String FILE_NAME = "_state.txt";

    /** The folder where the MP3 tracks are stored. */
    public final static File FOLDER =
      new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
      File.separator + "Podcasts");

    /** The last modification time of the tracks folder. */
    private static long folderLastMod = -1;

    static List<Track> tracks = new ArrayList<Track>();
    static int position = -1;

    static Track track(int pos) {
        if (pos < 0 || pos >= tracks.size())
            return null;
        return tracks.get(pos);
    }

    static Track currentTrack() {
        return track(position);
    }

    static Track findTrackByName(String name) {
        for (Track t : tracks)
            if (name.equals(t.pathName))
                return t;
        return null;
    }

    static void selectTrackByName(String name) {
        int n = tracks.size();
        for (int i = 0; i < n; ++i)
            if (name.equals(tracks.get(i).pathName)) {
                if (position != i) {
                    if (Log.ok) Log.i(TAG, "trackPosition " + position + " -> " + i);
                    position = i;
                }
                break;
            }
    }

    static synchronized void save(Context context) {
        StringBuffer sb = new StringBuffer();
        sb.append(position);
        sb.append('\n');
        for (Track t : tracks) {
            sb.append(t.pathName);
            sb.append('\t');
            sb.append(t.currentMs);
            sb.append('\t');
            sb.append(t.durationMs);
            sb.append('\t');
            sb.append(t.title);
            sb.append('\t');
            sb.append(t.lastMod);
            sb.append('\n');
            if (t.currentMs != 0)
                if (Log.ok) Log.i(TAG, "Save track " + t);
        }
        if (Log.ok) Log.i(TAG, "write track count " + tracks.size());
        try {
            FileOutputStream fos = null;
            //File dir = context.getFilesDir();
            File dir = FOLDER;
            File tmp = new File(dir, TMP_NAME);
            File name = new File(dir, FILE_NAME);
            try {
                if (!tmp.exists()) {
                    if (Log.ok) Log.i(TAG, "Good, no file " + tmp);
                } else if (tmp.delete())
                    Log.w(TAG, "Yow! Deleted file " + tmp);
                else
                    Log.e(TAG, "Uh oh! Couldn't deleted file " + tmp);
                fos = new FileOutputStream(tmp);
                byte[] b = sb.toString().getBytes();
                fos.write(b);
                if (Log.ok) Log.i(TAG, "Wrote " + b.length + " to " + tmp);
            }
            finally {
                if (fos != null)
                    fos.close();
            }
            if (tmp.renameTo(name)) {
                if (Log.ok) Log.i(TAG, "Renamed " + tmp + " to " + name);
            } else
                Log.w(TAG, "Failed to rename " + tmp + " to " + name);
        }
        catch (IOException ex) {
            Log.e(TAG, "save failed", ex);
        }
    }

    private static void readState(Context context) {
        try {
            BufferedReader br = null;
            try {
                //File dir = context.getFilesDir();
                File dir = FOLDER;
                File file = new File(dir, FILE_NAME);
                if (Log.ok) Log.i(TAG, "file size " + file.length() +
                        ", exists " + file.exists());
                br = new BufferedReader(new FileReader(file));

                int numTracks = tracks.size();

                // First line is the number of the track we were listening to.
                String line = br.readLine();
                int trackNum = -1;
                if (line != null)
                    trackNum = Integer.valueOf(line);
                if (Log.ok) Log.i(TAG, "readState trackNum " + trackNum);

                /* Subsequent lines are the info of each track
                   separated by tabs. */
                int n = -1;
                while ((line = br.readLine()) != null) {
                    ++n;

                    String[] f = line.split("\t");
                    if (f.length != 5) {
                        Log.w(TAG, "Wrong number of columns " + f.length +
                                " in |" + line + '|');
                        continue;
                    }
                    int i = 0;
                    String name = f[i++];
                    int curMs = Integer.valueOf(f[i++]);
                    int durMs = Integer.valueOf(f[i++]);
                    String title = f[i++];
                    long lastMod = Long.valueOf(f[i++]);

                    for (int k = 0;; ++k) {
                        if (k == numTracks) {
                            if (Log.ok) Log.i(TAG, "No track " + name);
                            break;
                        }
                        Track t = tracks.get(k);
                        if (!name.equals(t.pathName))
                            continue;
                        //if (Log.ok) Log.i(TAG, "Track " + t);
                        if (t.durationMs != 0 && t.durationMs != durMs)
                            Log.w(TAG, "readState: Duration changed " +
                               t.durationMs + " => " + durMs + ": " + t);
                        t.currentMs = curMs;
                        t.durationMs = durMs;
                        if (title != null)
                            t.title = title;
                        t.lastMod = lastMod;
                        if (n == trackNum)
                            position = k;
                        break;
                    }
                }
                if (Log.ok) Log.i(TAG, "read track count: " + (n + 1));
            }
            finally {
                if (br != null)
                    br.close();
            }
        }
        catch (FileNotFoundException ex) {
            Log.w(TAG, "readState: " + ex.getMessage());
        }
        catch (IOException ex) {
            Log.e(TAG, "readState failed", ex);
        }
    }

    private static class Caseless implements Comparator<File> {
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    }

    private static final Comparator<File> caseless = new Caseless();

    private static void findFiles() {
        tracks.clear();
        File[] files = FOLDER.listFiles();
        if (files == null) {
            Log.w(TAG, "Not a directory: " + FOLDER);
            return;
        }
        Arrays.sort(files, caseless);
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".mp3"))
                tracks.add(new Track(name, name.substring(0, name.length() - 4)));
        }
        if (Log.ok) Log.i(TAG, "MP3: " + tracks.size());
    }

    private static boolean fillMetaData() {
        boolean changed = false;
        for (Track t : tracks) {
            File file = new File(FOLDER, t.pathName);
            if (t.durationMs == 0) {
                t.lastMod = file.lastModified();
                if (Log.ok) Log.i(TAG, "Looking up metadata because durMs=0 (lastMod " +
                        t.lastMod + ')');
            } else {
                // already have it
                long lm = file.lastModified();
                if (lm == t.lastMod)
                    continue;
                if (Log.ok) Log.i(TAG, "Looking up metadata because lastMod changed from " +
                    t.lastMod + " to " + lm);
                t.lastMod = lm;
            }
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(file.toString());
            }
            catch (IllegalArgumentException ex) {
                Log.w(TAG, "MediaMetadataRetriever.setDataSource " +
                                file.toString() + ": " + ex.getMessage());
                continue;
            }
            changed = true;
            /*
            if (Log.ok) Log.i(TAG, "name=" + t.pathName);
            if (Log.ok) Log.i(TAG, "title=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            if (Log.ok) Log.i(TAG, "artist=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (Log.ok) Log.i(TAG, "album=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            if (Log.ok) Log.i(TAG, "duration=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            */
            String s = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (s == null)
                try {
                    MP3File mp3 = new MP3File(file);
                    AbstractID3v2 v2 = mp3.getID3v2Tag();
                    if (v2 != null) {
                        s = v2.getSongTitle();
                        if (s != null && s.length() == 0)
                            s = null;
                        if (s != null) {
                            if (Log.ok) Log.i(TAG, "ID3v2 title '" + s + "' in " + t.pathName);
                            String a = v2.getAlbumTitle();
                            if (a != null && a.length() != 0)
                                s = a + " ~ " + s;
                        }
                    }
                    if (s == null) {
                        ID3v1 v1 = mp3.getID3v1Tag();
                        if (v1 != null) {
                            s = v1.getSongTitle();
                            if (s != null && s.length() == 0)
                                s = null;
                            if (s != null) {
                                if (Log.ok) Log.i(TAG, "ID3v1 title '" + s + "' in " + t.pathName);
                                String a = v1.getAlbumTitle();
                                if (a != null && a.length() != 0)
                                    s = a + " ~ " + s;
                            }
                        }
                    }
                    if (s == null)
                        if (Log.ok) Log.i(TAG, "No ID3v1 or ID3v2 tag in " + t.pathName);
                }
                catch (Exception ex) {
                    Log.e(TAG, "MP3File exception " + ex.getMessage(), ex);
                }
            if (s != null)
                t.title = s;
            s = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (s != null) {
                t.durationMs = Integer.parseInt(s);
                if (t.currentMs > t.durationMs) {
                    Log.w(TAG, "MetaData: curMs " + t.currentMs + " was " +
                            (t.currentMs - t.durationMs) + " after durMs " +
                            t.durationMs + ": " + t);
                    t.currentMs = t.durationMs;
                }
            }
        }
        return changed;
    }

    static boolean restore(Context context) {
        boolean changed = false;
        /* TODO: (BUG) The granularity of the last-modified time depends on the
           file system.  Although the units are milliseconds, the granularity
           seems to be seconds on my phone's file system.  So, for two
           calls to restore() in rapid succession with changes in between,
           the changes won't be noticed. */
        long lm = FOLDER.lastModified();
        if (lm == folderLastMod) {
            if (Log.ok) Log.i(TAG, "Folder is unchanged at " + lm);
        } else {
            if (Log.ok) Log.i(TAG, "Folder changed from " + folderLastMod + " to " + lm);
            folderLastMod = lm;
            findFiles();
            readState(context);
            changed = true;
        }
        if (fillMetaData()) {
            save(context);
            changed = true;
        }
        if (changed) {
            if (Log.ok) Log.i(TAG, "Send ACTION_MEDIA_MOUNTED");
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, 
                    Uri.parse("file://" + FOLDER)));
        }
        return changed;
    }
}
