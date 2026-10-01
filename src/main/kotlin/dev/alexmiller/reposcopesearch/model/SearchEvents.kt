package dev.alexmiller.reposcopesearch.model

sealed interface SearchEvent {
    data class Status(val text: String) : SearchEvent
    data class Found(val result: SearchResult) : SearchEvent
    data class Warning(val text: String) : SearchEvent
    data class Finished(val results: Int, val repositories: Int, val cancelled: Boolean) : SearchEvent
}
