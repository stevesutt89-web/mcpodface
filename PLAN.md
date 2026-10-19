# Podcast App — Build Plan

Native Android podcast player for personal use on a **Samsung Galaxy S25 Ultra** and **Galaxy S23+**.
The feature set is inspired by Player.fm and Pocket Casts, with a clean, low-colour interface of its own.

This file is the **source of truth for every build agent**. Subagents start with no context, so every
task prompt should point them at this file plus `CLAUDE.md` (created in Phase 0).

---

## 1. Goals and non-goals

**Goals**
- Search an open podcast catalogue and subscribe.
- A beautiful Liquid Glass (iOS 26 style) UI with light, dark and system themes.
- Smart auto-download and auto-delete, set globally with per-podcast overrides.
- Wi-Fi-only or Wi-Fi + cellular downloads.
- **Fast offline full-text search** of episode titles and show notes, within one podcast or across
  all subscriptions, with filters.
- **A growing local episode archive** that slowly backfills older episodes for long-running shows.
- Reliable background playback with lock screen, notification, Bluetooth and Galaxy Buds controls.
- Export and import all user data so you can move between the two phones.

**Non-goals (for now)**
- A cloud account or server. The "own database" is the on-device Room database (§4, §7).
- Play Store publishing. The app is sideloaded.
- Tablet, foldable or Wear OS layouts. Both target devices are phones.
- Video podcasts. Audio only at first, but don't block video later.

---

## 2. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin (latest stable) | Standard |
| UI | Jetpack Compose + Material 3, restyled as Liquid Glass with Kyant `backdrop` + `shapes` (§11) | Native, modern, and easy for agents to work with |
| Navigation | Navigation Compose with type-safe `@Serializable` routes | Well known; few surprises for Haiku |
| DI | Hilt (KSP) | Standard and well documented |
| Database | Room (KSP) with Flow queries, plus **FTS4** for search | Reactive UI, migrations, testable. Room supports FTS4 natively |
| Preferences | Preferences DataStore | Global settings and last opened tab |
| Networking | OkHttp + Retrofit + kotlinx.serialization | Catalogue APIs and feed fetching |
| RSS parsing | Our own `XmlPullParser` parser | Full control over iTunes and Podcasting 2.0 tags; easy to test |
| Images | Coil 3 | Compose-native, with a disk cache |
| Playback | AndroidX Media3: ExoPlayer + `MediaLibraryService` | Gives notification, lock screen, Bluetooth, Android Auto and Samsung quick-panel controls for free |
| Background work | WorkManager | Feed refresh, downloads, cleanup and archive backfill, with constraints |
| Widgets (later) | Jetpack Glance | Home-screen mini player |

**SDK levels:** `minSdk 33` (both phones run Android 15/16, so there are no legacy code paths), with
`targetSdk` and `compileSdk` at the latest stable.

**Version rule:** In Phase 0, Opus sets every dependency version once in `gradle/libs.versions.toml`,
choosing the latest stable release *checked at setup time*. Haiku agents **must never add or bump
versions** on their own. They should ask the orchestrator instead, because guessed version numbers
are a common way builds break.

**Structure:** one Gradle `:app` module with feature packages. Multiple modules would add Gradle
complexity that costs more agent tokens than it saves.

```
app/src/main/java/<pkg>/
  core/        // time, result types, dispatchers, html-to-text, formatting
  data/
    db/        // Room entities, DAOs, migrations, AppDatabase
    prefs/     // DataStore SettingsRepository
    network/   // OkHttp setup, PodcastIndexApi, ITunesApi, RateLimiter
    feed/      // RssParser, FeedFetcher (conditional GET), FeedRepository, EpisodeMerger
    search/    // SearchQueryBuilder, SearchRepository, ranking
    backup/    // BackupExporter / BackupImporter / OPML
  domain/
    policy/    // DownloadPolicy + CleanupPolicy — PURE KOTLIN, no Android imports
  download/    // DownloadWorker, DownloadManager facade, StorageRepository
  refresh/     // RefreshWorker, CleanupWorker, scheduling
  backfill/    // BackfillWorker, BackfillScheduler, sources (RssPaging, PodcastIndex)
  playback/    // PlaybackService (MediaLibraryService), PlayerController, SleepTimer, QueueManager
  ui/
    theme/     // colors, typography, shapes, AppTheme
    components/// Artwork, EpisodeRow, MiniPlayer, LargeTitleTopBar, etc.
    latest/ library/ downloads/ queue/ search/ podcast/ episode/ player/ settings/
  MainActivity.kt, App.kt
```

---

## 3. Podcast catalogue (discovery) and API usage

1. **Podcast Index API** (main source). It's open and free.
   - Get a free API key and secret at `https://api.podcastindex.org`.
   - Keep the key and secret in `local.properties`, which is git-ignored, and expose them through
     `BuildConfig`. This is fine for a sideloaded personal app.
   - Auth headers on every request: `User-Agent`, `X-Auth-Key`, `X-Auth-Date` (unix seconds), and
     `Authorization` = lowercase hex SHA-1 of `key + secret + date`.
   - Endpoints: `/search/byterm?q=`, `/podcasts/trending`, `/podcasts/byfeedurl`, `/categories/list`,
     `/episodes/byfeedid` (for backfill, §7.4).
2. **iTunes Search API** (fallback; no key needed):
   `https://itunes.apple.com/search?media=podcast&entity=podcast&term=…`. It returns `feedUrl` and
   `artworkUrl600`. Use it when Podcast Index fails or returns nothing.
3. **Add by RSS URL**, for private or Patreon feeds. Support HTTP basic auth credentials.
4. **OPML import**, to bring your existing Pocket Casts or Player.fm subscriptions across in one go.

Search results show a preview screen first (artwork, description, latest episodes fetched live from
the feed) with a **Subscribe** button.

**Keeping Podcast Index usage low.** Episode data comes **straight from each publisher's RSS feed**,
not from Podcast Index. Feed refreshes and all episode search (§7) use **zero** API calls.
Podcast Index is only called for:
- discovery searches and trending (cache results for 24h in Room),
- one `/podcasts/byfeedurl` lookup when you subscribe (store `podcastIndexId` and `episodeCount`), and
- archive backfill (§7.4), which runs under a strict daily budget.

Every Podcast Index call goes through one `RateLimiter` (§7.4). Check Podcast Index's current terms for
their own limits; the app self-limits either way.

---

## 4. Data model (Room)

