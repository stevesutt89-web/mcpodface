package com.steve.mcpodface.data.db

// Stored by name (Room's built-in enum handling). Never rename a constant without a migration.

enum class FeedType { EPISODIC, SERIAL }

enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST }

enum class QueueAdd { OFF, TOP, BOTTOM }

/** How we learned of an episode (PLAN.md §7.4). */
enum class EpisodeOrigin { FEED, FEED_PAGED, PODCAST_INDEX }

enum class PlayState { UNPLAYED, IN_PROGRESS, PLAYED }

enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED, PAUSED_NETWORK }

enum class DownloadSource { AUTO, MANUAL }

enum class BackfillStatus { PENDING, IN_PROGRESS, COMPLETE, FAILED, PAUSED }

/** Playlists (PLAN.md §8.11). LISTEN_LATER is the built-in one filled by swiping right on a row. */
enum class PlaylistKind { LISTEN_LATER, MANUAL, SMART }
