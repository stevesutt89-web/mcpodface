# Next round: feedback from using the app (2026-10-20)

Fourteen changes the user asked for after a day of using the app on the S25 Ultra. Nothing here is
built yet. Each item has the request, then notes on where the code lives and how to approach it.
The work is grouped so parallel agents don't edit the same files (see "Suggested split").

Before starting, read [`CLAUDE.md`](../CLAUDE.md) and `PLAN.md` §8 and §11.

## Before you start in a cloud session

- `local.properties` is git-ignored, so it isn't in the repo: no API keys and no signing. Builds
  still compile (missing keys only switch off the matching catalogue source), and unit tests don't
  need keys.
- There's no emulator or phone, so check UI with `@ThemePreviews` and unit tests. The user will
  install builds locally (`./gradlew :app:installFast` over wireless debugging).
- All 966 unit tests and `lintDebug` passed at commit `c32f3ab`. Keep it that way.
- Room is at schema v2 (`app/schemas/…/2.json`). Any entity change needs v3 plus a real migration
  (see `data/db/Migrations.kt` and `MigrationTest`).

## The requests

### Episode rows

1. **Swipe should fly off and come back.**
   - Today `ui/components/SwipeableEpisodeRow.kt` wraps Material3 `SwipeToDismissBox` and springs
     back (`confirmValueChange` returns false).
   - Once the swipe passes the threshold, the card should fly off that side of the screen. About
     500 ms later it slides back in from the **other** side. The same applies to both directions:
     right is Up Next on/off, left is played/unplayed.
   - Likely needs a custom `Animatable` offset with `draggable`/`anchoredDraggable`, instead of
     SwipeToDismissBox.
   - Keep the TalkBack custom actions added in 9.6, and the haptic tick.

2. **A white box stays at the bottom of the screen after a swipe.**
   - The user's screenshot shows a light rounded box under the floating tab bar. It appeared after
     swiping an episode that was already in Up Next, and stayed there.
   - Likely cause: `ui/latest/LatestScreen.kt` has its own `SnackbarHost` aligned `BottomCenter`.
     That's *behind* the floating tab bar, and a dark-mode snackbar uses the light inverse surface,
     so it reads as a white box.
   - The Up Next and played snackbars are short, but a stuck or queued one would sit there. A
     second `SnackbarHost` also exists in `ui/navigation/AppShell.kt`.
   - Fix:
     - use one host, padded above the chrome (`LocalChromePadding`) so it sits above the mini
       player and tab bar;
     - make sure no snackbar is shown with an indefinite duration;
     - consider dismissing the current snackbar before showing the next.

