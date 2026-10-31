# HFR ScreenRec

Screen recorder for Android 11+ with 720p / 1080p and 30 / 60 / 90 / 120 / 144 fps.
No libraries, just Android's MediaProjection + MediaRecorder.

## Build the APK on GitHub

1. Create a new GitHub repo and push this whole folder to it (keep the `.github` folder).
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push
   (or press **Run workflow**).
3. When it finishes (about 3-5 min), open the run and download **HFR-ScreenRec-debug-apk**.
4. Unzip it, install `app-debug.apk` (allow "install unknown apps" for your browser/file manager).

From Termux:

```
git init -b main
git add .
git commit -m "first commit"
git remote add origin https://github.com/YOURNAME/YOURREPO.git
git push -u origin main
```

## Using it

- Pick resolution, fps, orientation and codec, then tap START RECORDING and allow screen capture.
- Use **Landscape** for games, so the video isn't letterboxed.
- Stop from the notification or from the app. Videos land in `Movies/HFRRec`.

## About 120 / 144 fps

- A recording can't contain more real frames than the screen draws. You need a 120/144 Hz screen set to its
  highest refresh rate, and an app/game that actually runs that fast.
- The phone's video encoder must also support that size and fps. The app shows the encoder limit.
  If recording fails at high fps, try 720p, H.265, or a lower fps.

## Play Store later

Change `applicationId` and `namespace` in `app/build.gradle.kts` (and the package folders), create a signing key,
build a release **AAB**, and declare the `mediaProjection` foreground service in Play Console.
