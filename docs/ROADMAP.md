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
| 9 Extras | Chapters, transcripts, share at timestamp, listening stats, home-screen widget, Android Auto browse tree and voice search, 200k-episode stress test |
| Design | Liquid Glass pass across the app |

## Next

Needs real phones:
- Smoothness on the `fast` build, the first-run notification prompt on Samsung, and the widget's
  look on the home screen.
- A real S25 Ultra → S23+ transfer using backup and restore.
- Android Auto in a car (or the desktop head unit).

Still open from Phase 9:
- **9.6** Accessibility pass (TalkBack labels, font scaling to 200%).
- **9.8** Baseline profiles (needs a benchmark module and a connected phone).
