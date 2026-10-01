package dev.alexananev.reposcopesearch.model

sealed interface SearchEvent {
    data class Status(val text: String) : SearchEvent
    data class Match(val result: SearchResult) : SearchEvent
    data class Warning(val text: String) : SearchEvent
    data class Finished(val matches: Int, val repositories: Int, val cancelled: Boolean) : SearchEvent
}
