# Lyrics Auto

Time-synced lyrics for whatever is playing on your phone (Spotify first, but any app
with a media session works). Lyrics come from [LRCLIB](https://lrclib.net), which is
free and needs no API key.

- **Phase 1 (this code):** phone app in Kotlin + Jetpack Compose.
- **Phase 2 (planned):** the same lyrics view as a custom Android Auto screen.

The app is built only by GitHub Actions; you don't need Android Studio.

> This is a rewrite of the XML-based *Auto Lyrics* app that lives on this repo's `main`
> branch. It uses a different package name (`com.lyricsauto`), so both apps can be
> installed side by side.

## Features

- Detects the playing song and its live position from the player's media session.
- Looks the song up on LRCLIB: exact match first, then a search ranked by song length.
  Prefers synced (LRC) lyrics and falls back to plain text.
- Caches every lookup on the device, so songs you've played work offline (useful in a car).
- Full-screen lyrics on a blurred album-art background. The current line is bright and
  stays centred; past and upcoming lines are dimmed.
- Tap any line to jump the song to it.
- Sync fix: **−** / **+** move the lyrics 0.5 s earlier/later. The correction is saved
  per song. Tap the value between the buttons to reset it.
- Keeps the screen on while the lyrics are visible.

## Install the APK from GitHub Actions

1. Open the repo on GitHub → **Actions** → **Build Lyrics Auto APK**.
2. Click the latest green run, scroll to **Artifacts**, and download
   `lyrics-auto-<number>`. (You must be signed in to GitHub to download artifacts;
   they're kept for 90 days.)
3. The download is a `.zip`. Unzip it on your phone (or on a PC and copy the file
   over) to get `lyrics-auto-<number>.apk`.
4. Open the APK. If Android asks, allow your browser or file manager to
   *install unknown apps*.

New builds install over old ones as long as every build is signed with the same key
(see [Signing](#signing-one-time-setup)). Each run uses the workflow run number as
`versionCode`, so newer builds always count as updates.

## Permissions

Lyrics Auto asks for one special permission: **notification access**. Android only lets
apps that are enabled notification listeners read other apps' media sessions (song
title, artist, position). The app doesn't read or store your notifications.

On first launch the app shows a **Grant notification access** button. Turn on
*Lyrics Auto* in the screen it opens, then press back.

**The switch is greyed out / "Restricted setting" (Android 13+):** Android blocks this
permission for apps installed from an APK until you allow it.

1. Tap **Open App info** in Lyrics Auto (or Settings → Apps → Lyrics Auto).
2. Tap **⋮** in the top-right corner → **Allow restricted settings**, and confirm.
3. Go back to Lyrics Auto and tap **Grant notification access** again.

It also uses the `INTERNET` permission to contact lrclib.net.

## Signing (one-time setup)

Android only installs an update if it's signed with the same key as the installed app.
A key that GitHub generates fresh on each run would fail with *"App not installed"* on
every update, so the workflow signs with your own key from the repository secrets.

1. Create a keystore once (any machine with a JDK):

   ```bash
   keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias lyricsauto -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Base64-encode it.

   macOS/Linux:

   ```bash
   base64 -w0 release.jks > KEYSTORE_BASE64.txt
   ```

   Windows PowerShell:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content KEYSTORE_BASE64.txt
   ```

3. On GitHub, go to **Settings → Secrets and variables → Actions → New repository
   secret** and add:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | contents of `KEYSTORE_BASE64.txt` |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `lyricsauto` (or the alias you chose) |
   | `KEY_PASSWORD` | optional; PKCS12 uses the keystore password |

4. Re-run the workflow. The warning *"Signed with a throwaway key"* should be gone.

Back up `release.jks` and its password somewhere safe and never commit them (`.gitignore`
blocks `*.jks`, `*.keystore` and `*.p12`). If you lose the key, you'll have to uninstall
the app before installing a build signed with a new one.

If the secrets aren't set, the build still succeeds but uses the runner's debug key
and shows a warning. Fine for a first try; not for updates.

## Building

Every push and every manual **Run workflow** (`workflow_dispatch`) runs:

```bash
./gradlew testReleaseUnitTest assembleRelease
```

on `ubuntu-latest` with JDK 17. The Gradle wrapper (including `gradle-wrapper.jar`) is
committed, so no Gradle install is needed. To build locally you'd need JDK 17 and the
Android SDK (`ANDROID_HOME`); the same command works.

Toolchain: Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Compose BOM
2024.12.01, `minSdk` 26, `targetSdk`/`compileSdk` 35.

## How it works

```
MediaListenerService (NotificationListenerService)
  └─ onActiveSessionsChanged ─▶ NowPlayingRepository ── StateFlow<NowPlaying?>
                                   picks the session: playing Spotify > other playing > paused Spotify
                                   NowPlaying = track + PlaybackSnapshot + album art
                                         │
                    LyricsRepository ◀───┘  (on each new track, debounced)
                      LyricsCache (filesDir/lyrics/*.json) ─▶ LrcLibClient ─▶ LrcParser
                      StateFlow<LyricsState>: Idle | Searching | Synced | Plain | Instrumental | NotFound | Failed
                                         │
                    LyricsScreen (Compose) + OffsetStore (per-song sync offset)
```

- **Live position** = `state.position + (elapsedRealtime() − state.lastPositionUpdateTime) × playbackSpeed`,
  re-evaluated about 30 times a second while playing (`PlaybackSnapshot.positionAt`).
- **LRCLIB lookup** (`LrcLibClient.find`): `GET /api/get` with artist, title, album and
  duration; if that has no synced lyrics, retry with cleaned metadata ("Song - Remastered 2011"
  → "Song", first artist only), then `GET /api/search?q=…` and pick the result whose duration
  is within 8 s, preferring synced lyrics. Requests send `User-Agent: LyricsAuto/<version> (repo URL)`.
- **LRC parsing** (`LrcParser`): `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`,
  several timestamps on one line, the `[offset:±ms]` header, and enhanced-LRC word tags
  (stripped). Covered by unit tests in `app/src/test`.
- **Caching:** hits are kept until the 1,000-song limit evicts them; "not found" results
  expire after 24 h. **Search again** on the "No lyrics found" screen bypasses the cache.
- `AppGraph` (in `LyricsAutoApp`) holds one `NowPlayingRepository` and one
  `LyricsRepository` for the whole process, so the Android Auto screen in Phase 2 can reuse them.

## Phase 2: Android Auto (planned)

Plan from the project brief, not started yet:

1. Study how [Fermata Auto](https://github.com/AndreyPavlenko/Fermata) draws a custom,
   non-template UI in Android Auto for sideloaded apps, and build a minimal version that
   renders the lyrics view: larger text, fewer lines, high contrast.
2. If that route doesn't work, fall back to the official Car App Library: a
   `PaneTemplate` showing the current and next line, refreshed on each line change.
3. Either way, reuse `NowPlayingRepository` and `LyricsRepository` from Phase 1.

Android Auto hides sideloaded apps unless you allow them:

1. Open **Android Auto** settings on the phone (Settings → Connected devices →
   Connection preferences → Android Auto, or the Android Auto app).
2. Scroll to **Version** and tap it about 10 times until developer mode is enabled.
3. Open **⋮ → Developer settings** and turn on **Unknown sources**.
4. Reconnect to the car (or restart the Desktop Head Unit).

The older *Auto Lyrics* app on `main` already shows lyrics in Android Auto through a
`MediaBrowserService` (lyrics as browse items and as the now-playing subtitle). That
remains a working option while Phase 2 is being built.
