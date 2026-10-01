package dev.alexananev.reposcopesearch.search

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import dev.alexananev.reposcopesearch.api.GitLabApiClient
import dev.alexananev.reposcopesearch.git.RepositoryCache
import dev.alexananev.reposcopesearch.model.*
import dev.alexananev.reposcopesearch.util.TextDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SearchEngine(private val api: GitLabApiClient, token: String, cachePath: Path) {
    private val cache = RepositoryCache(cachePath, token)

    fun search(options: SearchOptions, indicator: ProgressIndicator, emit: (SearchEvent) -> Unit) {
        val count = AtomicInteger(); var repositories = 0; var cancelled = false
        try {
            validate(options)
            val user = api.currentUser()
            val projects = api.projects().sortedWith(compareBy<ProjectInfo> { priority(it, user) }.thenBy { it.pathWithNamespace.lowercase() })
            repositories = projects.size
            emit(SearchEvent.Status("Доступно проектов: ${projects.size}. Режим: ${options.backend}."))
            if (options.backend == SearchBackend.LOCAL) local(projects, options, count, indicator, emit)
            else server(projects, options, count, indicator, emit)
        } catch (_: ProcessCanceledException) { cancelled = true }
        finally { emit(SearchEvent.Finished(count.get(), repositories, cancelled)) }
    }

    private fun local(projects: List<ProjectInfo>, o: SearchOptions, count: AtomicInteger, indicator: ProgressIndicator, emit: (SearchEvent) -> Unit) {
        val prepared = java.util.concurrent.ConcurrentHashMap<Long, PreparedRepository>()
        runParallel(projects, o.parallelism, indicator) { project ->
            if (count.get() >= o.maxResults) return@runParallel
            try {
                emit(SearchEvent.Status("[кэш] ${project.pathWithNamespace}"))
                val repo = cache.prepare(project, true, o.maxFileBytes, indicator)
                prepared[project.id] = repo
                val main = repo.branches.firstOrNull { it.name == project.defaultBranch }
                if (main != null) searchLocalBranch(repo, main, project.defaultBranch ?: main.name, o, count, indicator, emit)
                else emit(SearchEvent.Warning("${project.pathWithNamespace}: основная ветка не найдена"))
            } catch (e: Exception) { if (e is ProcessCanceledException) throw e else emit(SearchEvent.Warning("${project.pathWithNamespace}: ${e.message}")) }
        }
        if (o.defaultBranchOnly || count.get() >= o.maxResults) return
        emit(SearchEvent.Status("Второй проход: остальные ветки."))
        runParallel(projects, o.parallelism, indicator) { project ->
            if (count.get() >= o.maxResults) return@runParallel
            try {
                val repo = cache.prepare(project, false, o.maxFileBytes, indicator)
                prepared[project.id] = repo
                for (branch in repo.branches.filter { it.name != project.defaultBranch }) {
                    if (count.get() >= o.maxResults) break
                    searchLocalBranch(repo, branch, project.defaultBranch ?: branch.name, o, count, indicator, emit)
                }
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                emit(SearchEvent.Warning("${project.pathWithNamespace}: ${e.message}"))
            }
        }
    }

    private fun searchLocalBranch(repo: PreparedRepository, branch: BranchRef, defaultBranch: String, o: SearchOptions, count: AtomicInteger, indicator: ProgressIndicator, emit: (SearchEvent) -> Unit) {
        indicator.checkCanceled(); emit(SearchEvent.Status("[локально] ${repo.project.pathWithNamespace} / ${branch.name}"))
        val mask = o.filePathRegex?.takeIf { it.isNotBlank() }?.let(::Regex)
        val files = cache.listFiles(repo, branch.commit, indicator).filter { (it.size == null || it.size <= o.maxFileBytes) && (mask == null || mask.containsMatchIn(it.path)) }
        val candidates = if (!o.fuzzy && !o.multiline && o.mode == QueryMode.TEXT)
            cache.grepFiles(repo, branch.commit, o, indicator)
        else null
        for (file in files) {
            if (count.get() >= o.maxResults) return
            if (candidates != null && file.path !in candidates) continue
            val text = TextDecoder.decode(cache.readFile(repo, branch.commit, file.path, indicator)) ?: continue
            for (hit in ContentMatcher.find(text, o)) {
                if (!reserve(count, o.maxResults)) return
                emit(SearchEvent.Match(result(repo.project, branch, defaultBranch, file.path, "${hit.startLine}-${hit.endLine}", hit.snippet, hit.similarity, SearchBackend.LOCAL)))
            }
        }
    }

    private fun server(projects: List<ProjectInfo>, o: SearchOptions, count: AtomicInteger, indicator: ProgressIndicator, emit: (SearchEvent) -> Unit) {
        if (o.fuzzy) emit(SearchEvent.Warning("Серверный режим не поддерживает fuzzy: выполняется точный запрос GitLab."))
        if (!o.defaultBranchOnly) emit(SearchEvent.Warning("GitLab может игнорировать ref и искать только в индексируемой основной ветке; результаты дедуплицируются."))
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun pass(branchesFor: (ProjectInfo) -> List<BranchRef>) {
            runParallel(projects, o.parallelism, indicator) { project ->
                try {
                    for (requested in branchesFor(project)) {
                        if (count.get() >= o.maxResults) break
                        indicator.checkCanceled()
                        emit(SearchEvent.Status("[сервер] ${project.pathWithNamespace} / ${requested.name}"))
                        for (hit in api.searchBlobs(project.id, o.query, requested.name, o.mode == QueryMode.REGEX)) {
                            if (hit.path.isBlank()) continue
                            val mask = o.filePathRegex?.takeIf { it.isNotBlank() }?.let(::Regex)
                            if (mask != null && !mask.containsMatchIn(hit.path)) continue
                            val actualBranch = hit.ref?.takeIf { it.isNotBlank() } ?: requested.name
                            val actualCommit = if (actualBranch == requested.name) requested.commit else ""
                            val key = "${project.id}|$actualBranch|${hit.path}|${hit.startLine}|${hit.data}"
                            if (!seen.add(key) || !reserve(count, o.maxResults)) continue
                            emit(SearchEvent.Match(result(project, BranchRef(actualBranch, actualCommit), project.defaultBranch ?: actualBranch, hit.path, hit.startLine.toString(), hit.data, null, SearchBackend.SERVER)))
                        }
                    }
                } catch (e: Exception) {
                    if (e is ProcessCanceledException) throw e
                    emit(SearchEvent.Warning("${project.pathWithNamespace}: серверный поиск: ${e.message}"))
                }
            }
        }

        pass { project -> listOfNotNull(project.defaultBranch?.let { BranchRef(it, "") }) }
        if (!o.defaultBranchOnly && count.get() < o.maxResults) {
            emit(SearchEvent.Status("Серверный второй проход: остальные ветки."))
            pass { project -> api.branches(project.id).filter { it.name != project.defaultBranch } }
        }
    }

    private fun result(p: ProjectInfo, b: BranchRef, default: String, path: String, line: String, snippet: String, similarity: Double?, backend: SearchBackend): SearchResult {
        fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")
        val ref = b.commit.ifBlank { b.name }
        val commitUrl = if (b.commit.isBlank()) p.webUrl else "${p.webUrl}/-/commit/${enc(b.commit)}"
        return SearchResult(p.pathWithNamespace, b.name, path, line, snippet, b.commit, similarity,
            commitUrl, "${p.webUrl}/-/blob/${enc(ref)}/${path.split('/').joinToString("/") { enc(it) }}",
            "${p.webUrl}/-/blob/${enc(default)}/${path.split('/').joinToString("/") { enc(it) }}", backend)
    }

    private fun runParallel(projects: List<ProjectInfo>, threads: Int, indicator: ProgressIndicator, action: (ProjectInfo) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 16)); val failed = AtomicBoolean(false)
        try {
            val futures = projects.map { p -> pool.submit { if (!failed.get()) { indicator.checkCanceled(); action(p) } } }
            futures.forEach { try { it.get() } catch (e: java.util.concurrent.ExecutionException) { val c = e.cause; if (c is ProcessCanceledException) { failed.set(true); throw c } else throw e } }
        } finally { pool.shutdownNow() }
    }
    private fun reserve(c: AtomicInteger, max: Int): Boolean { while (true) { val n = c.get(); if (n >= max) return false; if (c.compareAndSet(n, n + 1)) return true } }
    private fun priority(p: ProjectInfo, u: GitLabUser): Int = when { p.namespaceKind == "user" && p.namespaceId == u.id -> 0; p.creatorId == u.id -> 1; p.accessLevel >= 50 -> 2; p.accessLevel >= 40 -> 3; p.accessLevel >= 30 -> 4; p.accessLevel >= 20 -> 5; else -> 6 }
    private fun validate(o: SearchOptions) { if (o.query.isBlank()) error("Пустой запрос"); o.filePathRegex?.takeIf { it.isNotBlank() }?.let(::Regex); if (o.mode == QueryMode.REGEX) Regex(o.query) }
}
