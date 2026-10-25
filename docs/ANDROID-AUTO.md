# Android Auto: full car experience

Goal: McPodface works fully from the car screen, so you never need the phone while driving.
That means browsing, playing, managing Up Next, and asking by voice.

Read [`CLAUDE.md`](../CLAUDE.md) first. The current tree is `playback/BrowseTree.kt` and the session
is `playback/PlaybackService.kt` (PLAN.md §12, task 9.5).

## Status (2026-10-25): built, waiting for a car test

Waves 0 to 3 are done on branch `android-auto`. 1081 unit tests and `lintDebug` pass. Not yet run
on a phone, the Desktop Head Unit or the car: work through the "Android Auto" checklist in
[`TESTING.md`](../TESTING.md).

What changed against the design below:
- **The overflow buttons (speed, star, mark played & next, sleep) only show while a car is
  connected** (`NowPlayingButtons`). Android Auto reads the same platform session as the phone's
  media controls, so without this the phone notification would gain four buttons.
- **The "No connection" message is done.** `SkipForwardingPlayer.getPlayerError` rewords lost or
  missing connection errors. The car and system media controls show that message.
- **No "No subscriptions" message.** Media3 would need library errors copied into the playback
  state to show it, which is risky for one empty state. An empty tab shows the car's own "No items".
- **Voice requests that match nothing play nothing.** No error message is set for them.
- **A9 skipped:** the app keeps all its text in code (`strings.xml` has only the app name), so car
  text does the same.
- **Car row actions reuse the phone's helpers.** `BrowseActions` imports `playedToggleTarget` and
  `downloadMenuAction` from `ui/`, so the car does exactly what the phone does. Move them to
  `domain/policy` if that layering bothers you.
- **A car download that would need the mobile-data prompt is queued for Wi-Fi**, which is the
  phone prompt's "Wait for Wi-Fi" choice.
- **Wave 4 (Up Next as the player queue, the "car downloads only" setting) is not started.** It
  needs the owner's go-ahead.

## What is possible (read before planning anything else)

Android Auto does **not** run our Compose UI. A media app gets the car's own templated screens,
driven entirely by the Media3 `MediaLibraryService` we already have:

| Car screen | What feeds it |
|---|---|
| Tabs across the top (max **4**) | The browsable children of the library root |
| Lists and grids | `onGetChildren`, plus content-style extras (list or grid, group headers) |
| Row badges: played / part-played / downloaded | `MediaMetadata.extras`: `MediaConstants.EXTRAS_KEY_COMPLETION_STATUS`, `EXTRAS_KEY_COMPLETION_PERCENTAGE`, `EXTRAS_KEY_DOWNLOAD_STATUS` |
| Per-row action menu (Add to Up Next, Download, …) | `MediaLibrarySession.Builder.setCommandButtonsForMediaItems` + `MediaMetadata.Builder.setSupportedCommands` (Auto calls these "custom browse actions") |
| Now playing: play/pause, skip, seek bar | Player commands, plus `setMediaButtonPreferences` for the back and forward slots |
| Now playing: overflow buttons (speed, star, sleep, …) | More `CommandButton`s in `setMediaButtonPreferences` with `SLOT_OVERFLOW` |
| Queue button on now playing | The player's timeline (more than one `MediaItem`) |
| Search box and "Hey Google, play … on McPodface" | `onSearch` / `onGetSearchResult`, and `onSetMediaItems` with `requestMetadata.searchQuery` |
| Messages such as "No subscriptions yet" | `LibraryResult.ofError` / `session.setPlaybackException` with a resolution action |

A fully custom car UI would need the Car App Library (`androidx.car.app`). That is a **new
dependency** (forbidden without asking), its media templates are newer and less proven, and
Google recommends the media-browser route for audio apps. **Decision: stay on Media3.** Revisit
only if something below turns out to be impossible.

## Already fixed

- **Play/pause did nothing in the car (2026-10-25).** In Media3 1.11,
  `AcceptedResultBuilder(session, controller)` gives *untrusted* controllers read-only player
  commands. The Android Auto app counts as untrusted unless it has notification access, so Media3
  dropped every play tap with *"Calling play() omitted due to COMMAND_PLAY_PAUSE not being
  available"*. The skip buttons still worked because they were granted explicitly.
  `onConnectAsync` now grants full commands to `isAutoCompanionController` /
  `isAutomotiveController`. **Rule for every task below: any new custom command must be added to
  the commands granted in `onConnectAsync`, or the car silently ignores it.**

