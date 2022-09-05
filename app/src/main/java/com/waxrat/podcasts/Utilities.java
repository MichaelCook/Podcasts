package com.waxrat.podcasts;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Date;

public class Utilities {
    private final static String TAG = "Podcasts.Utilities";

    static final String PACKAGE = "com.waxrat.podcasts";

    static final String DIGITS = "0123456789abcdef";

    private static String PASSWORD;

    @NonNull
    static synchronized String password(@NonNull Context context) {
        if (PASSWORD == null) {
            PASSWORD = "";
            File file = new File(context.getExternalCacheDir(), "_config.txt");
            try {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line = br.readLine();
                    if (line == null)
                        Note.w(TAG, "File is empty");
                    else {
                        Log.i(TAG, "Got |" + line + "|");
                        PASSWORD = line;
                    }
                }
            } catch (FileNotFoundException e) {
                Note.w(TAG, "No file " + file);
            } catch (IOException e) {
                Note.w(TAG, "I/O reading " + file);
            }
        }
        return PASSWORD;
    }

    @NonNull
    static String toHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int i = 0xFF & b;
            sb.append(DIGITS.charAt(i >> 4));
            sb.append(DIGITS.charAt(i & 0xf));
        }
        return sb.toString();
    }

    @NonNull
    static byte[] concatBytes(@NonNull byte[] a, @NonNull byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    @NonNull
    static String getDigest(@NonNull byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(bytes);
            return toHex(md.digest());
        }
        catch (NoSuchAlgorithmException exc) {
            return "*oops*";
        }
    }

    @NonNull
    static String urlEncode(@NonNull String text) {
        try {
            return URLEncoder.encode(text, "ISO-8859-1");
        }
        catch (UnsupportedEncodingException exc) {
            return "*oops*";
        }
    }

    private static final SimpleDateFormat sdf =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    static String timestampStr(long timestamp) {
        return sdf.format(new Date(timestamp));
    }

    @NonNull
    static String mmss(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d.%03d", s / 60, s % 60, ms % 1000);
    }

    @NonNull
    static String hhmmss(int ms) {
        int s = (ms + 500) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / (60 * 60), s / 60 % 60, s % 60);
    }

    static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    @NonNull
    static String getIpAddress() {
        StringBuilder result = new StringBuilder();
        try {
            Enumeration<?> netInterfaces = NetworkInterface.getNetworkInterfaces();
            while (netInterfaces.hasMoreElements()) {
                NetworkInterface ni = (NetworkInterface) netInterfaces.nextElement();
                Enumeration<?> nas = ni.getInetAddresses();
                while (nas.hasMoreElements()) {
                    InetAddress ia = (InetAddress) nas.nextElement();
                    if (ia.isLoopbackAddress()) {
                        Log.i(TAG, "Loopback interface: " + ni.getName());
                        continue;
                    }
                    String ha = ia.getHostAddress();
                    if (ha == null) {
                        Log.i(TAG, "No IP address on interface " + ni.getName());
                        continue;
                    }
                    if (ha.contains(":")) {
                        Log.i(TAG, "Interface " + ni.getName() + " has IPv6 address " + ha);
                        continue;
                    }
                    if (result.length() != 0)
                        result.append(' ');
                    result.append(ha);

                    Log.i(TAG, "Interface " + ni.getName() + " has IPv4 address " + ha);
                }
            }
        }
        catch (SocketException exc) {
            Log.e(TAG, "Trouble getting IP address", exc);
        }
        return result.toString();
    }

    public static String removeSuffix(@NonNull final String s, @NonNull final String suffix)
    {
        if (s.endsWith(suffix)) {
            return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    @Nullable
    static Integer[] integersFromString(@NonNull String value) {
        if (value.isEmpty())
            return null;
        String[] fields = value.split(" +");
        Integer[] result = new Integer[fields.length];
        try {
            for (int i = 0; i < fields.length; ++i)
                result[i] = Integer.valueOf(fields[i]);
        }
        catch (NumberFormatException exc) {
            Note.e(TAG, "Invalid integers '" + value + '\'');
            return null;
        }
        return result;
    }

    static void integersToString(@NonNull Integer[] ints, @NonNull StringBuilder sb) {
        boolean sep = false;
        for (Integer i : ints) {
            if (sep)
                sb.append(' ');
            sb.append(i);
            sep = true;
        }
    }

    static boolean sleep(int ms) {
        try {
            Thread.sleep(ms);
            return true;
        }
        catch (InterruptedException ex) {
            Note.e(TAG, "Sleep", ex);
            return false;
        }
    }

    static void writeFile(@NonNull Context context, @NonNull String fileName, @NonNull StringBuilder sb) {
        File folder = Utilities.getFolder(context);
        File file = new File(folder, fileName);
        writeFile(file, sb);
    }

    static void writeFile(@NonNull File file, @NonNull StringBuilder sb) {
        File temp = new File(file.getPath() + '~');

        try {
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                byte[] b = sb.toString().getBytes();
                fos.write(b);
            }
            if (temp.renameTo(file))
                Log.i(TAG, "Wrote " + file.getName());
            else
                Note.e(TAG, "Could not rename " + temp);
        }
        catch (FileNotFoundException ex) {
            Note.e(TAG, "File not found, probably permissions", ex);
        }
        catch (IOException ex) {
            Note.e(TAG, "Save failed", ex);
        }
    }

    @Nullable
    static ArrayList<String> readFile(@NonNull File file) {
        ArrayList<String> lines = new ArrayList<>();
        try {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                for (;;) {
                    String line = br.readLine();
                    if (line == null)
                        break;
                    lines.add(line);
                }
            } catch (FileNotFoundException ex) {
                Note.w(TAG, "No file: " + file);
                return null;
            }
        }
        catch (IOException ex) {
            Note.e(TAG, "I/O reading " + file, ex);
            return null;
        }
        return lines;
    }

    @NonNull
    static String orElse(@Nullable String v, @NonNull String def) {
        if (v == null || v.isEmpty())
            return def;
        return v;
    }

    @NonNull
    static File getFolder(@NonNull Context context) {
        File folder = context.getExternalCacheDir();
        if (folder == null)
            throw new RuntimeException("No external cache dir");
        return folder;
    }

    static int find(@NonNull ArrayList<CharSequence> haystack, @NonNull String needle) {
        for (int i = 0; i < haystack.size(); ++i)
            if (needle.contentEquals(haystack.get(i)))
                return i;
        return -1;
    }

    private static String toString(@NonNull InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1)
            result.write(buffer, 0, length);
        return result.toString("UTF-8");
    }

    @Nullable
    static String capture(@NonNull String command) {
        String result = null;
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(command);
            InputStream is = proc.getInputStream();
            result = toString(is);
        }
        catch (IOException ex) {
            Note.e(TAG, "capture: " + command, ex);
        }
        finally {
            if (proc != null)
                try {
                    proc.waitFor();
                }
                catch (InterruptedException ex) {
                    Note.e(TAG, "capture: " + command, ex);
                }
        }
        return result;
    }
}
