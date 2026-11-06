# CamRec

A native Android app for browsing, watching and recording public live rooms on **Chaturbate** and **Stripchat**. It's a rewrite of the live-cam part of [Open EasyX](https://github.com/raccommode/OpenEasyX) and needs no server.

- **Discover**: browse live rooms by site and category, search, or open a room by name or link.
- **Watch**: live playback (ExoPlayer, LL-HLS with separate audio), follow, record.
- **Following**: your followed rooms with their live status. Turn on background auto-record to record them whenever they go live.
- **Recordings**: active recordings with a Stop & save button, plus a library with a player that resumes where you left off. Share, save to Gallery, or delete recordings.
- Recording copies the stream without re-encoding. The app captures HLS segments, then joins them into an MP4 with Android's MediaMuxer. Short drop-outs are merged into one video, and long recordings can be split.
- If a site shows a check page, open it once in the in-app browser. The app reuses that session.

## Building

Every push to `main` builds the app with GitHub Actions. The APK is published as the **latest** release (`CamRec.apk`). The signing key is in the repo (`app/camrec.jks`), so each new build installs as an update over the previous one.

Local build: open the project in Android Studio, or run `./gradlew assembleRelease`.