```kotlin
@Entity(indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val feedUrl: String,
  val title: String,
  val author: String?,
  val descriptionHtml: String?,
  val artworkUrl: String?,
  val websiteUrl: String?,
  val podcastIndexId: Long?,
  val podcastIndexEpisodeCount: Int?,   // used to show archive progress
  val itunesId: Long?,
  val feedType: FeedType,            // EPISODIC | SERIAL (from <itunes:type>)
  val isSubscribed: Boolean,         // false = previewed/cached only
  val subscribedAt: Long?,
  val lastRefreshAt: Long?,
  val lastNewEpisodeAt: Long?,       // for "recently updated" library sort
  val httpEtag: String?,             // conditional GET
  val httpLastModified: String?,
  val authUser: String?, val authPassword: String?, // private feeds
)

/** Per-podcast overrides. null = inherit global setting. */
@Entity
data class PodcastSettingsEntity(
  @PrimaryKey val podcastId: Long,
  val autoDownload: Boolean?,         // null inherit
  val keepLatestCount: Int?,          // N
  val deletePlayed: Boolean?,
  val maxAgeDays: Int?,               // 0 = never expire
  val allowCellular: Boolean?,
  val sortOrder: SortOrder,           // NEWEST_FIRST | OLDEST_FIRST (default by feedType)
  val playbackSpeed: Float?,
  val skipIntroSec: Int = 0,
  val skipOutroSec: Int = 0,
  val notifyNewEpisodes: Boolean = true,
  val autoAddToQueue: QueueAdd = QueueAdd.OFF, // OFF | TOP | BOTTOM
  val backfillEnabled: Boolean?,      // null inherit
)

@Entity(indices = [Index(value = ["podcastId", "guid"], unique = true), Index("pubDate"),
                   Index("enclosureUrl")])
data class EpisodeEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val podcastId: Long,
  val guid: String,                  // fallback: enclosureUrl, then hash(title+pubDate)
  val title: String,
  val descriptionHtml: String?,
  val descriptionText: String,       // full plain text (HTML stripped) — indexed for search
  val summaryText: String,           // first ~300 chars of descriptionText, for list rows
  val pubDate: Long,
  val durationSec: Int?,
  val enclosureUrl: String,
  val enclosureType: String?,
  val enclosureLength: Long?,
  val artworkUrl: String?,           // falls back to the podcast artwork
  val season: Int?, val episodeNumber: Int?,
  val chaptersUrl: String?, val transcriptUrl: String?,
  val origin: EpisodeOrigin,         // FEED | FEED_PAGED | PODCAST_INDEX  (how we learned of it)
  val inCurrentFeed: Boolean = true, // false once it drops out of the live RSS (row is kept)
  val playState: PlayState,          // UNPLAYED | IN_PROGRESS | PLAYED
  val positionMs: Long = 0,
  val playedAt: Long?,
  val lastPlayedAt: Long?,
  val isStarred: Boolean = false,    // starred = never auto-deleted
  val autoDownloadBlocked: Boolean = false, // "tombstone" — see §6.4
  val firstSeenAt: Long,
)

/** Full-text index over episodes. Room creates the sync triggers automatically. */
@Fts4(contentEntity = EpisodeEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61,
      tokenizerArgs = ["remove_diacritics=1"])
@Entity(tableName = "episode_fts")
data class EpisodeFts(val title: String, val descriptionText: String)

@Entity
data class DownloadEntity(
  @PrimaryKey val episodeId: Long,
  val state: DownloadState,          // QUEUED | DOWNLOADING | COMPLETED | FAILED | PAUSED_NETWORK
  val source: DownloadSource,        // AUTO | MANUAL
  val filePath: String?,
  val bytesDownloaded: Long,
  val totalBytes: Long?,
  val downloadedAt: Long?,
  val workId: String?,
  val error: String?,
  val attempts: Int = 0,
)

@Entity
data class BackfillStateEntity(
  @PrimaryKey val podcastId: Long,
  val status: BackfillStatus,        // PENDING | IN_PROGRESS | COMPLETE | FAILED | PAUSED
  val rssNextPageUrl: String?,       // cursor for RSS paging (§7.4)
  val rssPagingDone: Boolean = false,
  val indexDone: Boolean = false,
  val indexCursor: String?,          // whatever paging parameter the Index endpoint uses
  val requestsMade: Int = 0,
  val episodesAdded: Int = 0,
  val oldestKnownPubDate: Long?,
  val lastAttemptAt: Long?,
  val nextEligibleAt: Long?,         // backoff after errors / 429
  val lastError: String?,
)

@Entity data class QueueItemEntity(@PrimaryKey val episodeId: Long, val position: Int)

@Entity data class ListeningSessionEntity(          // history + stats
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val episodeId: Long, val startedAt: Long, val listenedMs: Long, val speed: Float)

@Entity data class RecentSearchEntity(@PrimaryKey val query: String, val lastUsedAt: Long,
  val podcastId: Long?)

@Entity data class CatalogCacheEntity(@PrimaryKey val key: String, val json: String, val fetchedAt: Long)
```

Global settings live in DataStore (see §10). From day one, export Room schemas (`room.schemaLocation`)
and write a real `Migration` for every schema change. **Never use destructive migration**, because it
would wipe your library and archive on an update.

---

## 5. Feed refresh

- `RefreshWorker` runs periodically. The interval is set in Settings: 1h, 2h, **4h (default)**,
  12h, or manual only. It requires a network connection, and optionally a non-low battery.
- Refresh also runs on pull-to-refresh in Latest, Library and Podcast screens, and right after
  you subscribe.
- For each subscribed podcast, refresh up to 4 feeds at a time:
  1. Send a conditional GET with `If-None-Match` and `If-Modified-Since`. On a 304 response, skip.
  2. On a **301 permanent redirect**, update `feedUrl`. On **410 Gone**, flag the podcast in the UI.
  3. Parse the feed and merge episodes with `EpisodeMerger` (shared with backfill, §7.4). **Never
     overwrite user state** (playState, position, starred, tombstone) when updating an episode.
  4. **Never delete episode rows** that have dropped out of the feed. Set `inCurrentFeed = false`
     instead. This is how the archive grows by itself over time.
  5. Build a list of genuinely new episodes for notifications and auto-queue. These are episodes
     with `origin = FEED` that were first seen in this refresh *and* published after the previous
     refresh. Backfilled episodes never notify.
- After all podcasts refresh, run the **DownloadPolicy** for each podcast (§6), then **one global
  CleanupPolicy pass**.
- Post one grouped "New episodes" notification for podcasts that have `notifyNewEpisodes` on.

**Parser must handle:** `itunes:*` tags (image, duration, type, episode, season, explicit), `content:encoded`,
`podcast:chapters`, `podcast:transcript`, `podcast:guid`, `media:content` as an enclosure fallback, and
`<atom:link rel="next">` for paging (§7.4).
It must cope with sloppy RFC-822 dates (missing day name, odd time-zone names, 2-digit years) and with
durations given as `SS`, `MM:SS` or `HH:MM:SS`. HTML entities and CDATA must decode correctly.
Keep a folder of around 10 real-world feed fixtures in `app/src/test/resources/feeds/`.

**Networking gotchas:** Send a real `User-Agent`, because some hosts block the OkHttp default. Many
enclosures still use `http://`, so allow cleartext through `network_security_config.xml`. Follow
redirects, since tracking prefixes like podtrac or chartable chain several of them.

