package pl.iptv.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.iptv.tv.data.StubeSearch
import pl.iptv.tv.data.YtKeyless

/** Stan prawej kolumny panelu wyszukiwania STube. */
sealed interface StubeSearchState {
    /** Puste pole – podpowiedzi (ostatnio oglądane). */
    data object Idle : StubeSearchState
    data class Loading(val query: String, val filter: StubeSearch.Filter) : StubeSearchState
    data class Results(val query: String, val filter: StubeSearch.Filter, val items: List<StubeSearch.Item>) : StubeSearchState
    data class Error(val query: String, val message: String) : StubeSearchState
}

/**
 * (7.17) Wyszukiwanie na żywo w STube: [searchQuery] (klawiatura ekranowa, głos, historia) i [selectedFilter] łączone przez
 * `combine` → `debounce` (pisanie: [DEBOUNCE_MS]; zmiana filtra – bez opóźnienia) → `distinctUntilChanged` →
 * `flatMapLatest` (nowa litera anuluje poprzednie, nieaktualne zapytanie sieciowe). Wynik: [results].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class STubeSearchViewModel : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(StubeSearch.Filter.ALL)
    val searchQuery: StateFlow<String> = query.asStateFlow()
    val selectedFilter: StateFlow<StubeSearch.Filter> = filter.asStateFlow()

    private val historyFlow = MutableStateFlow<List<String>>(emptyList())
    /** Historia wyszukiwania (najnowsze pierwsze, ≤ [StubeSearch.HISTORY_MAX]). */
    val history: StateFlow<List<String>> = historyFlow.asStateFlow()

    /** Liczba zapytań wysłanych do źródła (testy: anulowane litery nie pytają sieci). */
    @Volatile var requests = 0; private set

    /** Ostatni filtr po `debounce` – zmiana filtra przechodzi od razu, kolejne litery czekają [DEBOUNCE_MS]. */
    private var lastFilter: StubeSearch.Filter? = null

    val results: StateFlow<StubeSearchState> = combine(query, filter) { q, f -> q.trim() to f }
        .debounce { (q, f) ->
            val immediate = f != lastFilter || q.isEmpty()
            lastFilter = f
            if (immediate) 0L else DEBOUNCE_MS
        }
        .distinctUntilChanged()
        .flatMapLatest { (q, f) ->
            flow {
                if (q.isEmpty()) { emit(StubeSearchState.Idle); return@flow }
                emit(StubeSearchState.Loading(q, f))
                requests++
                val r = runCatching { engine(q, f) }
                emit(r.fold({ StubeSearchState.Results(q, f, it.distinctBy { i -> i.key }) }, { StubeSearchState.Error(q, it.message ?: "Błąd wyszukiwania") }))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StubeSearchState.Idle)

    init { refreshHistory() }

    fun setQuery(q: String) { query.value = q.take(MAX_LEN) }
    fun type(s: String) { setQuery(query.value + s) }
    fun backspace() { if (query.value.isNotEmpty()) setQuery(query.value.dropLast(1)) }
    fun clear() { setQuery("") }
    fun setFilter(f: StubeSearch.Filter) { filter.value = f }

    /** Hasło do historii (otwarty wynik, głos) – zapis w tle, lista odświeżana. */
    fun remember(q: String = query.value) {
        val t = q.trim(); if (t.isEmpty()) return
        viewModelScope.launch { runCatching { StubeSearch.remember(t) }; refreshHistory() }
    }
    fun forget(q: String) { viewModelScope.launch { runCatching { StubeSearch.forget(q) }; refreshHistory() } }
    fun refreshHistory() { viewModelScope.launch { historyFlow.value = runCatching { StubeSearch.history() }.getOrDefault(emptyList()) } }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_LEN = 100
        /** Źródło wyników (testy podmieniają). */
        @Volatile var engine: suspend (String, StubeSearch.Filter) -> List<StubeSearch.Item> = { q, f -> YtKeyless.searchItems(q, f) }
    }
}
