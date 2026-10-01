package dev.alexmiller.reposcopesearch.model

data class GitLabUser(
    val id: Long,
    val username: String,
    val name: String
)

data class GitLabProject(
    val id: Long,
    val name: String,
    val pathWithNamespace: String,
    val webUrl: String,
    val httpUrlToRepo: String,
    val defaultBranch: String?,
    val namespaceKind: String?,
    val namespaceId: Long?,
    val ownerId: Long?,
    val creatorId: Long?,
    val accessLevel: Int,
    val archived: Boolean,
    val emptyRepo: Boolean
) {
    fun priorityFor(user: GitLabUser): Int = when {
        ownerId == user.id || creatorId == user.id ||
            (namespaceKind == "user" && namespaceId == user.id) -> 0
        accessLevel >= 50 -> 1
        accessLevel >= 40 -> 2
        accessLevel >= 30 -> 3
        accessLevel >= 20 -> 4
        accessLevel >= 10 -> 5
        else -> 6
    }
}

enum class SearchMode { TEXT, REGEXP }

data class SearchOptions(
    val query: String,
    val mode: SearchMode,
    val caseSensitive: Boolean,
    val multiline: Boolean,
    val fuzzy: Boolean,
    val fuzzyThreshold: Double,
    val contextLines: Int,
    val maxFileBytes: Long,
    val maxResults: Int
)

data class BranchRef(val name: String, val commit: String)

data class RepositoryFile(val path: String, val size: Long?)

data class PreparedRepository(
    val project: GitLabProject,
    val directory: java.nio.file.Path,
    val branches: List<BranchRef>
)

data class RawMatch(
    val line: Int,
    val endLine: Int,
    val matchedText: String,
    val snippet: String,
    val similarity: Double? = null
)

data class SearchResult(
    val project: GitLabProject,
    val branch: String,
    val defaultBranch: String,
    val path: String,
    val commit: String,
    val match: RawMatch
) {
    val commitUrl: String get() = "${project.webUrl}/-/commit/$commit"
    val foundFileUrl: String get() = "${project.webUrl}/-/blob/$commit/${encodePath(path)}#L${match.line}"
    val mainFileUrl: String get() = "${project.webUrl}/-/blob/${encodeSegment(defaultBranch)}/${encodePath(path)}"

    private fun encodePath(value: String): String = value.split('/').joinToString("/") { encodeSegment(it) }
    private fun encodeSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
        .replace("+", "%20")
}
