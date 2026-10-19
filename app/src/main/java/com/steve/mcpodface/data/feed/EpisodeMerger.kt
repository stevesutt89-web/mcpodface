package com.steve.mcpodface.data.feed

import com.steve.mcpodface.core.HtmlText
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeOrigin
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Merges parsed episodes into a podcast's stored episodes (PLAN.md §5 and §7.4). Shared by feed
 * refresh, RSS paging, Podcast Index backfill and backup import. Pure: the caller writes the result.
 *
 * Matching, in order: guid, then normalized enclosure URL, then title + publish date on the same day.
 * User state (playState, positionMs, playedAt, lastPlayedAt, isStarred, autoDownloadBlocked) is never
 * touched. Rows are never deleted.
 */
class EpisodeMerger @Inject constructor() {

    data class Result(
        /** New rows to insert (id = 0). */
        val toInsert: List<EpisodeEntity>,
        /** Existing rows whose feed fields or inCurrentFeed changed. */
        val toUpdate: List<EpisodeEntity>,
    )

    /**
     * @param origin FEED for a full live-feed refresh: matched rows take the feed's fields and rows
     * missing from the feed get inCurrentFeed = false. Any other origin is backfill: only unknown
     * episodes are added (with inCurrentFeed = false) and existing rows are left alone.
     */
    fun merge(
        podcastId: Long,
        existing: List<EpisodeEntity>,
        incoming: List<ParsedEpisode>,
        origin: EpisodeOrigin,
        now: Long,
    ): Result {
        val index = Index(existing)
        val isLiveFeed = origin == EpisodeOrigin.FEED
        val toInsert = mutableListOf<EpisodeEntity>()
        val updated = LinkedHashMap<Long, EpisodeEntity>()
        val seenExistingIds = HashSet<Long>()
        val insertedKeys = Index(emptyList())

        for (parsed in incoming) {
            val match = index.find(parsed)
            if (match != null) {
                if (!seenExistingIds.add(match.id)) continue // duplicate item in the same feed
                if (isLiveFeed) {
                    val merged = applyFeedFields(match, parsed)
                    if (merged != match) updated[match.id] = merged
                }
                continue
            }
            if (insertedKeys.find(parsed) != null) continue // duplicate of an item added above
            val entity = newEntity(podcastId, parsed, origin, now)
            insertedKeys.add(entity)
            toInsert += entity
        }

        if (isLiveFeed) {
            for (e in existing) {
                if (e.id !in seenExistingIds && e.inCurrentFeed) updated[e.id] = e.copy(inCurrentFeed = false)
            }
        }
        return Result(toInsert, updated.values.toList())
    }

    private fun applyFeedFields(e: EpisodeEntity, p: ParsedEpisode): EpisodeEntity {
        val text = if (p.descriptionHtml == e.descriptionHtml) e.descriptionText else HtmlText.toPlainText(p.descriptionHtml)
        return e.copy(
            title = p.title,
            descriptionHtml = p.descriptionHtml,
            descriptionText = text,
            summaryText = if (text == e.descriptionText) e.summaryText else HtmlText.summary(text),
            pubDate = p.pubDate ?: e.pubDate,
            durationSec = p.durationSec ?: e.durationSec,
            enclosureUrl = p.enclosureUrl,
            enclosureType = p.enclosureType ?: e.enclosureType,
            enclosureLength = p.enclosureLength ?: e.enclosureLength,
            artworkUrl = p.artworkUrl ?: e.artworkUrl,
            season = p.season ?: e.season,
            episodeNumber = p.episodeNumber ?: e.episodeNumber,
            chaptersUrl = p.chaptersUrl ?: e.chaptersUrl,
            transcriptUrl = p.transcriptUrl ?: e.transcriptUrl,
            inCurrentFeed = true,
        )
    }

    private fun newEntity(podcastId: Long, p: ParsedEpisode, origin: EpisodeOrigin, now: Long): EpisodeEntity {
        val text = HtmlText.toPlainText(p.descriptionHtml)
        return EpisodeEntity(
            podcastId = podcastId,
            guid = p.guid,
            title = p.title,
            descriptionHtml = p.descriptionHtml,
            descriptionText = text,
            summaryText = HtmlText.summary(text),
            pubDate = p.pubDate ?: now,
            durationSec = p.durationSec,
            enclosureUrl = p.enclosureUrl,
            enclosureType = p.enclosureType,
            enclosureLength = p.enclosureLength,
            artworkUrl = p.artworkUrl,
            season = p.season,
            episodeNumber = p.episodeNumber,
            chaptersUrl = p.chaptersUrl,
            transcriptUrl = p.transcriptUrl,
            origin = origin,
            inCurrentFeed = origin == EpisodeOrigin.FEED,
            firstSeenAt = now,
        )
    }

    /** Lookup tables for the three matching rules. */
    private class Index(episodes: List<EpisodeEntity>) {
        private val byGuid = HashMap<String, EpisodeEntity>()
        private val byEnclosure = HashMap<String, EpisodeEntity>()
        private val byTitleDay = HashMap<String, EpisodeEntity>()

        init {
            episodes.forEach(::add)
        }

        fun add(e: EpisodeEntity) {
            byGuid.putIfAbsent(e.guid, e)
            byEnclosure.putIfAbsent(EnclosureUrls.normalize(e.enclosureUrl), e)
            byTitleDay.putIfAbsent(titleDayKey(e.title, e.pubDate), e)
        }

        fun find(p: ParsedEpisode): EpisodeEntity? =
            byGuid[p.guid]
                ?: byEnclosure[EnclosureUrls.normalize(p.enclosureUrl)]
                ?: p.pubDate?.let { byTitleDay[titleDayKey(p.title, it)] }
    }

    companion object {
        internal fun titleDayKey(title: String, pubDate: Long): String {
            val day = Instant.ofEpochMilli(pubDate).atZone(ZoneOffset.UTC).toLocalDate()
            return title.trim().lowercase().replace(Regex("\\s+"), " ") + "|" + day
        }
    }
}
