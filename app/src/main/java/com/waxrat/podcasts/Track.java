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
    final int skipForwardMs, skipBackwardMs;
    @Nullable String emoji;
    @Nullable Integer[] quiet;

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
        this.skipForwardMs = 30000;
        this.skipBackwardMs = 10000;
        this.emoji = null;
        this.quiet = null;
    }

    // The audio file name relative to Tracks.FOLDER
    @NonNull
    String audioFileName() {
        return ident + ".mp3";
    }

    // The tag file name relative to Tracks.FOLDER
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

    // The track's priority class as a single-character string, or the empty
    // string if the class has no priority (which would be unusual but
    // strictly speaking possible)
    @NonNull String priorityClass() {
        if (priority.isEmpty())
            return "";
        return priority.substring(0, 1);
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
}
