# ShareDL

Share a link from any app on your phone and ShareDL saves it: an MP4 if it has video, audio (MP3 or M4A) if it doesn't. It shows up as two entries in the Android share sheet, **Download** and **Download audio**, and does the rest in the background with a progress notification.

Under the hood it runs [yt-dlp](https://github.com/yt-dlp/yt-dlp) (supports 1,000+ sites) through [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles Python, FFmpeg and the QuickJS runtime that YouTube now requires.

## Build and install

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and pick this `ShareDL` folder. The first Gradle sync downloads a lot, so give it a few minutes. If Studio offers to upgrade the Android Gradle Plugin or Kotlin, accepting is fine.
3. On your phone, turn on **Developer options → USB debugging**, plug it in, and press **Run**. Android Studio installs the right APK for your phone automatically.
4. Open the app once. It asks for notification permission and unpacks yt-dlp (this takes a few seconds the first time only).

To get an APK file instead (to install without a cable), use **Build → Build App Bundle(s) / APK(s) → Build APK(s)** and install `app-arm64-v8a-debug.apk` from `app/build/outputs/apk/debug/`. Almost every phone from the last several years is arm64.

There's no `gradlew` script in this folder because Android Studio doesn't need one. If you want command-line builds, run `gradle wrapper` once (with Gradle installed) and then `./gradlew assembleDebug`.

## Using it

In YouTube, Instagram, TikTok, X, Reddit, SoundCloud or any app with a Share button: tap **Share**, then **Download** or **Download audio**. On most phones you can long-press a share-sheet entry and pin it so it's always first.

Files land in **Movies/ShareDL** (video) and **Music/ShareDL** (audio), so your gallery and music player pick them up. When a download finishes, tap the notification to open it.

The app has three tabs:

- **Downloads**: paste a link manually, and watch or cancel downloads. Each one shows how much is done out of the total size, the speed and the time left. The same details appear in the notification.
- **Library**: everything you've downloaded, with thumbnails, size, length and date. Tap to play, or use the ⋮ menu to share or delete. Filter by videos or audio, sort by newest or largest, and see how much storage it all uses.
- **Settings**: video quality (720p, 1080p, Best), audio format (MP3 or M4A), and yt-dlp updates.

The Library reads straight from Android's media store, so it always matches what's really on the phone. Android only shows an app the files it created itself, so after uninstalling and reinstalling ShareDL, older downloads stay on the phone but no longer appear in the Library.

## How it works

The flow is: share sheet → `ShareAutoActivity` / `ShareAudioActivity` (invisible) → `LinkExtractor` pulls out and validates the https link → `Downloads.enqueue` queues a WorkManager job → `DownloadWorker` asks yt-dlp whether the link has video, downloads into the app's private cache, and `MediaStoreSaver` copies the result into shared storage.

| File | What it does |
|---|---|
| `share/ShareActivities.kt` | The two share-sheet entries. No UI; they queue the download and close. |
| `download/LinkExtractor.kt` | Finds the link in shared text and allowlists it (https only, real host, length limit). |
| `download/Downloads.kt` | Queues downloads with WorkManager and skips duplicates. |
| `download/DownloadWorker.kt` | Runs one download as a foreground service with progress and cancel. |
| `download/YtDlp.kt` | All yt-dlp options: format choice, MP4 remux, audio extraction, album art, updates. |
| `download/MediaStoreSaver.kt` | Saves into Movies/Music without needing a storage permission. |
| `download/ProgressTracker.kt` | Reads yt-dlp's output and works out overall size, progress, speed and time left. |
| `download/ErrorMessages.kt` | Turns yt-dlp errors into one readable sentence. |
| `library/MediaLibrary.kt` | Lists, watches and deletes the saved files via MediaStore. |
| `ui/` | The three tabs (Jetpack Compose, Material You colors). |

## What it can't do

DRM-protected services (Spotify, Netflix, Apple Music, Prime Video, Disney+, YouTube Music offline) can't be downloaded; yt-dlp refuses DRM content and so does this app. Content that needs you to be signed in (private accounts, members-only videos) also won't work, since this version has no cookie support.

YouTube changes its defenses often. If you see "confirm you're not a bot" or "site blocked the download", turn on **nightly builds** in the app and tap **Update yt-dlp**. Fixes usually land in nightly within days.

Google Play doesn't allow apps that download from YouTube, so this is for sideloading (or F-Droid / GitHub releases).

## FFmpeg and 16 KB page sizes

FFmpeg is what merges YouTube's separate video and audio files and converts audio to MP3. When the app starts a download it runs FFmpeg once to check that it works, and **Settings → FFmpeg** shows the result.

Newer Android versions can use **16 KB memory pages** instead of 4 KB. The Android 17 emulator images do, and so may some new phones. The FFmpeg bundled with youtubedl-android 0.18.1 includes libraries built for 4 KB pages, which Android refuses to load on a 16 KB device. In that case ShareDL automatically switches to files that need no FFmpeg (lower-quality video, audio as M4A) and says so in Settings.

To get full quality:

- **On an emulator:** create one with a standard 4 KB system image, for example API 35 "Google Play" without "16 KB" in its name.
- **On a phone:** check its page size with `adb shell getconf PAGE_SIZE`. `4096` means everything works; `16384` means the fallback is used until youtubedl-android ships a 16 KB-compatible FFmpeg (bump the version in `app/build.gradle.kts` when it does).

**Test FFmpeg** in Settings shows the raw details if you need to report a problem.

## Release builds

Use **Build → Generate Signed App Bundle / APK** and create your own signing key (keep it out of the repo; `.gitignore` already excludes `*.jks` and `*.keystore`). Release builds are shrunk with R8. If R8 stops with "missing classes", copy the rules it suggests from `app/build/outputs/mapping/release/missing_rules.txt` into `app/proguard-rules.pro`.

## If something doesn't compile

The youtubedl-android API occasionally changes between versions. If a call in `YtDlp.kt` or `DownloadWorker.kt` doesn't compile (for example the `processId =` or `callback =` argument names of `execute`), compare with the library's sample app in its GitHub repo, which always matches the current version.

## License

youtubedl-android is GPL-3.0. Building this for yourself is fine. If you distribute the APK to others, you need to release your source under the GPL-3.0 as well. Only download content you have the right to download; that depends on the site's terms and your local copyright law.

## Ideas for next steps

- **Sharing shortcuts** so ShareDL appears in the top row of the share sheet without pinning.
- A **Quick Settings tile** that downloads whatever link is on your clipboard.
- **Cookie support** for signed-in content (store cookies encrypted, never in plain files).
- A proper download **history** with Room instead of WorkManager's job list.
