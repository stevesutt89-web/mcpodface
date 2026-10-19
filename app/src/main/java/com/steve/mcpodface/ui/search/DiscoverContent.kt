package com.steve.mcpodface.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.catalog.CatalogCategory
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.library.ShapedArtwork
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private val ArtworkSize = 60.dp
private val ArtworkRadius = 14.dp
/** Hairlines start where the title text starts: gutter + artwork + gap. */
private val DividerInset = Spacing.m + ArtworkSize + Spacing.m

/**
 * Discover mode (PLAN.md §8.9): trending with category chips when the query is blank, otherwise
 * search results. Stateless: the ViewModel owns the query, debounce and requests.
 * [contentPadding] carries the screen's bottom chrome clearance into the list.
 */
@Composable
fun DiscoverContent(
    query: String,
    state: DiscoverState,
    categories: List<CatalogCategory>,
    selectedCategory: String?,
    subscribedFeedUrls: Set<String>,
    openingFeedUrl: String?,
    onCategorySelected: (String?) -> Unit,
    onResultClick: (CatalogPodcast) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (query.isBlank()) {
            item(key = "trending-header") { SectionTitle("Trending") }
            item(key = "category-chips") {
                CategoryChips(categories, selectedCategory, onCategorySelected)
            }
        }
        when (state) {
            DiscoverState.Loading -> Unit // the thin progress bar under the field shows this
            is DiscoverState.Trending -> podcastRows(state.podcasts, subscribedFeedUrls, openingFeedUrl, onResultClick)
            is DiscoverState.Results -> podcastRows(state.podcasts, subscribedFeedUrls, openingFeedUrl, onResultClick)
            DiscoverState.Empty -> item(key = "empty") { CenteredMessage("No podcasts found") }
            DiscoverState.Error -> item(key = "error") { ErrorMessage(onRetry) }
        }
    }
}

private fun LazyListScope.podcastRows(
    podcasts: List<CatalogPodcast>,
    subscribedFeedUrls: Set<String>,
    openingFeedUrl: String?,
    onResultClick: (CatalogPodcast) -> Unit,
) {
    itemsIndexed(
        items = podcasts,
        // Index first: the API can return the same feed twice, and duplicate keys crash LazyColumn.
        key = { index, podcast -> "$index:${podcast.feedUrl}" },
    ) { index, podcast ->
        Column {
            PodcastResultRow(
                podcast = podcast,
                subscribed = podcast.feedUrl in subscribedFeedUrls,
                opening = podcast.feedUrl == openingFeedUrl,
                onClick = { onResultClick(podcast) },
            )
            if (index < podcasts.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = DividerInset),
                    color = AppTheme.colors.hairline,
                )
            }
        }
    }
}

@Composable
private fun PodcastResultRow(
    podcast: CatalogPodcast,
    subscribed: Boolean,
    opening: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapedArtwork(
            url = podcast.artworkUrl,
            contentDescription = null,
            shape = remember { RoundedRectangle(ArtworkRadius) },
            modifier = Modifier.size(ArtworkSize),
        )
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
            Text(
                podcast.title,
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            podcast.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            opening -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.colors.accent)
            subscribed -> Icon(
                Icons.Outlined.Check,
                contentDescription = "Subscribed",
                tint = AppTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.textPrimary,
        modifier = Modifier.padding(start = Spacing.m, top = Spacing.m, bottom = Spacing.s),
    )
}

@Composable
private fun CategoryChips(
    categories: List<CatalogCategory>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.padding(bottom = Spacing.m),
    ) {
        item(key = "all") {
            GlassChip("All", selected = selectedCategory == null, onClick = { onCategorySelected(null) })
        }
        items(categories, key = { it.id }) { category ->
            GlassChip(
                category.name,
                selected = selectedCategory == category.name,
                onClick = { onCategorySelected(category.name) },
            )
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorMessage(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            "Couldn't search. Check your connection.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        GlassButton(onClick = onRetry) { Text("Retry") }
    }
}

private val previewPodcasts = listOf(
    CatalogPodcast(
        feedUrl = "https://example.invalid/radiolab.xml",
        title = "Radiolab",
        author = "WNYC Studios",
    ),
    CatalogPodcast(
        feedUrl = "https://example.invalid/hardcore-history.xml",
        title = "Dan Carlin's Hardcore History: a very long title that wraps onto two lines",
        author = "Dan Carlin",
    ),
    CatalogPodcast(
        feedUrl = "https://example.invalid/no-author.xml",
        title = "Untitled Feed",
    ),
)

private val previewCategories = listOf(
    CatalogCategory(1, "Technology"),
    CatalogCategory(2, "Comedy"),
    CatalogCategory(3, "News"),
)

@ThemePreviews
@Composable
private fun DiscoverResultsPreview() {
    AppTheme {
        Surface {
            DiscoverContent(
                query = "radio",
                state = DiscoverState.Results(previewPodcasts),
                categories = previewCategories,
                selectedCategory = null,
                subscribedFeedUrls = setOf(previewPodcasts.first().feedUrl),
                openingFeedUrl = null,
                onCategorySelected = {},
                onResultClick = {},
                onRetry = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DiscoverTrendingPreview() {
    AppTheme {
        Surface {
            DiscoverContent(
                query = "",
                state = DiscoverState.Trending(previewPodcasts),
                categories = previewCategories,
                selectedCategory = "Comedy",
                subscribedFeedUrls = emptySet(),
                openingFeedUrl = previewPodcasts[1].feedUrl,
                onCategorySelected = {},
                onResultClick = {},
                onRetry = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DiscoverEmptyPreview() {
    AppTheme {
        Surface {
            DiscoverContent(
                query = "zzqx",
                state = DiscoverState.Empty,
                categories = emptyList(),
                selectedCategory = null,
                subscribedFeedUrls = emptySet(),
                openingFeedUrl = null,
                onCategorySelected = {},
                onResultClick = {},
                onRetry = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DiscoverErrorPreview() {
    AppTheme {
        Surface {
            DiscoverContent(
                query = "radio",
                state = DiscoverState.Error,
                categories = emptyList(),
                selectedCategory = null,
                subscribedFeedUrls = emptySet(),
                openingFeedUrl = null,
                onCategorySelected = {},
                onResultClick = {},
                onRetry = {},
            )
        }
    }
}