---

## 6. Auto-download and auto-delete rules (core feature — spec precisely)

This logic lives in `domain/policy` as **pure functions with no Android dependencies**. Write it test
first, with table-driven unit tests. Every other part of the app only calls into it.

### 6.0 What "played" means
An episode becomes **PLAYED** when playback position reaches **more than 95% of its duration**
(`markPlayedPercent`, default 95). It also becomes PLAYED when playback reaches the skip-outro point,
when the episode ends, or when you mark it played by hand. `playedAt` is set at that moment.
Use the duration ExoPlayer reports if the feed's duration is missing or wrong. Marking an episode
unplayed clears `playedAt`.

### 6.1 Effective settings

```kotlin
data class EffectiveSettings(
  val autoDownload: Boolean,    // global default: true
  val keepLatestCount: Int,     // N, global default: 5   (range 1–20)
  val deletePlayed: Boolean,    // global default: true
  val deletePlayedDelayHours: Int, // global only, default 24; options 0/24/72/168
  val maxAgeDays: Int,          // global default: 30;  0 = never
  val allowCellular: Boolean,   // global default: false (Wi-Fi only)
  val sortOrder: SortOrder,
  val countManualTowardLimit: Boolean, // global only, default false
)
fun resolve(global: GlobalSettings, override: PodcastSettingsEntity?): EffectiveSettings
```
Each per-podcast field is used if it isn't null; otherwise the global value applies. The per-podcast
settings screen labels inherited values as "Default (5)", "Default (Wi-Fi only)" and so on.

### 6.2 Which episodes to auto-download

For each subscribed podcast with `autoDownload = true`:

- **NEWEST_FIRST:** The *window* is the N newest episodes by `pubDate`, whether or not they've been
  played. Download every episode in the window that is:
  - not PLAYED,
  - not tombstoned (`autoDownloadBlocked`),
  - not already downloaded or queued, and
  - within `maxAgeDays` of its `pubDate`, unless `maxAgeDays` is 0.

  This means only *recent* episodes ever download automatically. Playing the newest one doesn't pull
  in an old back-catalogue episode.
- **OLDEST_FIRST** (serial shows or catching up): Find the *resume point*, which is the `pubDate` of
  the newest PLAYED episode. If nothing has been played, start from the oldest episode. The window is
  the **next N unplayed, non-tombstoned episodes after the resume point**, in ascending `pubDate`
  order. The age filter is not applied here, because back-catalogue episodes are expected. Using the
  resume point means older episodes that backfill adds behind where you are listening never trigger
  downloads.

On first subscribe, apply the same rule. Never download the whole back catalogue.

### 6.3 Which downloaded episodes to delete (CleanupPolicy)

Never delete an episode that is **starred**, **currently playing**, or **mid-download**. Otherwise,
delete a downloaded episode when any of these rules matches:

| # | Rule | Applies to |
|---|---|---|
| R1 | `deletePlayed` is on, the episode is PLAYED (more than 95%, §6.0), and `now − playedAt ≥ deletePlayedDelayHours`. **Default: 24 hours after it was played** | AUTO and MANUAL |
| R2 | `maxAgeDays > 0` and the episode's age is over `maxAgeDays`. Age is measured from `pubDate` for AUTO + NEWEST_FIRST downloads, and from `downloadedAt` for MANUAL or OLDEST_FIRST downloads, so a back-catalogue episode you choose to download isn't deleted instantly | AUTO and MANUAL |
| R3 | The podcast has more than N downloaded episodes. Delete those **outside the current window** first, furthest from the front of the sort order first, until N remain | AUTO only (MANUAL too if `countManualTowardLimit`) |

Episodes in the Up Next queue are exempt from R3 but **not** from R1 or R2.

### 6.4 Preventing loops (important)

When an episode's download is deleted for **any** reason (by cleanup or by you), set
`autoDownloadBlocked = true`. The auto-download step never picks a tombstoned episode again. This
prevents download, delete, re-download cycles, for example with a monthly show whose newest episode
is older than 30 days. A manual download clears the tombstone.

### 6.5 When the policies run
- After every feed refresh: DownloadPolicy for each refreshed podcast, then one global cleanup pass.
  At the default 4h refresh, a played episode is deleted within roughly 24–28 hours.
- When an episode becomes PLAYED. This only records `playedAt`; the 24h delay is enforced by later
  passes.
- When global or per-podcast settings change. If lowering N or the max age would delete files, show
  a preview first: "This will remove 7 episodes (412 MB)."
- In a `CleanupWorker` every 6 hours, which also removes **orphan files**: files on disk with no DB
  row, and DB rows whose file is missing. This keeps the 24h delay accurate even if refresh is set
  to manual.

### 6.6 Network rules
- Auto downloads use the constraint `NetworkType.UNMETERED` unless `allowCellular` is on, in which
  case they use `CONNECTED`.
- Manual downloads on cellular, when cellular isn't allowed, show a prompt:
  "Download now on mobile data / Wait for Wi-Fi."
- There's a separate global setting **"Stream on mobile data"** (default on, with a warning when off).
- If Wi-Fi drops during a Wi-Fi-only download, the download resumes later using an HTTP `Range`
  request. Don't restart from zero.

### 6.7 Download mechanics
- One `DownloadWorker` per episode, uniquely named `dl-<episodeId>`, with a global concurrency of 2.
- It runs as a long-running worker using `setForeground` with a `dataSync` foreground-service type and
  a progress notification. Declare WorkManager's `SystemForegroundService` with
  `foregroundServiceType="dataSync"` in the manifest. Note that Android 15+ limits dataSync to 6 hours
  per day, which is plenty here.
- Save files to `getExternalFilesDir(Environment.DIRECTORY_PODCASTS)/<podcastId>/<episodeId>.<ext>`.
  This is app-private, needs no storage permission, and is deleted on uninstall.
- Write to `.part` first, then rename when complete. Record `totalBytes` from Content-Length.
- Retry with exponential backoff, up to 5 attempts, then mark as FAILED and show it in the
  Downloads tab.

---

## 7. Episode search and archive backfill

### 7.1 What it does
- **Search within a podcast:** a search icon on the podcast detail screen opens search already scoped
  to that podcast. The scope chip can be removed to widen the search.
- **Search all subscriptions:** the Search tab's **"Your podcasts"** mode (§8.9).
- Both search **episode titles and full show notes** for every episode in the local database,
  including archived and backfilled ones. Search works fully **offline** and makes no API calls.

### 7.2 Query syntax (`SearchQueryBuilder`, pure Kotlin, test-first)
Turn what you type into a safe FTS4 `MATCH` expression:
- `climate change` matches both words, in any order, as prefixes (`climate* change*`).
- `"climate change"` matches the exact phrase.
- `-politics` excludes the word, and `nasa OR esa` matches either word.
- Strip or escape FTS special characters so input can never cause an SQL error. A blank query
  combined with filters lists matching episodes with no text filter.