## Bugs found while planning (all fixed in wave 2)

- **Voice "play X on McPodface" plays nothing.** Voice requests arrive as a `MediaItem` with an
  empty `mediaId` and `requestMetadata.searchQuery` set. `onSetMediaItems` maps only numeric ids,
  so the request comes out empty.
- **Library root ignores `params.isRecent`**, so Auto's "resume last played" card on the car's
  home screen gets the whole tree instead of the last episode.
- **The car never refreshes lists.** Nothing calls `notifyChildrenChanged`, so Up Next and
  Downloads are stale until the car is reconnected.
- **Artwork may not show.** Our artwork URIs are `https://`. The car host loads artwork through
  the phone and handles `content://` most reliably (Google's UAMP sample proxies artwork through
  a `ContentProvider` for this reason).

## Target design

**Tabs (root, exactly 4):**
1. **Up Next**: the queue in order, with the current episode first.
2. **Latest**: new episodes from the last 30 days, grouped under "Today", "Yesterday",
   "This week", "Earlier". Played episodes are hidden by default.
3. **Podcasts**: grid of subscriptions (artwork), then each podcast's episodes in its sort
   order, with in-progress episodes first.
4. **Library**: folders for Downloads, In progress, Starred, Playlists (each playlist, smart ones
   included), and History.

**Every episode row:** title, a subtitle of `Podcast · 23 min left` (or `· 1 h 05 min` when
unplayed), artwork, completion badge, downloaded badge. Row actions: **Up Next** (add/remove),
**Download** (or Remove download), **Star**, **Mark played**.

**Now playing:**
- Centre: play/pause.
- Back and forward slots: skip back and skip forward (already done).
- Overflow:
  - **Speed** cycles 1.0 → 1.2 → 1.5 → 1.8 → 2.0 and is saved as the podcast's speed, as the
    phone player does.
  - **Star**.
  - **Mark played & next**.
  - **Sleep timer** cycles Off → 15 min → 30 min → End of episode.

**Voice:**
- "Play McPodface" or an empty query resumes the last episode.
- "Play \<podcast\> on McPodface" plays that podcast's in-progress episode, or else the newest
  unplayed one.
- Anything else plays the best full-text match across episodes.

**Messages:**
- No subscriptions: "Subscribe to podcasts on your phone".
- Stream fails while offline: "No connection. Downloaded episodes still play".

**Optional (wave 4, ask before doing):**
- Up Next as the player's real timeline, so the car's queue button works.
- A Settings → Android Auto section with "Car shows downloaded episodes only".

## How to test (agents and you)

- **Agents, no car needed:**
  - `BrowseTreeTest` and the new Robolectric tests below.
  - `adb shell dumpsys media_session` with the app playing on a phone. Check:
    - the state shows `ACTION_PLAY_PAUSE` (actions include 512);
    - the custom actions are listed;
    - the controllers and their commands appear.
- **Desktop Head Unit (DHU):** a car screen on the PC.
  1. Android Studio → SDK Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator*.
  2. On the phone, open Android Auto settings, tap the version 10 times, then ⋮ → *Start head unit
     server*. Turn on *Unknown sources* too.
  3. Run `adb forward tcp:5277 tcp:5277`, then
     `"$LOCALAPPDATA/Android/Sdk/extras/google/auto/desktop-head-unit.exe"`.
  4. Typing `screenshot <file>.png` into the DHU console saves the car screen, so the orchestrator
     can check layouts itself.
- **The real car** is the final check (you): the TESTING.md "Android Auto" checklist from task A11.

## Tasks

Owners: **O** = Opus orchestrator (does it itself, and reviews every H diff under `playback/`),
**H** = Haiku agent using the prompt template in PLAN.md §15. Tasks in the same wave have
**disjoint file lists** and can run in parallel. Haiku agents run only
`./gradlew :app:compileDebugKotlin`, and the orchestrator runs tests and lint between waves.
Commit after every task.

### Wave 0: contracts (O, sequential, no behaviour change)