3. **Use podcast artwork in mixed lists.**
   - Rows should show the podcast's artwork, so it's easy to see which show an episode is from.
   - Exception: the podcast page itself (one podcast's episodes) keeps per-episode artwork.
   - `EpisodeRow` currently uses `episode.artworkUrl ?: podcastArtworkUrl`. Add a parameter such
     as `preferPodcastArtwork: Boolean = true`, and pass `false` only from `ui/podcast/`.

4. **Shorter episode rows.**
   - Today the row is: artwork | meta / title / summary, then a separate bottom row with play,
     duration and download state.
   - New layout:
     - the **play button sits under the artwork**, in the empty space to the left of the summary;
     - **duration (or time left) and the download icon** move to the top meta line, after
       "Podcast · 15h ago";
     - the bottom row goes away.
   - Keep the in-progress ring and progress bar somewhere sensible, e.g. a thin bar under the meta
     line or around the play button.
   - Update the `fontScale = 2f` preview, and the spoken summary if anything changes.

### Navigation, playlists and library

5. **Playlists screen as a grid, like Library.**
   - Two squares per row. Each square is a 2×2 mosaic of the **first four unique** podcast
     artworks in that playlist, like Spotify, with the title below the square.
   - Fewer than four artworks: use 1 large, or 2 or 3 plus a filled tile.
   - Applies to manual and smart playlists, plus Starred and Downloads (see 7).
   - "New playlist" and "New smart playlist" become a top-bar "+" menu, or trailing tiles.
   - Files: `ui/playlists/PlaylistsScreen.kt` / `PlaylistsViewModel.kt`. The artwork comes from
     `PlaylistRepository.observeEpisodes(id)` (take the distinct `podcastArtworkUrl`s).

6. **Stats and History move to Settings.**
   - Take them off the Playlists screen and add them as entries in the main Settings list (see 12).
   - The routes already exist: `StatsRoute`, `HistoryRoute`.

7. **Downloads leaves the tab bar.**
   - It becomes an entry (a tile) on the Playlists screen; the route `DownloadsRoute` already
     exists.
   - The Downloads screen needs a back button when it's opened this way.

8. **Search returns to the tab bar.**
   - The tabs become **Latest · Library · Playlists · Up Next · Search**.
   - Remove the top-bar `SearchButton` from every tab, and the back button from `SearchScreen`.
   - Restore `TopLevelDestination.SEARCH`; `AppTab.SEARCH` still exists.
   - A stored `lastTab = DOWNLOADS` must now fall back to a valid tab.

9. **Selected tab colour.**
   - In `ui/components/glass/GlassTabBar.kt`, the selected tab's icon and label should be **white
     in dark mode and black in light mode**, not the violet accent.
   - The frosted `GlassStyle.Selected` pill stays, with a neutral rather than violet tint.

10. **Icon background: white, not lavender.**
    - The user would prefer transparent. Adaptive icons need an opaque background layer, and One UI
      adds its own backplate to legacy transparent icons, so use **white**.
    - Change `launcher_background` in `res/values/colors.xml` from `#EEEAFF` to `#FFFFFF`. The same
      colour is used behind the splash icon.
    - Check the mango still reads on white; it has dark headphones, so it should.

11. **Remove the episode-count badge on Library.**
    - Drop the "99+" unplayed bubble on the Library grid (and list) artwork in `ui/library/`.

### Settings and playback

12. **Settings becomes a list of sections.**
    - The main Settings screen shows only section titles: Appearance, Playback, Downloads, Auto-delete,
      Refresh & notifications, Search & archive, Backup and transfer, Battery, About.
    - Then Listening stats and History (from 6).
    - Tapping a section opens a sub-screen holding today's rows for that section.
    - Files: `ui/settings/*`. The rows (`SettingsRows.kt`, `ChoiceRow.kt`, `SettingsOptions.kt`) can be
      reused. Add routes as needed. Keep the 7.3 cleanup preview and the 8.3 backup dialogs working.
    - Switching tabs must still close Settings (AppShell pops the settings routes on tab change).
      Make sure the sub-screens are popped too.

13. **Appearance: glass, accent colour and performance options.**
    - **Glass style**, e.g. Clear / Balanced (today's look) / Frosted. Feeds blur, tint alpha and
      lens strength in `ui/components/glass/Glass.kt` (`glassTint`, blur and lens values per
      `GlassStyle`), via a `CompositionLocal` provided by `AppTheme`.
    - **Accent colour**: presets such as Violet (today's `#6B4EFF` / `#9B8CFF`), Blue, Teal, Green,
      Orange, Pink and Graphite. The accent comes from `ui/theme`. "Tint player from artwork" keeps
      overriding it in the full player.
    - **Reduce visual effects (better performance)**: no live backdrop sampling. Every `glass()`
      takes the `glassCard`-style path (translucent fill and rim, no `drawBackdrop`), with no
      refraction or lens, and a cheaper `ArtworkAmbience` (solid tint, or none). Useful on the S23+
      and when battery saver is on.
    - New DataStore keys: `glassStyle`, `accentColor`, `reduceEffects`. Add them to `GlobalSettings`
      and `DataStoreSettingsRepository`. Key names are part of the backup format, so choose them
      once.

14. **Customisable resume rewind.**
    - Today `resumeRewind` is a boolean in `GlobalSettings` (check `PlaybackDecisions.startPosition`
      for the current rule).
    - Make two settings: **rewind amount** (e.g. 5 / 10 / 15 / 30 / 60 s) and **what counts as a long
      pause** (e.g. 1 / 5 / 15 / 30 min, 1 h), with the **default long pause = 1 minute**.
    - Keep the old key's meaning, i.e. on/off, or map it into the new ones. Don't rename the existing
      key.
    - Update the `PlaybackDecisions` tests.

15. **Wrap-up.**
    - Update the docs (README features, ROADMAP, ARCHITECTURE, and `PLAN.md` §8.1 tabs, §8.10
      settings, §11), commit and push.
    - The user installs the build on their phone locally (wireless debugging isn't reachable from a
      cloud session).

## Suggested split (parallel Haiku agents, one worktree each)

| Agent | Items | Owns |
|---|---|---|
| A: rows | 1, 2, 3, 4 | `ui/components/EpisodeRow.kt`, `SwipeableEpisodeRow.kt`, the snackbar host in `ui/latest/LatestScreen.kt` (and AppShell's host if merged into one), `ui/podcast/` (passing the artwork flag) |
| B: navigation | 5, 7, 8, 9, 11 | `ui/navigation/*`, `ui/components/glass/GlassTabBar.kt`, `ui/playlists/*`, `ui/library/*`, `ui/downloads/DownloadsScreen.kt` (back button), `ui/search/SearchScreen.kt`, removing `SearchButton` from the tab screens |
| C: settings | 6, 12, 13, 14 | `ui/settings/*`, `data/prefs/*`, `ui/theme/*`, `ui/components/glass/Glass.kt` + `ArtworkAmbience.kt`, `playback/PlaybackDecisions.kt` |
| Orchestrator | 10, merging, 15 | `res/values/colors.xml`; resolve the small overlaps below |

Known overlaps to handle at merge:
- A and B both touch `LatestScreen.kt`: A the snackbar, B removing the search button.
- B and C both need `AppShell.kt`: C's settings sub-routes and the Stats/History links. Either
  C adds `SettingsScreen` params with defaults and B wires them, or C owns the settings routes in
  AppShell and B only touches the tab code.
- B's tab colours (9) and C's accent (13) meet in `GlassTabBar`: B uses
  `AppTheme.colors.textPrimary` for the selected tab, so the accent preset doesn't affect it.
