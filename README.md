<img src="docs/icon.png" width="96" alt="McPodface icon" align="right">

# McPodface

A native Android podcast app with a Liquid Glass (iOS 26 style) interface, smart auto-download and
auto-delete, and an offline episode archive you can search. It is built for personal use and
sideloaded onto a Galaxy S25 Ultra and S23+. The feature set takes cues from Pocket Casts and
Player.fm.

<p>
  <img src="docs/screenshots/latest.jpg" width="200" alt="Latest tab">
  <img src="docs/screenshots/player.jpg" width="200" alt="Full player">
  <img src="docs/screenshots/playlists.jpg" width="200" alt="Playlists tab">
  <img src="docs/screenshots/stats.jpg" width="200" alt="Listening stats">
</p>

> **Status:** the planned feature set (PLAN.md phases 0–9) is built. See
> [docs/ROADMAP.md](docs/ROADMAP.md) for what's left.

## Features

**Working now**
- **Discover**: searches Podcast Index and iTunes together, with Listen Notes as a fallback. Also
  trending and categories, add-by-URL (including password-protected feeds) and OPML import/export.
- **Latest**: new episodes from all your subscriptions, with All / Unplayed / Downloaded filters.
- **Tabs**: Latest · Library · Playlists · Downloads · Up Next. Search and Settings are glass
  buttons at the top right of every tab.
- **Library**: subscriptions in a grid or list, with unplayed badges.
- **Playback** through Media3: works from the lock screen, notification, Bluetooth and Galaxy Buds.
  - Per-podcast speed, skip intro/outro, trim silence, voice boost and resume rewind.
  - Sleep timer that fades out, adds 5 minutes when you shake the phone, or stops at the end of
    the episode.
  - Up Next queue with drag-to-reorder, and autoplay of the next episode.
  - Marks an episode played at 95% and saves your position every 10 seconds.
- **Auto-download and auto-delete**, set globally:
  - "keep the latest N episodes";
  - Wi-Fi only, or Wi-Fi and mobile data;
  - delete played episodes after a delay.
  - Downloads resume where they left off and retry on failure.
- **Background refresh** with grouped new-episode notifications.
- **Swipe actions**: swipe right on any episode to add it to (or remove it from) Up Next; swipe left
  to mark it played or unplayed.
- **Playlists tab**: Starred, History, listening Stats, manual playlists (drag to reorder) and
  **smart playlists** built from rules (podcasts, unplayed, last N days, downloaded, length, words)
  that update themselves.
- **Chapters and transcripts** (Podcasting 2.0): the current chapter under the title, a chapter
  list to jump around, and a transcript that follows along and seeks on tap.
- **Share** an episode, from the player at the current time.
- **Stats**: listening time, a 30-day chart, streaks, time saved by speed, top podcasts.
- **Home-screen widget** (Glance) and **Android Auto** (browse Up Next, Latest, Downloads,
  Podcasts; voice search).
- **Episode search**, offline, across every episode's title and show notes: phrases, `-exclusions`
  and `OR`, filters (date, status, downloaded, starred, duration), relevance ranking and
  highlighted snippets. Also works within one podcast.
- **Archive backfill**: slowly fetches older episodes a feed no longer lists (RSS paging, then
  Podcast Index), within a daily API budget.
- **Backup and restore** to move between phones: one zip with subscriptions, progress, queue,
  playlists, history, settings and (optionally) the episode archive. Feed passwords are left out. Merge or replace on import.
- **Settings** for theme (light / dark / system / pure black), downloads, playback, notifications
  and backup.


## Design

The UI follows Apple's Liquid Glass language. It uses Kyant's
[backdrop](https://github.com/Kyant0/AndroidLiquidGlass) library for real refraction and blur, and
continuous-corner shapes throughout:
- the tab bar and mini player float above content as glass;
- the full player is tinted with colours taken from the episode artwork.

Where a stock Material control runs more smoothly than a custom one (the timeline scrubber, for
example), the stock control wins. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#liquid-glass).

## Tech

Kotlin · Jetpack Compose + Material 3 · Hilt · Room with FTS4 · DataStore · Media3 (ExoPlayer,
MediaLibraryService) · WorkManager · Retrofit / OkHttp · Coil 3 · Kyant backdrop + shapes.

minSdk 33, target/compile SDK 37. One Gradle module. AGP 9 with built-in Kotlin.

## Building

You need Android Studio (for its JDK and SDK) and free API keys from Podcast Index and,
optionally, Listen Notes. Full steps are in [docs/BUILDING.md](docs/BUILDING.md). In short:

```sh
# put your keys in local.properties (git-ignored), then:
./gradlew :app:installDebug     # debug build
./gradlew :app:installFast      # non-debuggable build over the debug app (judge smoothness)
./gradlew :app:testDebugUnitTest
```

## Documentation

| Doc | What's in it |
|---|---|
| [docs/BUILDING.md](docs/BUILDING.md) | Setup, API keys, signing, build types, tests, emulator |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Package layout, data flow, playback, downloads, glass rules |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What's done and what's next |
| [PLAN.md](PLAN.md) | The full product spec and task breakdown (source of truth) |
| [CLAUDE.md](CLAUDE.md) | Conventions for AI coding agents working in this repo |
| [TESTING.md](TESTING.md) | On-device testing checklist |

## How it's built

The app is built with [Claude Code](https://claude.com/claude-code). An Opus model orchestrates
the work and builds the tricky parts: the playback service, policies and downloads. Haiku subagents
build self-contained screens in parallel, each in its own git worktree, following `PLAN.md` and
`CLAUDE.md`.

## License

[Apache 2.0](LICENSE)