- Matching ignores case and diacritics (`unicode61` with `remove_diacritics`).

### 7.3 Filters, sort and results
**Filters** are shown as chips above the results, with a filter bottom sheet:

| Filter | Options |
|---|---|
| Scope | All subscriptions, or one or more chosen podcasts |
| Date | Any, Past week, Past month, Past year, or a custom range (Material date range picker) |
| Match in | Title and show notes (default), or Title only |
| Status | Any, Unplayed, In progress, Played |
| Other | Downloaded only, Starred only |
| Duration | Any, under 15 min, 15–45 min, 45–90 min, over 90 min |

**Sort:** Relevance (default when there's text), Newest, Oldest.
- *Relevance* uses FTS4 `matchinfo(episode_fts,'pcx')`, scored in Kotlin. A title hit is weighted 5×
  a show-notes hit, and recency breaks ties. FTS4 has no built-in BM25 function, so we score it ourselves.

**Results** use the standard `EpisodeRow`, with a **highlighted snippet** from
`snippet(episode_fts, '<b>', '</b>', '…', -1, 16)` in place of the summary. The matched words appear
in bold, with no colour, to keep the low-colour look. A results count is shown: "128 episodes".

**Implementation:** `SearchRepository` builds one `SupportSQLiteQuery`, run through a `@RawQuery` DAO
method returning a `Flow`. The query joins `episode_fts` → `EpisodeEntity` → `PodcastEntity` with
`isSubscribed = 1`, and the filters become `WHERE` clauses with bound arguments. Search runs with a
200ms debounce. Recent searches (up to 10) are stored in `RecentSearchEntity`.

**Performance target:** under 150ms for a query across **100,000 episodes** on an S23+. Check this
with a seeded DAO benchmark test.

### 7.4 Archive backfill (getting older episodes slowly)
Many RSS feeds only list the latest 50–300 episodes. Backfill fills the gap gradually and
politely. It's **best effort**, because no source is guaranteed to hold a show's full history.

**Sources, cheapest first:**
1. **Keeping every episode we see.** Refresh never deletes rows (§5), so the archive grows on its own.
2. **RSS paging.** If the feed has `<atom:link rel="next">` (RFC 5005), follow the next pages. These
   requests go to the publisher, so **they don't count against the Podcast Index budget**. They
   are still spaced at least 10 seconds apart per host.
3. **Podcast Index `/episodes/byfeedid`**, using the stored `podcastIndexId` and a high `max`.
   Podcast Index often keeps episodes that publishers have since dropped from their feed. Check the
   endpoint's current paging parameters in the docs at implementation time and store the cursor in
   `BackfillStateEntity.indexCursor`. A podcast counts as complete when the endpoint returns nothing
   new, or when our count reaches `podcastIndexEpisodeCount`.

**Merging (`EpisodeMerger`, shared with refresh):**
- Match episodes by `guid`, then by a normalized `enclosureUrl` (tracking prefixes and query string
  removed), then by title plus publish date to the same day.
- Insert new episodes with `origin = FEED_PAGED` or `PODCAST_INDEX` and `inCurrentFeed = false`.
- Never overwrite fields from the live feed with backfill data.
- Backfilled episodes **never** notify and never auto-queue. They only auto-download if they fall
  inside an OLDEST_FIRST window (§6.2).

**Scheduling and budget (`BackfillWorker` + `RateLimiter`):**
- The worker runs periodically every 6 hours. By default it requires **Wi-Fi**; "only while charging"
  is an optional setting (default off).
- It works through podcasts one at a time in this priority order: recently subscribed, then recently
  played, then the rest. It processes a batch of pages for one podcast before moving to the next.
- **Daily Podcast Index budget:** default **100 requests per day**, settable from 25 to 500. A
  token-bucket limiter persists the count in DataStore and resets at local midnight. Discovery
  searches share the same limiter but always get 20% of the budget kept back for them, so backfill
  can't use up your ability to search the catalogue.
- Requests are spaced at least 5 seconds apart.
- On a **429** response, pause all Podcast Index backfill for 24h. On other errors, back off
  exponentially per podcast using `nextEligibleAt`.
- New subscriptions are queued as PENDING automatically. Backfill can be turned off per podcast
  (`backfillEnabled`) or globally.

**UI:**
- The podcast settings sheet shows "Archive: 412 of ~1,200 episodes · backfilling", with buttons to
  pause or resume and to backfill now.
- Settings → Search & archive shows overall progress, API requests used today against the budget,
  and the database size on disk.

---

## 8. Screens and navigation

### 8.1 Shell
- A bottom navigation bar with 5 tabs: **Latest · Library · Downloads · Up Next · Search**.
- A **mini player** sits above the tab bar whenever something is loaded. Tap it to open the full player.
- **Remembers the last tab.** Write `lastTab` to DataStore whenever the tab changes. On launch, hold
  the SplashScreen API (`setKeepOnScreenCondition`) until the value loads, so the app doesn't flash
  the wrong tab.
- Settings is reached from a gear icon in the top bar of Library and Latest.
- The app draws edge-to-edge (required at the current targetSdk) and supports predictive back.

### 8.2 Latest tab (recent episodes)
- Shows episodes from all subscriptions, newest first, limited to the last 30 days or 300 rows.
- Each row shows rounded artwork (56dp), the podcast name and relative date in small secondary text,
  the **title** (bold, 2 lines max), the **first part of the show notes** (`summaryText`, 3 lines max,
  secondary color), and a footer with a play/progress button, duration or time left, and download
  state. A thin progress bar shows partial plays.
- Filter chips: All · Unplayed · Downloaded.
- Swipe right to add to Up Next. Swipe left to mark played or unplayed. Long press opens a menu with
  Download, Star, Share, and Go to podcast.
- Supports pull-to-refresh.

### 8.3 Library tab (subscriptions)
- Uses a large collapsing title, "Library".
- Top rows link to **Starred** and **History**.
- Below them is a 3-column **grid of podcast artwork**, with rounded corners and an unplayed-count badge.
- Sort options: Recently updated (default), A–Z, Recently added. You can toggle between grid and list.
- Long press a podcast to open its Settings or Unsubscribe. Unsubscribing asks whether to delete its
  downloads too.

### 8.4 Podcast detail
- Header with large artwork, title, author, an expandable description, a Subscribed ✓ / settings
  button, and a **search icon**, which opens search scoped to this podcast (§7).
- A **sort toggle (Newest ↔ Oldest)**, which saves to `PodcastSettingsEntity.sortOrder`. Serial feeds
  default to oldest first.
- The episode list respects the sort order and includes archived episodes (marked "Archived" in
  subtle text). "Mark all played" and "Hide played" filters are available.
- A **Podcast settings** sheet contains: auto-download (Default/On/Off), keep latest N, delete played,
  max age, allow cellular, playback speed, skip intro and outro seconds, notifications, auto-add to
  Up Next, and archive backfill with its status.

### 8.5 Episode detail
- Shows artwork, title, date, duration, and Play, Download, Queue and Star buttons.
- Full show notes are rendered with `AnnotatedString.fromHtml`. Links are tappable, and **timestamps
  like `12:34` are tappable** to seek.
- Shows the chapter list if there is one.

### 8.6 Full player (bottom sheet)
- Large artwork on a neutral background. An optional "Tint player from artwork" setting
  (`androidx.palette`, default off) adds a very subtle tint.
- Scrubber with elapsed and remaining time (remaining time is adjusted for playback speed).
- Skip back 10s and forward 30s, both configurable.
- Speed control from 0.5× to 3× in 0.1 steps, saved globally or per podcast.
- **Sleep timer:** 5/15/30/45/60 minutes or end of episode. Shaking the phone adds 5 more minutes, and
  the volume fades out over the final 10 seconds.
- Toggles for trim silence (`ExoPlayer.skipSilenceEnabled`) and voice boost (`LoudnessEnhancer`).
- Chapters, show notes, Up Next and an output device picker (system media output switcher).

### 8.7 Downloads tab
- **Storage header:** total size of all downloads (summed from the DB and checked against disk), how
  much free space the device has, and the episode count. Tap it to see **per-podcast sizes**, sorted
  largest first.
- Sections, in order: **Downloading** (with progress, pause and cancel), **Queued**, **Failed** (with
  retry, and "waiting for Wi-Fi" shown as a reason), and **Downloaded**.
- The Downloaded section can be sorted by date downloaded, publish date or podcast, and filtered to
  unplayed only.
- Each Downloaded row shows the file size and when it will be auto-deleted, for example
  "Deletes in 18h · played", so the cleanup rules are visible.
- Bulk actions: "Delete all played", "Delete all downloads", "Delete all for <podcast>", and
  multi-select delete.

### 8.8 Up Next tab
- A reorderable queue with drag handles, swipe to remove, and "Clear queue".
- Continuous playback: when an episode ends, play the next item in the queue. If the queue is empty,
  the setting "Autoplay next episode from same podcast" (default off) plays the next one in that
  podcast's sort order.

### 8.9 Search tab
- A segmented control switches between **Your podcasts** and **Discover**. It defaults to Your
  podcasts once you have any subscriptions, and remembers the last mode.
- **Your podcasts:** local full-text search across all subscriptions, with the filters, sort, snippets
  and recent searches from §7.
- **Discover:** searches Podcast Index with a 300ms debounce, falling back to iTunes. Results are
  cached for 24h. When the field is empty, it shows **Trending** and category chips.
- "Add by URL" and "Import OPML" actions are available.

### 8.10 Settings
- **Appearance:** Theme (System / Light / Dark), Pure black dark mode (default on, good for OLED),
  Tint player from artwork (default off), Use dynamic color (default off).
- **Downloads:** Auto-download new episodes, Keep latest N (**default 5**), Download on (Wi-Fi only /
  Wi-Fi + mobile data), Only when charging (optional), Stream on mobile data.
- **Auto-delete:** Delete played episodes (Immediately / **after 24 hours** / 3 days / 1 week),
  Delete episodes older than (1 week / 2 weeks / **1 month** / 3 months / Never), Count manual
  downloads toward limit.
- **Playback:** Skip intervals, Default speed, Autoplay next, Rewind a few seconds when resuming
  after a long pause (3s after more than 5 minutes), Mark as played at (90 / **95** / 98%),
  Headset double/triple-press behaviour.
- **Refresh:** Interval, New-episode notifications.
- **Search & archive:** Archive backfill on or off, Wi-Fi only, Only while charging, Daily Podcast
  Index budget, archive progress, database size, and "Clear recent searches".
- **Storage:** Opens the Downloads tab.
- **Backup and transfer:** Export data, Import data, Export OPML, Import OPML (§9).
- **Battery:** Shows whether the app is battery-restricted, with a button that opens the system
  setting (see §12).
- **About:** Version, open source licenses.

### 8.11 Playlists
Three kinds, stored in the `playlists` and `playlist_items` tables (DB v2), with the logic in
`data/playlist/PlaylistRepository`:

- **Listen Later**
  - Built in, created on first use, always listed first; it can't be renamed or deleted.
  - **Swipe right on any episode row** (Latest, podcast page, search results, Starred, History,
    other playlists) to add an episode. Swiping right again removes it, and the swipe label shows
    which will happen ("Listen later" / "Remove").
  - Newest saves go on top.
  - An episode leaves Listen Later automatically when it finishes playing.
  - Swipe left keeps its current meaning (played/unplayed). "Add to Up Next" moves to the
    long-press menu.
- **Manual playlists**
  - The user names one and adds episodes from the episode page or a row's long-press menu ("Add to
    playlist…" sheet, which can also create a playlist).
  - Drag to reorder, swipe left to remove. Rename and delete.
- **Smart playlists**
  - Saved `SmartRules`, evaluated live by the search engine (§7.3): podcasts (all or chosen),
    status (default "not played"), downloaded only, starred only, published in the last N days,
    duration, optional words, newest or oldest first, and a size limit (default 50).
  - The editor shows a live preview of the matching episodes as you change the rules.

**Library tab:** Under Starred and History sits a **Playlists** section: Listen Later (with its
count), then the user's playlists (manual with counts, smart marked as smart), then "New playlist"
and "New smart playlist".

**Playlist page:**
- Large title and episode count, then **Play all**, which replaces Up Next with the playlist's
  episodes in order and starts the first; it confirms first if Up Next isn't empty.
- **Shuffle**.
- Standard episode rows.
- Overflow menu: rename / edit rules / delete.

**Backup (§9):** playlists are included; smart-rule podcasts are stored as feed URLs.

---

## 9. Export / import (moving between phones)

**Format:** `podcasts-backup-YYYY-MM-DD.zip` containing:
- `backup.json`, versioned:
  ```json
  {
    "schemaVersion": 1, "appVersion": "1.0.0", "exportedAt": "2026-10-19T10:00:00Z",
    "settings": { "...all DataStore keys...": "" },
    "podcasts": [{ "feedUrl": "", "title": "", "artworkUrl": "", "subscribedAt": 0,
                   "settings": { "...PodcastSettingsEntity fields...": "" } }],
    "episodes": [{ "feedUrl": "", "guid": "", "enclosureUrl": "", "title": "", "pubDate": 0,
                   "playState": "PLAYED", "positionMs": 0, "playedAt": 0, "isStarred": false }],
    "queue":    [{ "feedUrl": "", "guid": "" }],
    "history":  [{ "feedUrl": "", "guid": "", "startedAt": 0, "listenedMs": 0 }],
    "backfill": [{ "feedUrl": "", "status": "COMPLETE", "...cursor fields...": "" }]
  }
  ```
  By default, only export episodes whose state isn't the default (played, in progress, starred or
  queued). Include enough metadata to recreate the episode row if it has since dropped out of the feed.