| ID | Task | Done when |
|---|---|---|
| A0 ✅ | **Done 2026-10-25.** `LibraryCallback` moved to `playback/LibraryCallback.kt` (Hilt-injected; the service sets its `scope`). Contracts in `playback/auto/`, with stub bodies that keep today's behaviour: `AutoCommands` (every action id, `all` granted in `onConnectAsync`, `skipButtons`, `rowButtons`); `BrowseItems` (`CarEpisode` + `toCarEpisode()` for `EpisodeListItem` and `DownloadListItem`, `folder`, `episodeItem(episode, now, groupTitle)`, `subtitle`, `groupTitle(pubDate, now, zone)`); `artworkUri(url)`; `VoiceSearch.resolve(query, extras)`; `CarActions.run(action, player, engine)` and `overflowButtons(episodeId, player)`; `BrowseActions.run(action, episodeId)`; `BrowseInvalidator.start(scope, notify)`. `res/drawable/ic_car_download.xml` added | `compileDebugKotlin` + 985 unit tests + `lintDebug` green; no behaviour change |

### Wave 1: building blocks (H, parallel)

| ID | Task | Files | Done when |
|---|---|---|---|
| A1 | **Row metadata.** Implement `BrowseItems`: subtitle (`Podcast · 23 min left` / `· 1 h 05 min`), completion status and percentage from `playState`/`positionMs`/`durationSec`, download status, group title extra (`Today` / `Yesterday` / `This week` / `Earlier`, taking `now` from `Clock`), grid style on podcast folders and list style on episode lists, row actions via `setSupportedCommands` (from `AutoCommands`), artwork through `artworkUri()` | `playback/auto/BrowseItems.kt`, `test/…/playback/auto/BrowseItemsTest.kt` | 15+ tests green (each badge state, time-left formatting, group boundaries at midnight) |
| A2 | **Tree v2.** Restructure `BrowseTree` to the 4 tabs above (Library folder: Downloads, In progress, Starred, Playlists → each playlist, History). Latest hides played; podcast lists put in-progress first. Use existing DAOs and `PlaylistRepository` only, and **stop and report** if a query is missing rather than adding one. Use `BrowseItems` for every row | `playback/BrowseTree.kt`, `test/…/playback/BrowseTreeTest.kt` | Tests cover every node id, empty states and the 4-tab root |
| A3 | **Artwork provider.** A read-only `ContentProvider` at `${applicationId}.artwork` that maps `content://…/artwork?u=<encoded https url>` to a ~512 px JPEG cached in `cacheDir/car-artwork/` (fetched with the injected `OkHttpClient` through a Hilt `EntryPoint`, decoded with `BitmapFactory` and `inSampleSize`). `artworkUri()` returns these URIs. Add the `<provider>` to the manifest (not exported writes, `grantUriPermissions` off; Auto reads it through the session) | `playback/auto/ArtworkProvider.kt`, `playback/auto/ArtworkUris.kt`, `AndroidManifest.xml`, `test/…/ArtworkUrisTest.kt` | URI round-trip test green; on the DHU, podcast grid shows artwork |
| A4 | **Voice search.** Implement `VoiceSearch.resolve`: blank → last played (`EpisodeDao.getLastPlayed`) else first Up Next; a podcast title match (case/punctuation-insensitive, `MediaStore.EXTRA_MEDIA_ARTIST` / `EXTRA_MEDIA_TITLE` used when present) → in-progress or newest unplayed in that podcast; otherwise `SearchRepository` top results | `playback/auto/VoiceSearch.kt`, `test/…/VoiceSearchTest.kt` | Tests for each branch with a Robolectric in-memory DB |
| A5 | **Now-playing actions.** Implement `CarActions.run`: speed cycle (saved as the podcast's `playbackSpeed` in `podcast_settings`), star toggle (`isStarred`), mark played & next (same path as the engine's end-of-episode, so expose `PlaybackEngine.completeNow()` as a one-line O-reviewed change), sleep cycle via `SleepTimer`. Give each its `CommandButton` icon (`CommandButton.ICON_PLAYBACK_SPEED`, `ICON_STAR_FILLED/UNFILLED`, `ICON_CHECK_CIRCLE_FILLED`) and a display name that shows the current value ("1.5×", "Sleep 15 min"). Media3 has no timer icon, so sleep uses a new `res/drawable/ic_car_sleep.xml` (Material "bedtime" moon, same format as `ic_car_download.xml`) via `setCustomIconResId`. Also implement `overflowButtons` | `playback/auto/CarActions.kt`, `playback/PlaybackEngine.kt` (only `completeNow()`), `res/drawable/ic_car_sleep.xml`, `test/…/CarActionsTest.kt` | Tests for each cycle; speed persists |
| A6 | **List refresh.** `BrowseInvalidator.start` collects the Up Next, downloads, episode play-state and subscription flows, debounces 500 ms, and calls `notify` with the parent ids affected (`upnext`, `downloads`, `latest`, `podcast:<id>`, library folders) | `playback/auto/BrowseInvalidator.kt`, `test/…/BrowseInvalidatorTest.kt` | Test with Turbine-free fakes: one DB change → one notify per affected id |

