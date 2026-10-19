# Architecture

McPodface is a single-module Android app: Kotlin, Compose, Hilt, Room, Media3 and WorkManager.
[`PLAN.md`](../PLAN.md) is the detailed spec. This page is the map.

## Package layout

All code lives under `app/src/main/java/com/steve/mcpodface/`.

```
core/            Clock (injected everywhere; never call System.currentTimeMillis), HtmlText, DI qualifiers
data/
  db/            Room entities, DAOs, AppDatabase (+ FTS4 table for episode search)
  prefs/         SettingsRepository over DataStore (GlobalSettings)
  network/       OkHttp/Retrofit; Podcast Index (signed auth), iTunes, Listen Notes; RateLimiter
  catalog/       CatalogRepository: merges the discovery sources, 24h cache
  feed/          RssParser, FeedFetcher (conditional GET), EpisodeMerger, FeedRepository, SubscriptionRepository
  episodes/      EpisodeRepository (play state, starring)
  queue/         QueueRepository (Up Next)
  playlist/      PlaylistRepository (manual and smart playlists), SmartRules
  backup/        OPML parser, importer, exporter
domain/policy/   DownloadPolicy, CleanupPolicy, PlayedRule: pure Kotlin, no android.* imports
download/        EpisodeDownloader, DownloadWorker, DownloadManager facade
refresh/         RefreshCoordinator, PolicyRunner, refresh/cleanup workers, WorkScheduler
playback/        PlaybackService, PlaybackEngine, PlayerController, SleepTimer, BrowseTree (Android Auto)
ui/
  theme/         colours, typography, AppTheme
  components/    shared composables; glass/ holds the Liquid Glass building blocks
  navigation/    Routes, AppShell (tabs, floating chrome, full-player overlay)
  <screen>/      one package per screen: Screen + ViewModel
```

## Data

The on-device Room database is the app's own store. There is no server.

| Table | Holds |
|---|---|
| `podcasts`, `podcast_settings` | Subscriptions and per-podcast overrides (null = inherit the global setting) |
| `episodes`, `episode_fts` | Every episode ever seen, plus an FTS4 index of titles and show notes |
| `downloads` | Download state per episode, including tombstones that stop deleted episodes coming back |
| `queue_items` | Up Next order |
| `playlists`, `playlist_items` | Manual playlists (ordered items) and smart playlists (rules as JSON) |
| `listening_sessions` | Listening history and stats |
| `backfill_state` | Progress of the slow archive backfill |
| `recent_searches`, `catalog_cache` | Search history and cached discovery results |

Rules that keep user data safe:
- Feed data never overwrites the episode state you set yourself: play state, position, played-at,
  starred and auto-download blocked.
- Episodes that drop out of a feed are kept, so the archive only grows.
- `podcasts` and `episodes` are never written with `REPLACE`, because that would cascade-delete
  their child rows. Use upserts and updates.
- Every schema change ships with a real migration.

## Feeds and discovery

- **Discovery.** Podcast Index and iTunes are searched in parallel.
  - Results are merged (iTunes ranking first) and then re-ranked by how well the title matches.
  - Listen Notes is used only if both come back empty. Its free plan hides the RSS URL, so the feed
    is recovered by looking the show up in iTunes.
- **Refresh.**
  - `FeedFetcher` uses conditional GETs (ETag / Last-Modified), so unchanged feeds cost almost
    nothing.
  - `RssParser` is a streaming XmlPullParser with a fast byte-level pre-pass for HTML entities; a
    500-episode feed parses in about a second on the phone.
  - `EpisodeMerger` matches episodes by GUID, then by enclosure URL, then by title and date.
- **Opening a podcast from search** shows the page straight away from the catalogue data, while the
  feed downloads in the background.

## Auto-download and cleanup

The rules live in `domain/policy` as pure functions with table-driven tests (PLAN.md §6).
`PolicyRunner` feeds them from the database.

- **Effective settings**: a per-podcast override if there is one, otherwise the global setting.
- **Download policy**: keep the latest *N* unplayed episodes (oldest-first for serial shows),
  skipping anything you deleted by hand.