- `archive.jsonl.gz` (optional; the **"Include episode archive"** checkbox is on by default). This
  holds every episode row, including show notes, so the new phone **doesn't need to backfill again**
  and doesn't spend API budget. It's typically tens of MB.
- `subscriptions.opml`, so the backup also works in any other podcast app.

**Export flow:** Use the Storage Access Framework `ACTION_CREATE_DOCUMENT`, so you can save to local
storage, Google Drive, Samsung Cloud or OneDrive, or share it straight to the other phone with Quick Share.

**Import flow:** Pick the file with `ACTION_OPEN_DOCUMENT`, then choose **Merge** (default) or
**Replace**. Import then:
1. Validates `schemaVersion`.
2. Upserts podcasts and settings.
3. Merges the archive, if present, through `EpisodeMerger`.
4. Upserts episode state, matched by `(feedUrl, guid)` with `enclosureUrl` as a fallback. If an
   episode doesn't exist yet, create it from the backup's metadata.
5. Restores the queue, history and backfill state.
6. Starts a full refresh, then runs the policies, which start the downloads.

Import works in batched transactions, with a progress UI. Downloaded audio files are **not**
included in the backup; they download again on the new phone. Write a round-trip unit test: export,
import into an empty DB, and check the result is identical.

**Bonus:** Set up `dataExtractionRules` so Android/Google backup and **Samsung Smart Switch** copy the
Room DB and DataStore automatically, while excluding the downloads folder.

