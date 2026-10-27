package com.steve.mcpodface.ui.search

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.catalog.CatalogCategory
import com.steve.mcpodface.data.catalog.CatalogException
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.catalog.CatalogRepository
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.prefs.SearchMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var catalog: FakeCatalog
    private lateinit var subscribed: MutableStateFlow<List<PodcastEntity>>
    private lateinit var dao: PodcastDao
    private lateinit var subscriptions: SubscriptionRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        catalog = FakeCatalog()
        subscribed = MutableStateFlow(emptyList())
        dao = mockk()
        every { dao.observeSubscribed() } returns subscribed
        coEvery { dao.getSubscribed() } answers { subscribed.value }
        subscriptions = mockk()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): SearchViewModel =
        SearchViewModel(catalog, dao, subscriptions).also { vm ->
            // Keep a collector attached, as the screen does, so the state is observed like in the app.
            backgroundScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun `is always Discover, even with subscriptions`() = runTest(dispatcher) {
        subscribed.value = listOf(entity("https://example.com/a.xml"))
        catalog.searchResult = { listOf(podcast(1)) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("radio")
        advanceUntilIdle()

        assertThat(vm.uiState.value.mode).isEqualTo(SearchMode.DISCOVER)
        assertThat(catalog.searchCalls).containsExactly("radio")
    }

    @Test
    fun `rapid typing produces one search after the 300ms debounce`() = runTest(dispatcher) {
        catalog.searchResult = { listOf(podcast(1)) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("r")
        advanceTimeBy(100)
        vm.onQueryChange("ra")
        advanceTimeBy(100)
        vm.onQueryChange("radiolab")
        advanceTimeBy(299)
        assertThat(catalog.searchCalls).isEmpty()

        advanceUntilIdle()
        assertThat(catalog.searchCalls).containsExactly("radiolab")
        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Results(listOf(podcast(1))))
    }

    @Test
    fun `blank query loads trending for the All category`() = runTest(dispatcher) {
        catalog.trendingResult = { listOf(podcast(1), podcast(2)) }
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(catalog.trendingCalls).containsExactly(null)
        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Trending(listOf(podcast(1), podcast(2))))
        assertThat(catalog.searchCalls).isEmpty()
    }

    @Test
    fun `selecting a category reloads trending for that category name`() = runTest(dispatcher) {
        catalog.trendingResult = { category -> listOf(podcast(category?.length ?: 0)) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onCategorySelected("Comedy")
        advanceUntilIdle()

        assertThat(catalog.trendingCalls).containsExactly(null, "Comedy").inOrder()
        assertThat(vm.uiState.value.selectedCategory).isEqualTo("Comedy")
        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Trending(listOf(podcast(6))))
    }

    @Test
    fun `a text search ignores the selected category`() = runTest(dispatcher) {
        catalog.searchResult = { listOf(podcast(1)) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("radiolab")
        advanceUntilIdle()
        vm.onCategorySelected("Comedy")
        advanceUntilIdle()

        assertThat(catalog.searchCalls).containsExactly("radiolab")
        assertThat(catalog.trendingCalls).containsExactly(null)
    }

    @Test
    fun `a failed search shows Error and retry searches again`() = runTest(dispatcher) {
        catalog.failingSearches = 1
        catalog.searchResult = { listOf(podcast(1)) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("radio")
        advanceUntilIdle()
        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Error)

        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Results(listOf(podcast(1))))
        assertThat(catalog.searchCalls).containsExactly("radio", "radio").inOrder()
    }

    @Test
    fun `a search with no matches shows Empty`() = runTest(dispatcher) {
        catalog.searchResult = { emptyList() }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("zzqx")
        advanceUntilIdle()

        assertThat(vm.uiState.value.discover).isEqualTo(DiscoverState.Empty)
    }

    @Test
    fun `results are marked subscribed by feed url`() = runTest(dispatcher) {
        catalog.searchResult = { listOf(podcast(1), podcast(2)) }
        subscribed.value = listOf(entity(podcast(2).feedUrl))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onQueryChange("radio")
        advanceUntilIdle()

        assertThat(vm.uiState.value.subscribedFeedUrls).containsExactly(podcast(2).feedUrl)
    }

    @Test
    fun `categories are capped at 20`() = runTest(dispatcher) {
        catalog.categoryList = (1..25).map { CatalogCategory(it, "Category $it") }
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.categories).hasSize(20)
    }

    @Test
    fun `tapping a result opens the podcast and emits its id`() = runTest(dispatcher) {
        coEvery { subscriptions.open(any(), any(), any(), any(), any()) } returns
            SubscriptionRepository.OpenResult.Ok(42L)
        catalog.searchResult = { listOf(podcast(1)) }
        val vm = createViewModel()
        val events = mutableListOf<SearchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.onResultClick(podcast(1))
        advanceUntilIdle()

        assertThat(events).containsExactly(SearchEvent.OpenPodcast(42L))
        coVerify { subscriptions.open(podcast(1).feedUrl, podcast(1), null, null, waitForFeed = false) }
        assertThat(vm.uiState.value.openingFeedUrl).isNull()
    }

    @Test
    fun `a failed open emits a message and clears the progress state`() = runTest(dispatcher) {
        coEvery { subscriptions.open(any(), any(), any(), any(), any()) } returns
            SubscriptionRepository.OpenResult.Failed("This feed is down")
        val vm = createViewModel()
        val events = mutableListOf<SearchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        advanceUntilIdle()

        vm.onResultClick(podcast(1))
        advanceUntilIdle()

        assertThat(events).containsExactly(SearchEvent.ShowMessage("This feed is down"))
        assertThat(vm.uiState.value.openingFeedUrl).isNull()
    }

    private fun entity(feedUrl: String) = PodcastEntity(
        feedUrl = feedUrl,
        title = feedUrl,
        isSubscribed = true,
    )

    private fun podcast(i: Int) = CatalogPodcast(
        feedUrl = "https://example.com/$i.xml",
        title = "Podcast $i",
        author = "Author $i",
    )

    private class FakeCatalog : CatalogRepository {
        val searchCalls = mutableListOf<String>()
        val trendingCalls = mutableListOf<String?>()
        var searchResult: (String) -> List<CatalogPodcast> = { emptyList() }
        var trendingResult: (String?) -> List<CatalogPodcast> = { emptyList() }
        var categoryList: List<CatalogCategory> = emptyList()
        /** The next N search calls throw CatalogException. */
        var failingSearches = 0

        override suspend fun search(term: String): List<CatalogPodcast> {
            searchCalls += term
            if (failingSearches > 0) {
                failingSearches--
                throw CatalogException("offline")
            }
            return searchResult(term)
        }

        override suspend fun trending(category: String?): List<CatalogPodcast> {
            trendingCalls += category
            return trendingResult(category)
        }

        override suspend fun categories(): List<CatalogCategory> = categoryList

        override suspend fun lookupByFeedUrl(feedUrl: String): CatalogPodcast? = null
    }
}
