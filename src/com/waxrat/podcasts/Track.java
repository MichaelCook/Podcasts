package com.waxrat.podcasts;

final class Track {
    String pathName, title;
    int currentMs;
    int durationMs;
    long lastMod;

    Track(String pathName, String title) {
        this.pathName = pathName;
        this.title = title;
        this.currentMs = 0;
        this.durationMs = 0;
        this.lastMod = 0;
    }

    @Override
    public String toString() {
        return pathName + '@' + currentMs + '/' + durationMs;
    }
}
