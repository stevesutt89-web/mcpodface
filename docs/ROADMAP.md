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
| 6 Search & archive | Search engine (FTS4 query syntax, filters, relevance, snippets; 100k episodes in ~30 ms), persisted API rate limiter, slow archive backfill (RSS paging, then Podcast Index), archive status UI |
| 8.1–8.2 | Backup export (zip: data, OPML, optional streamed archive) and import (merge or replace) |
| 7 Settings | Global settings, per-podcast settings sheet, preview before a change deletes files, battery-optimisation row, notification prompt |
| Design | Liquid Glass pass across the app |

## Next

- **6.3** The "Your podcasts" search screen (in progress).
- **8.3** Backup export/import screens (in progress).

## Later (optional)

Chapters, transcripts, listening stats, a home-screen widget (Glance), an Android Auto browse
tree, an accessibility pass, sharing an episode at a timestamp, and baseline profiles.