---

## 10. Global settings (DataStore keys)

`theme`, `pureBlack`, `tintPlayer`, `dynamicColor`, `lastTab`, `searchMode`, `autoDownload`,
`keepLatestCount` (5), `allowCellularDownload`, `downloadOnlyWhileCharging`, `streamOnCellular`,
`deletePlayed`, `deletePlayedDelayHours` (24), `maxAgeDays` (30), `countManualTowardLimit`,
`markPlayedPercent` (95), `refreshIntervalHours`, `notifyNewEpisodes`, `skipBackSec`,
`skipForwardSec`, `defaultSpeed`, `autoplayNext`, `resumeRewind`, `trimSilence`, `voiceBoost`,
`librarySort`, `libraryLayout`, `latestFilter`, `backfillEnabled`, `backfillWifiOnly`,
`backfillChargingOnly`, `apiDailyBudget` (100), `apiRequestsToday`, `apiBudgetDay`,
`apiPausedUntil`.

---

## 11. Visual design (Liquid Glass, iOS 26 style)

*Changed 2026-10-19 at the owner's request, replacing the earlier quiet low-colour brief.* The app
leans fully into Apple's **Liquid Glass** language: translucent, refractive glass controls that float
over content, colour drawn from podcast artwork, springy motion. It should feel beautiful and alive.

**How glass works here** (Kyant `backdrop` library, Android 13+; both phones get the full effect):
glass samples a *recorded backdrop layer*, refracts it through a lens at the edges, blurs it and adds
a specular rim. **A glass element must never sample a layer it is drawn inside.** The building blocks
in `ui/components/glass/` handle this; screens should only use them:

| Piece | Use |
|---|---|
| `Modifier.glass(shape, style)` | Any glass surface. Styles: `Regular` (bars, sheets, cards), `Clear` (small controls over artwork), `Prominent` (accent-tinted primary actions). |
| `GlassPage(background = …) { … }` | Wrap a screen so its glass controls refract the page background. Use `ArtworkAmbience(url)` as the background on podcast, episode and player pages. |
| `LargeTitleScaffold` / `GlassTopBarScaffold` | Screen frames. Content scrolls *under* a top bar that turns to glass. Pass their `PaddingValues` as the list's **`contentPadding`** (never `Modifier.padding`), so content flows under the bars and the floating tab bar. |
| `GlassButton`, `GlassIconButton`, `GlassChip` | Buttons, toolbar/icon buttons and filter chips with the springy press lens and haptics. `BackButton` is a glass circle. |
| `GlassTabBar`, `MiniPlayer` | Floating capsules in the shell (already done). |
| `rememberArtworkAccent(url)` | Vivid accent from the artwork for that page's Prominent glass and highlights. |

- **Colour:** a vivid violet accent (`#6B4EFF` light / `#9B8CFF` dark) for selection, progress,
  toggles and Prominent glass, overridden by the artwork's accent on podcast, episode and player
  pages. Text and backgrounds stay neutral (white / true black) so the glass and artwork carry the colour.
- **Shapes:** continuous-corner ("squircle") shapes from `com.kyant.shapes`: `Capsule()` for
  buttons, chips and bars; `RoundedRectangle(28.dp)` for cards and sheets; 12–28dp on artwork.
  Prefer them over Material's `RoundedCornerShape`.
- **Surfaces:** lists stay solid and readable (no glass behind every row). Group settings and
  secondary content in glass cards (`RoundedRectangle(28.dp)`). Floating chrome may cast a soft
  shadow; nothing else needs one.
- **Typography:** system font; bold large titles (28sp collapsing), 16sp body, 13sp secondary.
- **Motion:** springs everywhere (`GlassPressSpring`: damping ~0.55). Controls swell and their lens
  bulges while pressed; the tab-bar lens slides between tabs; the full player's artwork shrinks a
  little when paused. Haptics on play/pause, tab changes and toggles.
