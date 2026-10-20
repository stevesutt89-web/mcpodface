package com.steve.mcpodface.data.links

import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.network.ITunesApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a podcast link (PLAN.md §8.9): resolves it to a feed URL and opens the podcast's page
 * unsubscribed, the same as tapping a Discover result.
 */
@Singleton
class PodcastLinkOpener @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val iTunes: ITunesApi,
) {
    suspend fun open(text: String): SubscriptionRepository.OpenResult =
        when (val link = parsePodcastLink(text)) {
            null -> SubscriptionRepository.OpenResult.Failed("That link isn't a podcast")
            // No catalogue info, so wait for the feed: a page that isn't a feed fails here, and the
            // podcast page opens with its real title.
            is PodcastLink.Feed -> {
                val first = subscriptions.open(link.url)
                if (first is SubscriptionRepository.OpenResult.Failed && link.fallbackUrl != null) {
                    subscriptions.open(link.fallbackUrl)
                } else {
                    first
                }
            }
            is PodcastLink.ApplePodcasts -> {
                val match = try {
                    iTunes.lookup(link.id.toString()).results.firstOrNull { !it.feedUrl.isNullOrBlank() }
                } catch (e: Exception) {
                    null
                }
                if (match == null) {
                    SubscriptionRepository.OpenResult.Failed("Couldn't find that podcast's feed")
                } else {
                    // iTunes gives the title and artwork, so the page can open at once (like Discover).
                    val hint = CatalogPodcast(
                        feedUrl = match.feedUrl!!,
                        title = match.collectionName.orEmpty(),
                        author = match.artistName,
                        artworkUrl = match.artworkUrl600 ?: match.artworkUrl100,
                        itunesId = match.collectionId,
                    )
                    subscriptions.open(hint.feedUrl, hint, waitForFeed = false)
                }
            }
        }
}