**Wave 1 done 2026-10-25** (A1–A6 merged into `android-auto`; 1068 unit tests and `lintDebug` green). Notes from review:
- A2: *In progress* filters the History query (the last 500 episodes listened to), because no
  `positionMs > 0 and not PLAYED` episode query exists. Add `EpisodeDao.observeInProgress()` if
  older in-progress episodes go missing.
- A3: the provider is exported (lint `ExportedContentProvider`) because the car host reads it. It only
  fetches http(s) URLs, refuses bodies over 10 MB, and keeps the 500 most recently used images.
- A6: bounded to the newest 300 episodes plus recent history, and compares "started" rather than the
  position, so the 10 s position saves don't refresh car lists.
- A5: speed steps up to the next preset (1.1× → 1.2×). The sleep label is "Sleep 15 min" or
  "Sleep 30 min", worked out from the time left.

### Wave 2: wiring (O)

| ID | Task | Done when |
|---|---|---|
| A7 ✅ | In `LibraryCallback` and `PlaybackService`: (1) `setCommandButtonsForMediaItems` with the row actions and the overflow buttons in `setMediaButtonPreferences`. Refresh the overflow labels when speed, star or sleep change. (2) `onCustomCommand` dispatches to `CarActions` and the row-action handler. (3) `onSetMediaItems` / `onAddMediaItems` send items that have a `searchQuery` through `VoiceSearch`. (4) `onGetLibraryRoot` returns the last-played item as a playable root when `params.isRecent`, and sets root extras for the default content style. (5) Start `BrowseInvalidator` with `session.notifyChildrenChanged`. (6) Error results for an empty library and offline streams (`setPlaybackException` with an "Open McPodface" resolution intent) | `testDebugUnitTest` + `lintDebug` green; `dumpsys media_session` shows the custom actions; DHU screenshots of every tab and now playing look right |
| A8 ✅ (H, parallel with A7) | **Row actions handler**: implement `BrowseActions.run(action, episodeId)` in `playback/auto/BrowseActions.kt` (the episode id comes from `args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)`, parsed by A7). It does add/remove Up Next through `QueueRepository`, download/remove through `DownloadManager` (honour the cellular rule and never prompt; if blocked, return an error result with the message "Waiting for Wi-Fi"), star, and mark played through the same DAO call the phone UI uses. Plus tests | Tests green |

### Wave 3: polish and checks

| ID | Owner | Task | Done when |
|---|---|---|---|
| A9 ⏭ skipped | H | Car-friendly strings in `res/values/strings.xml` (`car_*`) for every label and message above; replace literals in `playback/auto/` | Lint has no hard-coded text warnings in `playback/` |
| A10 ✅ | O | Pass the driving rules: lists capped (Auto pages them, keep `LIST_LIMIT` 100), no more than 4 tabs, no tab over 1 level of folders before episodes except Library → Playlists → playlist | DHU walk-through |
| A11 ✅ | H | Add an "Android Auto" checklist to `TESTING.md` covering the steps below | File updated |

The A11 checklist steps:

- connect, and play/pause from the screen and the steering wheel;
- skip back and forward;
- each tab and the badges on rows;
- each row action;
- each overflow action;
- the voice phrases;
- what happens with no signal;
- disconnecting mid-episode, then resuming from the car's home card.

### Wave 4: optional (ask the user first)

| ID | Owner | Task |
|---|---|---|
| A12 | O | Up Next as the player timeline (current episode + queue items), so the car's queue button lists and jumps. This changes `PlaybackEngine`'s one-episode model (`complete()`, `playNext`, `onMediaItemTransition`) and must keep Chromecast working. Biggest risk in this plan |
| A13 | H | Settings → Android Auto: "Downloaded episodes only in the car" (new DataStore key `carDownloadedOnly`, also in backup), read by `BrowseTree` |

## Order and size

```
A0 → (A1 A2 A3 A4 A5 A6 in parallel) → (A7 + A8) → (A9 A10 A11) → [A12 A13 if wanted]
```

That makes about 12 tasks, 8 of them Haiku. Wave 1 depends only on the A0 signatures. A2 calls
the A1 functions by signature, so the two can run together, and the orchestrator runs tests once
both land.