- **Cleanup policy**: delete played episodes after a delay, and anything beyond the keep window.
  Starred episodes and the one playing now are never deleted, and queued episodes are never
  trimmed from the keep window.
- **When it runs**: after each refresh, every 6 hours (`CleanupWorker`), and when an episode
  finishes.

Downloads run in `DownloadWorker`, a foreground `dataSync` worker:
- two at a time;
- each writes to a `.part` file that resumes with HTTP Range and is renamed when complete;
- up to five attempts with backoff;
- network constraints (Wi-Fi only or any network) come from the effective settings.

## Playback

```
UI  ──►  PlayerController (Media3PlayerController: a MediaController)
                 │  binder
                 ▼
        PlaybackService (MediaLibraryService + ExoPlayer)
                 │
                 ▼
        PlaybackEngine ── SleepTimer
```

- **`PlayerController`** is the UI's only entry point.
  - It checks the "stream on mobile data" setting.
  - It publishes `NowPlaying` twice a second while playing.
- **`PlaybackService`** gives the lock screen, notification, Bluetooth, Galaxy Buds and Android Auto
  controls through Media3. It plays the local file when there is one, and streams otherwise.
- **`PlaybackEngine`** runs inside the service. It:
  - applies per-podcast speed and skip intro/outro;
  - saves the position every 10 seconds and on pause;
  - marks the episode played at 95%;
  - records listening sessions;
  - continues to the next Up Next item, or to the next episode of the podcast;
  - applies voice boost through `LoudnessEnhancer`.
- **`SleepTimer`** is a singleton shared by the UI and the engine. It fades the volume over the last
  10 seconds, and shaking the phone adds 5 minutes. "End of episode" makes the engine pause instead
  of moving on.

## Background work

`WorkScheduler` (started from `App.onCreate`) schedules:
- a periodic feed refresh (`RefreshWorker` → `RefreshCoordinator`), which applies the policies,
  adds new episodes to Up Next where you asked for that, and posts one grouped notification;
- a 6-hourly `CleanupWorker`;
- `OrphanCleaner`, which removes files the database no longer knows about.

## Liquid Glass

The look comes from Kyant's `backdrop` library, which samples a recorded layer and refracts and
blurs it through a shape, much like iOS 26. The building blocks are in `ui/components/glass/`:
`Modifier.glass`, `glassCard`, `GlassPage`, `GlassButton`, `GlassIconButton`, `GlassChip`,
`ArtworkAmbience` and `rememberArtworkAccent`.

The one rule: **a glass element must never sample a layer it is drawn inside.** Doing so recurses
and either crashes or renders garbage. In practice:

- `LocalChromeBackdrop` records the whole tab content. Only the floating tab bar and mini player
  sample it.
- `GlassPage` records its own background for the controls on that page.
- `LargeTitleScaffold` and `GlassTopBarScaffold` record their scrolling content for the glass top
  bar. Their padding goes into the list's `contentPadding`, so content scrolls underneath.
- Dialogs and bottom sheets are separate windows. They set `LocalGlassBackdrop` to `null` and get
  a translucent fallback.
- The lens effect only works on rounded-rectangle and capsule shapes.
- Large surfaces (show notes, settings groups) use `glassCard()`. It looks like glass but doesn't
  sample anything, which keeps scrolling smooth.

**Performance.** Judge smoothness only on the `fast` build. Stock Material controls are preferred
wherever a custom one costs frames; the timeline scrubber is a plain `Slider`. The full player
reads the playback position through a lambda, so only the timeline recomposes on each tick. The
screen underneath stops drawing while the player covers it.

## Tests

The tests are mostly JVM tests:
- the policy rules are table-driven;
- the parser runs against real feed fixtures;
- the network code runs against MockWebServer;
- DAOs and repositories run against an in-memory Room database through Robolectric;
- ViewModels are tested with fake controllers that extend `NoOpPlayerController`.

UI is checked on a device or emulator against [`TESTING.md`](../TESTING.md).
