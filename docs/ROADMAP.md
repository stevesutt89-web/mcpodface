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
| 6.1, 6.4 | Search query syntax builder; persisted API rate limiter and slow archive backfill (RSS paging, then Podcast Index) |
| 7 Settings | Global settings, per-podcast settings sheet, preview before a change deletes files, battery-optimisation row, notification prompt |
| Design | Liquid Glass pass across the app |

## Next

- **6.2–6.3** Full-text search of your episodes:
  - filters and sort;
  - highlighted snippets.
- **6.5** Archive status UI.
- **8.x** Full backup and restore (zip of JSON plus OPML), to move between phones.
- Wire up the "Tint player from artwork" setting.

## Later (optional)

Chapters, transcripts, listening stats, a home-screen widget (Glance), an Android Auto browse
tree, an accessibility pass, sharing an episode at a timestamp, and baseline profiles.
