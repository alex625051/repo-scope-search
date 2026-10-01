package dev.alexmiller.reposcopesearch.search

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import dev.alexmiller.reposcopesearch.api.GitLabApiClient
import dev.alexmiller.reposcopesearch.git.RepositoryCache
import dev.alexmiller.reposcopesearch.model.*
import dev.alexmiller.reposcopesearch.settings.RepoSearchSettings
import dev.alexmiller.reposcopesearch.util.TextDecoder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SearchEngine(
    private val baseUrl: String,
    private val token: String,
    private val cacheDirectory: Path
) {
    fun search(options: SearchOptions, indicator: ProgressIndicator, emit: (SearchEvent) -> Unit) {
        ContentMatcher.validate(options)?.let { throw IllegalArgumentException(it) }
        val api = GitLabApiClient(baseUrl, token)
        emit(SearchEvent.Status("Проверка токена и загрузка списка проектов…"))
        val user = api.currentUser()
        val projects = api.listAccessibleProjects()
            .sortedWith(compareBy<GitLabProject> { it.priorityFor(user) }
                .thenByDescending { it.accessLevel }
                .thenBy { it.pathWithNamespace.lowercase() })
        emit(SearchEvent.Status("Доступно репозиториев: ${projects.size}. Первый проход: основные ветки."))

        val cache = RepositoryCache(cacheDirectory, baseUrl, token)
        val prepared = ArrayList<PreparedRepository>(projects.size)
        val count = AtomicInteger(0)
        var cancelled = false
        try {
            for ((index, project) in projects.withIndex()) {
                if (count.get() >= options.maxResults) break
                indicator.checkCanceled()
                indicator.isIndeterminate = false
                indicator.fraction = index.toDouble() / projects.size.coerceAtLeast(1)
                indicator.text = "Основные ветки: ${project.pathWithNamespace}"
                emit(SearchEvent.Status("[1/2] ${index + 1}/${projects.size}: ${project.pathWithNamespace}"))
                val repository = try {
                    cache.prepare(project, indicator)
                } catch (e: Exception) {
                    emit(SearchEvent.Warning("${project.pathWithNamespace}: ${e.message}"))
                    continue
                }
                prepared += repository
                val defaultName = project.defaultBranch
                val defaultRef = repository.branches.firstOrNull { it.name == defaultName }
                if (defaultRef == null || defaultName == null) {
                    emit(SearchEvent.Warning("${project.pathWithNamespace}: основная ветка не найдена"))
                } else {
                    searchBranch(cache, repository, defaultRef, defaultName, options, count, indicator, emit)
                }
            }

            if (count.get() < options.maxResults) {
                emit(SearchEvent.Status("Второй проход: остальные ветки всех репозиториев."))
                outer@ for ((index, repository) in prepared.withIndex()) {
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / prepared.size.coerceAtLeast(1)
                    indicator.text = "Остальные ветки: ${repository.project.pathWithNamespace}"
                    val defaultName = repository.project.defaultBranch
                    val otherBranches = repository.branches.filter { it.name != defaultName }
                    for ((branchIndex, branch) in otherBranches.withIndex()) {
                        if (count.get() >= options.maxResults) break@outer
                        indicator.checkCanceled()
                        emit(SearchEvent.Status("[2/2] ${index + 1}/${prepared.size}, ветка ${branchIndex + 1}/${otherBranches.size}: ${repository.project.pathWithNamespace} / ${branch.name}"))
                        searchBranch(cache, repository, branch, defaultName ?: branch.name, options, count, indicator, emit)
                    }
                }
            }
        } catch (_: ProcessCanceledException) {
            cancelled = true
        } finally {
            emit(SearchEvent.Finished(count.get(), prepared.size, cancelled))
        }
    }

    private fun searchBranch(
        cache: RepositoryCache,
        repository: PreparedRepository,
        branch: BranchRef,
        defaultBranch: String,
        options: SearchOptions,
        count: AtomicInteger,
        indicator: ProgressIndicator,
        emit: (SearchEvent) -> Unit
    ) {
        val files = try {
            cache.listFiles(repository, branch.commit, indicator)
        } catch (e: Exception) {
            emit(SearchEvent.Warning("${repository.project.pathWithNamespace}/${branch.name}: ${e.message}"))
            return
        }
        for (file in files) {
            indicator.checkCanceled()
            if (count.get() >= options.maxResults) return
            if (file.size != null && file.size > options.maxFileBytes) continue
            val bytes = try {
                cache.readFile(repository, branch.commit, file.path, indicator)
            } catch (e: Exception) {
                emit(SearchEvent.Warning("${repository.project.pathWithNamespace}/${branch.name}/${file.path}: ${e.message}"))
                continue
            }
            if (bytes.size > options.maxFileBytes) continue
            val text = TextDecoder.decode(bytes) ?: continue
            for (match in ContentMatcher.find(text, options)) {
                indicator.checkCanceled()
                val next = count.incrementAndGet()
                emit(SearchEvent.Found(SearchResult(repository.project, branch.name, defaultBranch, file.path, branch.commit, match)))
                if (next >= options.maxResults) return
            }
        }
    }
}
