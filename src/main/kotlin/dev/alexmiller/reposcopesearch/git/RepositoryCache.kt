package dev.alexmiller.reposcopesearch.git

import com.intellij.openapi.progress.ProgressIndicator
import dev.alexmiller.reposcopesearch.model.BranchRef
import dev.alexmiller.reposcopesearch.model.GitLabProject
import dev.alexmiller.reposcopesearch.model.PreparedRepository
import dev.alexmiller.reposcopesearch.model.RepositoryFile
import java.nio.file.Files
import java.nio.file.Path

class RepositoryCache(
    private val root: Path,
    private val baseUrl: String,
    private val token: String,
    private val git: GitCommandRunner = GitCommandRunner()
) {
    fun prepare(project: GitLabProject, indicator: ProgressIndicator): PreparedRepository {
        Files.createDirectories(root)
        val directory = root.resolve("${project.id}.git")
        val (environment, cleanup) = git.credentialEnvironment(baseUrl, token, root)
        try {
            if (!Files.exists(directory.resolve("HEAD"))) {
                Files.createDirectories(directory)
                git.run(directory, listOf("init", "--bare"), indicator)
                git.run(directory, listOf("remote", "add", "origin", project.httpUrlToRepo), indicator)
            } else {
                val current = git.run(directory, listOf("remote", "get-url", "origin"), indicator).stdoutText().trim()
                if (current != project.httpUrlToRepo) {
                    git.run(directory, listOf("remote", "set-url", "origin", project.httpUrlToRepo), indicator)
                }
            }
            git.run(
                directory,
                listOf("-c", "http.extraHeader=", "-c", "credential.helper=", "fetch", "--prune", "--force", "--no-tags", "origin", "+refs/heads/*:refs/remotes/origin/*"),
                indicator,
                environment
            )
        } finally {
            cleanup()
        }

        val refs = git.run(
            directory,
            listOf("for-each-ref", "--format=%(refname:strip=3)%09%(objectname)", "refs/remotes/origin"),
            indicator
        ).stdoutText().lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[0] != "HEAD") BranchRef(parts[0], parts[1]) else null
            }
            .distinctBy { it.name }
            .toList()

        return PreparedRepository(project, directory, refs)
    }

    fun listFiles(repository: PreparedRepository, commit: String, indicator: ProgressIndicator): List<RepositoryFile> =
        git.run(repository.directory, listOf("ls-tree", "-r", "-l", "-z", commit), indicator, binaryOutput = true)
            .stdout.toString(Charsets.UTF_8).split('\u0000').mapNotNull { record ->
                if (record.isBlank()) return@mapNotNull null
                val tab = record.indexOf('\t')
                if (tab < 0) return@mapNotNull null
                val metadata = record.substring(0, tab).trim().split(Regex("\\s+"))
                val path = record.substring(tab + 1)
                val size = metadata.getOrNull(3)?.takeUnless { it == "-" }?.toLongOrNull()
                RepositoryFile(path, size)
            }

    fun readFile(repository: PreparedRepository, commit: String, path: String, indicator: ProgressIndicator): ByteArray =
        git.run(repository.directory, listOf("show", "$commit:$path"), indicator, binaryOutput = true).stdout
}
