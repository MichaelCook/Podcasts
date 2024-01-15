package com.waxrat.podcasts;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class TcpService extends Service {

    private final static String TAG = "Podcasts.TcpService";

    public static final String ACTION_TCP_BIND = "com.waxrat.podcasts.action.TCP_BIND";

    ServerSocket mServerSocket;
    static final List<ClientThread> mClients = new ArrayList<>();

    static final String NFY_PLAYING = "NFY\tPLAYING";
    static final String NFY_STOPPED = "NFY\tSTOPPED";
    static final String NFY_PAUSED = "NFY\tPAUSED";
    static final String NFY_POLLING_FOR_TRACKS = "NFY\tPOLLING-FOR-TRACKS";
    static final String NFY_DOWNLOADING_TRACK = "NFY\tDOWNLOADING-TRACK";
    static final String NFY_TRACK_DELETED = "NFY\tTRACK-DELETED";
    static final String NFY_TRACK_SELECTED = "NFY\tTRACK-SELECTED";
    static final String NFY_TRACK_UPDATED = "NFY\tTRACK-UPDATED";     // updated a track's metadata
    static final String NFY_TRACK_FINISHED = "NFY\tTRACK-FINISHED";
    static final String NFY_CLIENT_CONNECT = "NFY\tCLIENT-CONNECT";
    static final String NFY_VOLUME_UPDATED = "NFY\tVOLUME-UPDATED";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException exc) {
                Note.e(TAG, "Trouble closing socket", exc);
            }
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action == null)
            return START_NOT_STICKY;

        Log.i(TAG, "onStartCommand " + action);
        switch (action) {
            case ACTION_TCP_BIND:
                if (mServerSocket != null) {
                    Log.i(TAG, "Already bound");
                    break;
                }
                Log.i(TAG, "Binding...");
                Thread serverThread = new Thread(new ServerThread(this));
                serverThread.start();
                break;
            default:
                Note.w(TAG, "Unexpected action " + action);
                break;
        }

        return START_NOT_STICKY; // Don't restart this service if killed
    }

    @NonNull
    private static String makeLine(@NonNull String... args) {
        StringBuilder sb = new StringBuilder();
        boolean sep = false;
        for (String arg : args) {
            if (sep)
                sb.append('\t');
            sb.append(arg);
            sep = true;
        }
        sb.append('\n');
        return sb.toString();
    }

    private class ServerThread extends Thread {
        @NonNull final Context mContext;

        ServerThread(@NonNull Context context) {
            mContext = context;
        }

        @Override
        public void run() {
            try {
                // See also: adb shell netstat -plant | grep -w 4004
                mServerSocket = new ServerSocket(4004);

                while (true) {
                    Log.d(TAG, "ServerThread: accept...");
                    Socket clientSocket = mServerSocket.accept();
                    Log.i(TAG, "ServerThread: connection from " + clientSocket.getInetAddress());

                    ClientThread clientThread = new ClientThread(mContext, clientSocket);
                    clientThread.start();
                }
            } catch (SocketException exc) {
                // This is somewhat normal.
                // In particular, onDestroy causes a "Socket closed" exception
                Log.w(TAG, "Trouble in ServerThread: " + exc.getMessage());
            } catch (IOException exc) {
                Note.e(TAG, "Trouble in ServerThread", exc);
            }
        }
    }

    private static void broadcastLine(@NonNull String line) {
        try {
            synchronized (mClients) {
                for (ClientThread client : mClients) {
                    client.sendLine(line);
                }
            }
        } catch (Exception exc) {
            Note.e(TAG, "Trouble in broadcast", exc);
        }
    }

    static void broadcast(@NonNull String... args) {
        if (mClients.isEmpty())
            return;
        String line = makeLine(args);
        if (Utilities.isMainThread()) {
            // Launch a thread to avoid NetworkOnMainThreadException
            Thread thread = new Thread(() -> broadcastLine(line));
            thread.start();
        } else {
            broadcastLine(line);
        }
    }

    static int numClients() {
        synchronized (mClients) {
            return mClients.size();
        }
    }

    private static class ClientThread extends Thread {

        @NonNull final Context mContext;
        @NonNull PrintStream mToClient;
        @NonNull BufferedReader mFromClient;
        @Nullable final String mAddress;

        ClientThread(@NonNull Context context, @NonNull Socket socket) throws IOException {
            mContext = context;
            mAddress = socket.getInetAddress().getHostAddress();
            socket.setTcpNoDelay(true);
            mToClient = new PrintStream(socket.getOutputStream());
            mFromClient = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        private void sendLine(@NonNull String line) {
            synchronized (mClients) { // prevent broadcast() while we're sending this line
                mToClient.print(line);
                mToClient.flush();
            }
        }

        @Override
        public void run() {
            Log.d(TAG, "ClientThread...");
            // numClients()+1 here because we're about to add this client
            broadcast(NFY_CLIENT_CONNECT, "CONNECT", String.valueOf(numClients() + 1), mAddress);

            boolean authenticated = false;
            try {
                authenticated = authenticateClient();
            } catch (SocketException exc) {
                // This is somewhat normal
                Log.w(TAG, "Trouble in ClientThread during auth: " + exc.getMessage());
            } catch (IOException exc) {
                Note.e(TAG, "Trouble in ClientThread during auth", exc);
            }

            if (authenticated) {
                synchronized (mClients) {
                    mClients.add(this);
                }
                try {
                    handleClient();
                    mToClient.close();
                    mFromClient.close();
                } catch (SocketException exc) {
                    // This is somewhat normal
                    Log.w(TAG, "Trouble in ClientThread: " + exc.getMessage());
                } catch (IOException exc) {
                    Note.e(TAG, "Trouble in ClientThread", exc);
                }
                synchronized (mClients) {
                    mClients.remove(this);
                }
            }

            broadcast(NFY_CLIENT_CONNECT, "DISCONNECT", String.valueOf(numClients()), mAddress,
                      authenticated ? "true" : "false");
            Log.d(TAG, "ClientThread...done");
        }

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private void sendUpdated(@NonNull Optional<Boolean> ok,
                                 @NonNull String cmd,
                                 @NonNull String ident,
                                 @NonNull String value) {
            if (!ok.isPresent())
                sendLine(makeLine("ERR", cmd, "No such track", ident));
            else if (ok.get())
                sendLine(makeLine("OK", cmd, "updated", ident, value));
            else
                sendLine(makeLine("OK", cmd, "unchanged", ident, value));
        }

        private void setListItems() {
            Intent i = new Intent(MusicService.ACTION_PLAY_STATE);
            i.putExtra("setListItems", true);
            mContext.sendBroadcast(i);
        }

        private void askTo(@NonNull String action) {
            Intent in = new Intent(action);
            in.setPackage(Utilities.PACKAGE);
            mContext.startService(in);
        }

        private boolean authenticateClient() throws IOException {
            final byte[] nonce = new byte[16];
            new Random().nextBytes(nonce);

            final String hello = "HELLO";
            sendLine(makeLine(hello, Utilities.toHex(nonce)));

            String line = mFromClient.readLine();
            if (line == null) {
                Log.w(TAG, "Client disconnected before HELLO");
                return false;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 2 || !f[0].equals(hello)) {
                Note.w(TAG, "Wrong greeting '" + line + "'");
                return false;
            }
            String actualResponse = f[1];
            String expectedResponse = Utilities.getDigest(Utilities.concatBytes(nonce,
                Utilities.password(mContext).getBytes()));
            if (!actualResponse.equals(expectedResponse)) {
                Note.w(TAG, "Wrong response '" + actualResponse + "' != '" + expectedResponse + "'");
                return false;
            }
            Log.d(TAG, "Client authenticated");
            return true;
        }

        private void handleClient() throws IOException {
            String line;
            while ((line = mFromClient.readLine()) != null) {
                Log.i(TAG, "Client said: " + line);
                if (line.isEmpty()) {
                    // quietly ignore blank lines
                    continue;
                }
                String[] f = line.split("\t", -1);
                String cmd = f[0];
                if (cmd.equals("PLAY") && f.length == 1) {
                    askTo(MusicService.ACTION_PLAY);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("PAUSE") && f.length == 1) {
                    askTo(MusicService.ACTION_PAUSE);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SKIP-FORWARD") && f.length == 1) {
                    askTo(MusicService.ACTION_SKIP_FORWARD);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SKIP-BACK") && f.length == 1) {
                    askTo(MusicService.ACTION_SKIP_BACK);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SKIP-TO-TRACK-START") && f.length == 1) {
                    askTo(MusicService.ACTION_SKIP_TO_TRACK_START);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SKIP-TO-TRACK-END") && f.length == 1) {
                    askTo(MusicService.ACTION_SKIP_TO_TRACK_END);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                // Get track state
                if (cmd.equals("GET-TRACKS") && f.length == 1) {
                    Tracks.forEach((t) -> sendLine(makeLine("RSP", cmd, Tracks.getTrackState(t))));
                    sendLine(makeLine("OK", cmd, String.valueOf(numClients())));
                    askTo(MusicService.ACTION_GET_STATUS);   // might as well include this info, too
                    adjustVolume(AudioManager.ADJUST_SAME);  // might as well include this info, too

                    // If we're not currently playing, then the following info is important
                    // for the QtEdPod display to be correct
                    Track t = Tracks.currentTrack();
                    if (t != null)
                        sendLine(makeLine(TcpService.NFY_TRACK_SELECTED, t.ident));
                    continue;
                }
                if (cmd.equals("GET-TRACK") && f.length == 2) {
                    String ident = f[1];
                    Track t = Tracks.findTrackByIdent(ident);
                    if (t == null)
                        sendLine(makeLine("ERR", cmd, "No such track", ident));
                    else
                        sendLine(makeLine("OK", cmd, Tracks.getTrackState(t)));
                    continue;
                }
                if (cmd.equals("GET-STATUS") && f.length == 1) {
                    askTo(MusicService.ACTION_GET_STATUS);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                // DOWNLOAD-TRACKS force max-tracks then-start
                if (cmd.equals("DOWNLOAD-TRACKS") && f.length <= 4) {
                    boolean force = false;
                    int maxTracks = -1;
                    boolean thenStart = false;

                    int i = 0;
                    if (++i < f.length)
                        force = Boolean.parseBoolean(f[i]);
                    if (++i < f.length)
                        maxTracks = Integer.parseInt(f[i]);
                    if (++i < f.length)
                        thenStart = Boolean.parseBoolean(f[i]);

                    Log.i(TAG, "Download " + maxTracks + " " + thenStart);
                    Downloader.downloadNow(mContext, "tcp", force, maxTracks, thenStart, null);
                    sendLine(makeLine("OK", cmd, String.valueOf(maxTracks), String.valueOf(thenStart)));
                    continue;
                }
                if (cmd.equals("SET-TRACK-PRIORITY") && f.length == 3) {
                    String priority = f[1];
                    String ident = f[2];
                    Optional<Boolean> ok = Tracks.setPriority(mContext, ident, priority);
                    sendUpdated(ok, cmd, ident, priority);
                    setListItems();
                    continue;
                }
                if (cmd.equals("SET-TRACK-EMOJI") && f.length == 3) {
                    String emoji = f[1];
                    String ident = f[2];
                    Optional<Boolean> ok = Tracks.setEmoji(mContext, ident, emoji);
                    sendUpdated(ok, cmd, ident, emoji);
                    continue;
                }
                if (cmd.equals("SET-TRACK-TITLE") && f.length == 3) {
                    String title = f[1];
                    String ident = f[2];
                    Optional<Boolean> ok = Tracks.setTitle(mContext, ident, title);
                    sendUpdated(ok, cmd, ident, title);
                    continue;
                }
                if (cmd.equals("SET-TRACK-ARTIST") && f.length == 3) {
                    String artist = f[1];
                    String ident = f[2];
                    Optional<Boolean> ok = Tracks.setArtist(mContext, ident, artist);
                    sendUpdated(ok, cmd, ident, artist);
                    continue;
                }
                if (cmd.equals("DELETE-FINISHED-TRACKS") && f.length == 1) {
                    int numDeleted = Tracks.deleteFinished(mContext);
                    if (numDeleted != 0) {
                        Track cur = Tracks.currentTrack();
                        Tracks.restore(mContext.getApplicationContext(), true);
                        setListItems();
                        if (cur != null) {
                            Tracks.selectTrackByIdent(cur.ident);
                            TcpService.broadcast(TcpService.NFY_TRACK_SELECTED, cur.ident);
                        }

                        // scroll the list of tracks to show the current track
                        Intent i = new Intent(MusicService.ACTION_PLAY_STATE);
                        i.putExtra("show", true);
                        mContext.sendBroadcast(i);
                    }
                    sendLine(makeLine("OK", cmd, Integer.toString(numDeleted)));
                    continue;
                }
                if (cmd.equals("DELETE-TRACK") && f.length == 2) {
                    String ident = f[1];
                    Track t = Tracks.findTrackByIdent(ident);
                    if (t == null) {
                        sendLine(makeLine("ERR", cmd, "No such track", ident));
                        continue;
                    }
                    if (t == Tracks.currentTrack() && MusicService.playing()) {
                        sendLine(makeLine("ERR", cmd, "Track is playing", ident));
                        continue;
                    }
                    if (t.downloaded) {
                        t.deleteFiles(mContext);
                        Tracks.restore(mContext, true);
                    }
                    else
                        Downloader.deleteTrack(mContext, "tcp", ident);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SELECT-TRACK") && f.length == 2) {
                    String ident = f[1];
                    Track t = Tracks.findTrackByIdent(ident);
                    if (t == null) {
                        sendLine(makeLine("ERR", cmd, "No such track", ident));
                        continue;
                    }
                    if (!MusicService.playing()) {
                        Tracks.selectTrackByIdent(t.ident);
                        setListItems();
                        TcpService.broadcast(TcpService.NFY_TRACK_SELECTED, t.ident);
                        sendLine(makeLine("OK", cmd));
                        continue;
                    }
                    int curMs = 0;
                    /* If this track is "done", start from the beginning.
                       Otherwise, start from where we left off last but rewound a little. */
                    if (!t.isFinished()) {
                        curMs = t.curMs - MusicService.OVERLAP_MS;
                        if (curMs < 0)
                            curMs = 0;
                    }
                    Intent in = new Intent(MusicService.ACTION_PLAY);
                    in.setPackage(Utilities.PACKAGE);
                    in.putExtra("ident", t.ident);
                    in.putExtra("ms", curMs);
                    mContext.startService(in);
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SEEK") && f.length == 3) {
                    String ident = f[1];
                    int whereMs = Integer.parseInt(f[2]);

                    Track t = Tracks.findTrackByIdent(ident);
                    if (t == null) {
                        sendLine(makeLine("ERR", cmd, "No such track", ident));
                        continue;
                    }
                    if (t == Tracks.currentTrack() && MusicService.playing()) {
                        Intent in = new Intent(MusicService.ACTION_SEEK);
                        in.setPackage(Utilities.PACKAGE);
                        in.putExtra("where", whereMs);
                        mContext.startService(in);
                    }
                    else {
                        Tracks.seek(mContext, t, whereMs);
                    }
                    sendLine(makeLine("OK", cmd));
                    continue;
                }
                if (cmd.equals("SET-VOLUME") && f.length == 2) {
                    String action = f[1];
                    switch (action) {
                        case "UP":
                            adjustVolume(AudioManager.ADJUST_RAISE);
                            break;
                        case "DOWN":
                            adjustVolume(AudioManager.ADJUST_LOWER);
                            break;
                        case "SAME":
                            adjustVolume(AudioManager.ADJUST_SAME);
                            break;
                        default:
                            sendLine(makeLine("ERR", cmd, "Bad argument", action));
                            continue;
                    }
                    continue;
                }
                if (cmd.equals("ECHO")) {
                    broadcast(f);
                    continue;
                }
                if (cmd.equals("BYE") && f.length == 1) {
                    sendLine("BYE\n");
                    break;
                }
                Note.w(TAG, "Invalid command: " + cmd);
                sendLine(makeLine("ERR", cmd, "invalid", String.valueOf(f.length), line));
            }
        }

        private void adjustVolume(int direction) {
            AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            int stream = AudioManager.STREAM_MUSIC;
            if (direction != AudioManager.ADJUST_SAME)
                audioManager.adjustStreamVolume(stream, direction,
                        AudioManager.FLAG_SHOW_UI + AudioManager.FLAG_PLAY_SOUND);
            sendLine(makeLine("OK", "SET-VOLUME",
                    String.valueOf(audioManager.getStreamVolume(stream)),
                    String.valueOf(audioManager.getStreamMinVolume(stream)),
                    String.valueOf(audioManager.getStreamMaxVolume(stream))));
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
}
