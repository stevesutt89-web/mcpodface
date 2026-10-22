# Roadmap

Task IDs match the phases in [`PLAN.md` §14](../PLAN.md#14-phased-delivery).

## Done

| Phase | What |
|---|---|
| 0 Foundation | Project setup, theme, five-tab shell with last-tab memory, Room schema, release signing |
| 1 Data | RSS parser, HTML-to-text, DAOs, DataStore settings, episode merger, feed fetcher, download/cleanup policies |
| 2 Discover | Podcast Index + iTunes + Listen Notes search, trending, categories, podcast preview and subscribe, add by URL, OPML import/export |
| 3 Browsing | Shared components, Latest, Library, episode detail with tappable timestamps |
| 4 Playback | Media3 service, mini and full player, Up Next with drag reorder, sleep timer, trim silence, voice boost, resume rewind, listening sessions and History screen |
| 5 Downloads | Resumable download worker, background refresh and cleanup with notifications, Downloads tab (storage, sizes, "deletes in…" labels, bulk actions), download buttons with a mobile-data prompt |
| 6 Search & archive | Search engine (FTS4 query syntax, filters, relevance, snippets; 100k episodes in ~30 ms), persisted API rate limiter, slow archive backfill (RSS paging, then Podcast Index), archive status UI, "Your podcasts" search screen with filter chips and search within a podcast |
| 8 Backup | Export to a zip (data, OPML, optional streamed archive) and import (merge or replace) from Settings, via the system file picker |
| 7 Settings | Global settings, per-podcast settings sheet, preview before a change deletes files, battery-optimisation row, notification prompt |
| 8.11 Playlists | Playlists tab (Starred, History, Stats, playlists), manual playlists, smart playlists from rules; swipe right adds to Up Next |
| 9 Extras | Chapters, transcripts, share at timestamp, listening stats, home-screen widget, Android Auto browse tree and voice search, accessibility pass (TalkBack labels and actions, 48dp targets, large fonts), 200k-episode stress test |
| Design | Liquid Glass pass across the app |
| Next round | The 14 changes in [NEXT-ROUND.md](NEXT-ROUND.md): fly-off swipe, compact rows with podcast artwork, one snackbar host, playlist mosaic grid, Search back as a tab and Downloads under Playlists, neutral selected tab, white icon background, no Library badge, Settings sections, glass style / accent / reduce effects, customisable resume rewind |
| Fixes after phone testing | Smooth (blurred) artwork background instead of pixelated; Android Auto skip buttons as custom session commands; row state icons right-aligned; episode titles always two lines |
| Chromecast | `media3-cast`: Media3 `CastPlayer` over the ExoPlayer, cast button in the full player, downloaded episodes cast from their web URL |
| Podcast links | `podcast://`, `pcast://`, `feed:`, `itpc://`, Apple Podcasts and subscribeonandroid.com links, and links shared to the app, open the podcast's page |
| Release docs | [BUILDING.md §6](BUILDING.md#6-release-a-signed-apk-to-install-like-a-normal-app): keystore, version, signed APK, moving data from the debug app |

## Next

State on 2026-10-22: everything above is merged into `main` (PR #1). It compiles, and the unit tests
and `lintDebug` pass. The phone checks (swipe, artwork background, Android Auto, casting, podcast
links) went well, and `versionName` is `1.0.0` (`versionCode` stays 1).

**To finish release 1.0 (not done yet):**

1. Add the signing secrets under the repo's Settings -> Secrets and variables -> Actions
   (see [BUILDING.md, Releases on GitHub](BUILDING.md#releases-on-github-automatic)).
2. Tag it: `git tag v1.0.0 && git push origin v1.0.0`. That runs `.github/workflows/release.yml`,
   which tests, builds the signed APK and publishes it as a GitHub Release. If the run fails
   (for example a missing secret), fix it and re-run from the Actions tab; re-run manually only
   builds an artifact, so delete and re-push the tag to publish the Release.
3. Install the Release APK (`com.steve.mcpodface`) on both phones, then move data over from the
   debug app with Settings -> Backup and transfer (BUILDING.md §6, step 6).

Import OPML and the backup pickers now show a message instead of crashing when the phone has no file picker app.

Feature ideas, roughly in order (from a comparison with Pocket Casts and Player.fm):
- **Bookmarks:** save a moment in an episode with a note; jump back or share it.
- **Mark older as played** from an episode's menu.
- **Per-podcast episode filters:** skip episodes whose title has given words, and trailers or
  bonus episodes (`itunes:episodeType`).
- **Audio:** mono, an equaliser, volume levelling between shows.
- **Folders** in Library.
- **Shortcuts:** long-press app icon (Resume, Up Next) and a Quick Settings tile.
- **More Podcasting 2.0 tags:** `podcast:person`, `podcast:funding`, `podcast:soundbite`.
- **Start on Bluetooth connect** (opt-in).

Needs real phones:
- Smoothness on the `fast` build, the first-run notification prompt on Samsung, and the widget's
  look on the home screen.
- A real S25 Ultra → S23+ transfer using backup and restore.
- Android Auto in a car (or the desktop head unit).

Still open from Phase 9:
- **9.6** A screen-by-screen check at 200% font size (shared components and the player are done).
- **9.8** Baseline profiles (needs a benchmark module and a connected phone).