- **Tokens:** all colours come from `MaterialTheme.colorScheme`, `AppTheme.colors` or `glassTint()`.
  No hard-coded colours in screens. Every screen gets light and dark `@ThemePreviews` (previews show
  the translucent fallback, because previews can't render the real glass).

---

## 12. Platform and device notes (Samsung)

- **Battery:** One UI aggressively puts background apps to sleep, which kills periodic refresh,
  cleanup and backfill. Add a Settings row that detects `PowerManager.isIgnoringBatteryOptimizations`
  and opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Separately, set the app to
  **Unrestricted** in One UI battery settings and keep it out of "Deep sleeping apps".
- **Permissions:** `POST_NOTIFICATIONS` (asked at first launch), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET`,
  `ACCESS_NETWORK_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and `WAKE_LOCK`.
- **Playback service:** Use a `MediaLibraryService` with a `foregroundServiceType="mediaPlayback"`.
  Call `setHandleAudioBecomingNoisy(true)` so playback pauses when headphones disconnect, and set
  audio attributes to `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH` for proper audio focus. Save the position
  every 10s, on pause, and on service destroy.
- **Android Auto:** It comes almost free with `MediaLibraryService` once you expose a browse tree
  (Up Next, Latest, Downloads, Podcasts). Sideloaded apps only appear in Android Auto after you enable
  *Unknown sources* in Android Auto's developer settings.
- **Signing:** Create one **release keystore** in Phase 0 and back it up outside the repo. Both
  phones need the same signature to install updates without uninstalling, which would lose your data.
  Don't rely on the per-machine debug keystore.
- **Installing:** Pair both phones over Wireless debugging (`adb pair`, then `adb connect`), then run
  `./gradlew installRelease` (or `installDebug` while developing).

---

## 13. Extra features worth having (beyond the brief)

Included in the phases below:
- Up Next queue with drag to reorder, and auto-add new episodes per podcast.
- Sleep timer with shake to extend and fade-out.
- Playback speed (global and per podcast), trim silence, voice boost.
- Skip intro and outro per podcast, which is great for shows with long ad-reads at the start.
- Rewind a few seconds when resuming after a long pause.
- Starred episodes, which are never auto-deleted.
- Listening history and stats: time listened, time saved by speed and trim silence, and top shows.
- Chapters (Podcasting 2.0 JSON first, ID3 CHAP later) and transcripts.
- Tappable timestamps in show notes.
- OPML import and export.
- New-episode notifications, grouped.
- Private and authenticated feeds.
- Share episode (link with timestamp).
- Home-screen widget (Glance).
- Android Auto.

Maybe later: Chromecast, an audio equalizer, a Wear OS remote, and indexing downloaded transcripts in
the search index.

---

## 14. Phased delivery

Each task lists an **owner**, either **O** (Opus orchestrator does it directly) or **H** (delegate to a
Haiku subagent). It also gives dependencies, and a **Done when** criterion that can be checked with a
command. Tasks in the same group with no shared files can run in parallel.

### Phase 0 — Foundation (O, sequential)
| ID | Task | Done when |
|---|---|---|
| 0.1 | `git init`, `.gitignore` (includes `local.properties`, `*.jks`), Android project with Gradle wrapper, version catalog, Hilt, Room, Compose, Media3, WorkManager, Coil, Retrofit, kotlinx.serialization, KSP | `./gradlew assembleDebug` passes |
| 0.2 | Write `CLAUDE.md`: package layout, conventions, "no new dependencies or versions", build and test commands, file ownership rules, link to this plan | File exists |
| 0.3 | Theme tokens (§11, light, dark and pure black), typography, `AppTheme`, Theme setting wired to DataStore | Previews render in both themes |
| 0.4 | App shell: 5 tabs, nav graph with placeholder screens, last-tab persistence and splash hold, edge-to-edge | Relaunching the app opens the last tab, checked on device |
| 0.5 | All Room entities (including `EpisodeFts` and `BackfillStateEntity`), DAOs as interface stubs, `AppDatabase` with schema export; `SettingsRepository` interface | Compiles |
| 0.6 | Release keystore and `signingConfigs` (reading from `local.properties`) | `assembleRelease` passes |

### Phase 1 — Data layer
| ID | Owner | Task | Depends | Done when |
|---|---|---|---|---|
| 1.1 | H | `RssParser` with about 10 fixture feeds and unit tests (dates, durations, guid fallback, CDATA, itunes and podcast namespaces, `atom:link rel=next`) | 0.5 | `testDebugUnitTest` is green |
| 1.2 | H | `html-to-text` helper (full text + summary) with tests | 0.1 | Tests green |
| 1.3 | H | DAO queries: latest feed, podcast episodes by sort order, unplayed counts, download sums per podcast | 0.5 | Robolectric DAO tests green |
| 1.4 | H | `SettingsRepository` DataStore implementation for every key in §10 | 0.5 | Unit test green |
| 1.5 | O | `EpisodeMerger` (matching rules from §7.4, user state kept) + `FeedFetcher` (conditional GET, redirects, auth) + `FeedRepository` (keeps rows that drop out of the feed) | 1.1, 1.3 | MockWebServer tests and merger tests green |
| 1.6 | O | **`domain/policy`: `resolve`, `DownloadPolicy`, `CleanupPolicy`** with table-driven tests covering every rule in §6, including the 95% and 24h case, the tombstone loop case, and an OLDEST_FIRST window with backfilled episodes behind the resume point | 0.5 | 35+ test cases green |

Group A runs in parallel: 1.1, 1.2, 1.3, 1.4. Then 1.5 and 1.6.

### Phase 2 — Discover and subscribe
| ID | Owner | Task | Done when |
|---|---|---|---|
| 2.1 | H | `PodcastIndexApi` (auth signing) + `ITunesApi` + `CatalogRepository` with fallback and a 24h cache. All Index calls go through a `RateLimiter` interface (a stub for now; implemented in 6.4) | MockWebServer tests green; a real search works in a debug screen |
| 2.2 | H | Search tab shell with the segmented control, plus the **Discover** mode UI: debounced search, results, trending, categories | Searching "radiolab" on device shows results |
| 2.3 | H | Podcast preview/detail screen with Subscribe (including the `byfeedurl` lookup) and the sort toggle | Subscribing adds the podcast to Library |
| 2.4 | H | Add by URL dialog, plus OPML import and export (parser + writer with tests) | Importing a Pocket Casts OPML subscribes to every feed |

### Phase 3 — Browsing
| ID | Owner | Task | Done when |
|---|---|---|---|
| 3.1 | H | Shared components: `Artwork`, `EpisodeRow` (which takes an optional highlighted snippet), `LargeTitleScaffold`, `PlayProgressButton` | Previews in light and dark |
| 3.2 | H | Latest tab (§8.2) with filters, swipe actions, and pull-to-refresh | Checked on device |
| 3.3 | H | Library tab (§8.3) with grid/list, sorts, badges, and top rows | Checked on device |
| 3.4 | H | Episode detail with HTML show notes and tappable timestamps | Checked on device |

### Phase 4 — Playback (O owns the service; H does the UI)
| ID | Owner | Task | Done when |
|---|---|---|---|
| 4.1 | O | `PlaybackService` (MediaLibraryService) and `PlayerController` facade. Plays the local file if downloaded, else streams (respecting the cellular setting). Saves position, applies per-podcast speed and skip intro/outro, **marks played at more than 95%**, handles end of episode | Lock screen, notification and Galaxy Buds controls work; position survives killing the app |
| 4.2 | H | Mini player and full player UI (§8.6) | Checked on device |
| 4.3 | H | `QueueManager` and the Up Next tab with drag reorder | Reordering persists |
| 4.4 | H | Sleep timer (shake, fade), trim silence, voice boost, resume rewind | Checked on device |
| 4.5 | H | Listening sessions recording (for history and stats) | Rows written during playback |

### Phase 5 — Downloads, refresh, cleanup (O reviews closely)
| ID | Owner | Task | Done when |
|---|---|---|---|
| 5.1 | O | `DownloadWorker` (foreground, resumable with Range, `.part` then rename, retries) and the `DownloadManager` facade, with network constraints from the effective settings | A 100 MB episode survives switching Wi-Fi off and on |
| 5.2 | O | `RefreshWorker` + 6-hourly `CleanupWorker` + scheduling, wiring in the policies (§6.5), new-episode notifications | Run one with `adb shell cmd jobscheduler run`; downloads appear; an episode played to 96% is deleted 24h later (checked with a fake clock in tests) |
| 5.3 | H | **Downloads tab** (§8.7): storage header, per-podcast sizes, sections, "deletes in…" labels, bulk actions | Sizes match `adb shell du` on the downloads folder |
| 5.4 | H | Cellular prompt for manual downloads; per-episode download button states | Checked on device |

### Phase 6 — Search and archive
| ID | Owner | Task | Depends | Done when |
|---|---|---|---|---|
| 6.1 | H | `SearchQueryBuilder` (§7.2), test-first: prefixes, phrases, exclusions, OR, escaping hostile input | 0.5 | 25+ tests green, and no input can crash SQLite |
| 6.2 | O | `SearchRepository`: FTS join, filter `WHERE` builder, `snippet()`, `matchinfo` relevance scoring, and recent searches | 6.1, 1.5 | DAO tests green; 100k-episode seeded benchmark under 150ms |
| 6.3 | H | "Your podcasts" search UI: filter chips and sheet (date range picker), sort, highlighted results, recent searches, and the search entry point scoped to a podcast | 6.2, 3.1 | Checked on device: searching a term inside one podcast and across all gives correct, filtered results |
| 6.4 | O | `RateLimiter` (persisted token bucket, a share kept back for discovery, 429 pause) + `BackfillWorker` (RSS paging, then Podcast Index episodes, through `EpisodeMerger`, with state, priority order and backoff) | 1.5, 2.1 | MockWebServer tests: budget never exceeded, cursor resumes after a kill, no duplicates, no notifications |
| 6.5 | H | Archive status UI in podcast settings and in Settings → Search & archive (progress, requests today, database size, pause/resume/backfill now) | 6.4 | Checked on device |

### Phase 7 — Settings
| ID | Owner | Task | Done when |
|---|---|---|---|
| 7.1 | H | Global Settings screens (§8.10) as clean grouped lists | Every setting persists across restarts |
| 7.2 | H | Per-podcast settings sheet with "Default (x)" labels for inherited values | Overrides change policy behaviour (checked by a test) |
| 7.3 | H | Destructive-change preview dialog (§6.5) | Lowering N shows the files and MB about to be removed |
| 7.4 | H | Battery optimization row and the first-run notification permission prompt | Checked on device |

### Phase 8 — Backup
| ID | Owner | Task | Done when |
|---|---|---|---|
| 8.1 | H | `BackupExporter` (zip with JSON, optional archive `jsonl.gz`, and OPML) using kotlinx.serialization models, streamed so large archives don't run out of memory | Unit test green with 50k episodes |
| 8.2 | O | `BackupImporter`: merge and replace modes, archive merge, episode matching, stub creation, batched transactions, then refresh | The round-trip test passes; a real S25U → S23+ transfer works |
| 8.3 | H | Export/import UI with SAF pickers and progress; `dataExtractionRules` | Checked on device |

### Phase 9 — Polish and extras
Chapters (9.1), transcripts (9.2), stats screen (9.3), Glance widget (9.4), Android Auto browse tree
(9.5), accessibility pass with TalkBack labels and font scaling up to 200% (9.6), share episode at a
timestamp (9.7), and a performance pass using baseline profiles and a 500-podcast, 200k-episode
stress test (9.8).

**Rough size:** Phases 0–8 are the MVP, about 40 tasks. Phase 9 is optional.

---

## 15. Working with Opus 5.5 (medium) + Haiku 5.5 agents

**Division of labour**
- **Opus** owns everything that is hard to get right or expensive to redo: Gradle setup, schema, the
  policy engine, `EpisodeMerger`, the search repository, the rate limiter and backfill, the playback
  service, the download worker, backup import, and reviewing every Haiku diff that touches `data/`,
  `domain/`, `playback/`, `download/` or `backfill/`.
- **Haiku** gets leaf tasks with clear edges: a single screen, a parser or query builder written
  against existing tests, an API client, DAO queries, settings UI, or a mapper.

**Task prompt template (orchestrator → Haiku)**
```
Read CLAUDE.md and PLAN.md §<section>. Task <ID>: <one-paragraph goal>.
Files you may create/edit: <explicit list>. Do NOT edit other files or add dependencies.
Use these existing interfaces: <names + paths>.
Done when: <command> passes and <observable behaviour>.
Report back: files changed, test output summary, any open questions.
```

**Cost and reliability tips**
- **Interfaces first.** Opus writes the entity, DAO, repository and ViewModel *signatures* before
  fanning out tasks, so Haiku agents implement against fixed contracts instead of inventing them.
- **Tests as spec.** For the parser, policy, query builder, OPML and backup work, Opus or a Haiku
  agent writes the tests first. Haiku then implements until they pass, which is where Haiku works best.
- **File-disjoint parallelism.** Only run agents in parallel when their file lists don't overlap. Use
  `isolation: worktree` if you're unsure.
- **Serialize Gradle.** Several agents running Gradle at the same time on one Windows machine fight
  over the daemon, lock files and RAM. Either run parallel agents with
  `compileDebugKotlin` only and let Opus do the full build and test run, or use worktrees.
- **Commit after every task** so a bad agent result is a one-line revert.
- Keep `CLAUDE.md` short. Put the detail in this plan and point agents at the section they need.
- **Device testing is yours.** Agents can build and `adb install`, but you check feel, playback on
  Buds, and Samsung battery behaviour. Keep a running `TESTING.md` checklist per phase.

**Verification commands (in CLAUDE.md)**
```
./gradlew :app:compileDebugKotlin        # fast check (Haiku)
./gradlew :app:testDebugUnitTest         # unit tests
./gradlew :app:lintDebug                 # lint
./gradlew :app:installDebug              # to connected phone(s)
```

---

## 16. Before starting — things you need

1. Install **Android Studio** (latest stable, which includes the JDK and SDK) on this Windows machine.
   Add `platform-tools` to PATH for `adb`.
2. Get a **Podcast Index API key and secret** (free) and put them in `local.properties`:
   `PODCASTINDEX_KEY=…`, `PODCASTINDEX_SECRET=…`.
3. Turn on **Developer options → Wireless debugging** on both phones.
4. Choose an app name and `applicationId` (for example `dev.yourname.podcasts`).
5. Export your current subscriptions as **OPML** from Pocket Casts or Player.fm, to use as real test data.

## 17. Decisions

**Confirmed:**
- 5 tabs: Latest · Library · Downloads · Up Next · Search.
- Keep the latest 5 episodes per podcast.
- An episode counts as played at more than 95%, and is deleted 24h after being played.
- Liquid Glass (iOS 26 style) design, chosen 2026-10-19 (see §11); replaced the earlier low-colour brief.
- A local full-text episode search, plus slow archive backfill.

**Defaults chosen (change if you disagree):**
- Podcast Index budget of 100 requests per day, with 20% kept back for discovery.
- Archive backfill on, Wi-Fi only, charging not required.
- Backups include the episode archive.
- Manual downloads don't count toward the limit of 5.
- Dynamic (Material You) color is off.
