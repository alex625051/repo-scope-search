package dev.alexananev.reposcopesearch.model

enum class QueryMode { TEXT, REGEX }
enum class SearchBackend { LOCAL, SERVER }

data class GitLabUser(val id: Long, val username: String)
data class ProjectInfo(
    val id: Long,
    val name: String,
    val pathWithNamespace: String,
    val webUrl: String,
    val httpUrl: String,
    val defaultBranch: String?,
    val namespaceKind: String?,
    val namespaceId: Long?,
    val creatorId: Long?,
    val accessLevel: Int
)
data class BranchRef(val name: String, val commit: String)
data class PreparedRepository(val project: ProjectInfo, val directory: java.nio.file.Path, val branches: List<BranchRef>)
data class SearchOptions(
    val query: String,
    val mode: QueryMode,
    val backend: SearchBackend,
    val caseSensitive: Boolean,
    val multiline: Boolean,
    val fuzzy: Boolean,
    val fuzzyThreshold: Double,
    val filePathRegex: String?,
    val defaultBranchOnly: Boolean,
    val contextLines: Int,
    val maxFileBytes: Long,
    val maxResults: Int,
    val parallelism: Int
)
data class SearchResult(
    val project: String,
    val branch: String,
    val path: String,
    val line: String,
    val snippet: String,
    val commit: String,
    val similarity: Double?,
    val commitUrl: String,
    val fileAtCommitUrl: String,
    val fileAtDefaultUrl: String,
    val backend: SearchBackend
)
