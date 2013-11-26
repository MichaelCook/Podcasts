# Podcasts

A simple Android app for playing podcasts (MP3s).

## Server Side

Two to three times per day, cron job running on an Ubuntu Linux server checks
several podcast sites to look for new podcast tracks.  The script downloads
the tracks then does some post-processing:

  * Speed up, usually by 40-50%, reducing a 60-minute track to 40 minutes.
    Lowers pitch so the voices don't sound like chipmonks.

  * Normalize the volume so different tracks are at about the same level.

  * Tweaks the MP3 tags.  Often, tags aren't set to useful information in the
    original MP3 (e.g., a title of "Episode 37").

See scripts/*.

## The App

The app periodically checks the server for new tracks, but only if connected
to my home Wi-Fi network "montana".  The check can be done manually, too, via
the app's menu.  The app pulls any tracks from the server, verifies each was
copied correctly, then deletes the track from the server.

When playing tracks, the app remembers the position of each track.  If you
switch from playing one track to another then go back to the first track, the
app picks up where you left off.

The app has large buttons and a minimal user interface designed to be easy to
use while driving or exercising.  In landscape mode, the app disables the
screen lock so that the display won't turn off while you're driving.  Responds
to the buttons on my car's steering wheel to skip forward 30 seconds or back
10.

There are buttons to skip forward by 30 seconds and back by 10.  These buttons
make it easy to skip past commercial messages.  Hold the forward button for a
few seconds to skip to the end of the current track and mark it "done".

There is a menu button to delete all tracks that are marked "done".

Play/pause/play backs up 2 seconds for a little overlap of where you left off.
