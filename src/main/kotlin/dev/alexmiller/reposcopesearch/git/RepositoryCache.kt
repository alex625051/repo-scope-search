package dev.alexananev.reposcopesearch.git

import com.intellij.openapi.progress.ProgressIndicator
import dev.alexananev.reposcopesearch.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class RepositoryCache(private val root: Path, token: String) {
    private val git = GitCommandRunner(token)
    private val locks = ConcurrentHashMap<Long, Any>()

    fun prepare(project: ProjectInfo, defaultOnly: Boolean, maxFileBytes: Long, indicator: ProgressIndicator): PreparedRepository =
        synchronized(locks.computeIfAbsent(project.id) { Any() }) {
            Files.createDirectories(root)
            val dir = root.resolve("${project.id}.git")
            if (!Files.exists(dir.resolve("HEAD"))) {
                Files.createDirectories(dir)
                git.run(dir, listOf("init", "--bare"), indicator)
                git.run(dir, listOf("remote", "add", "origin", project.httpUrl), indicator)
            } else {
                git.run(dir, listOf("remote", "set-url", "origin", project.httpUrl), indicator)
            }
            val refspec = if (defaultOnly && project.defaultBranch != null)
                "+refs/heads/${project.defaultBranch}:refs/remotes/origin/${project.defaultBranch}"
            else "+refs/heads/*:refs/remotes/origin/*"
            git.run(dir, listOf("-c", "credential.helper=", "fetch", "--force", "--prune", "--depth=1", "--filter=blob:limit=$maxFileBytes", "origin", refspec), indicator)
            val refs = git.run(dir, listOf("for-each-ref", "--format=%(refname:strip=3)%09%(objectname)", "refs/remotes/origin"), indicator)
                .stdout.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.mapNotNull {
                    val p = it.split('\t', limit = 2); if (p.size == 2 && p[0] != "HEAD") BranchRef(p[0], p[1]) else null
                }.toList()
            PreparedRepository(project, dir, refs)
        }

    data class TreeFile(val path: String, val size: Long?)
    fun listFiles(repo: PreparedRepository, commit: String, indicator: ProgressIndicator): List<TreeFile> {
        val bytes = git.run(repo.directory, listOf("ls-tree", "-r", "-l", "-z", commit), indicator).stdout
        return bytes.toString(Charsets.UTF_8).split('\u0000').mapNotNull { row ->
            if (row.isBlank()) return@mapNotNull null
            val tab = row.indexOf('\t'); if (tab < 0) return@mapNotNull null
            val meta = row.substring(0, tab).split(Regex("\\s+")); if (meta.size < 4 || meta[1] != "blob") return@mapNotNull null
            TreeFile(row.substring(tab + 1), meta[3].toLongOrNull())
        }
    }

    fun grepFiles(repo: PreparedRepository, commit: String, options: SearchOptions, indicator: ProgressIndicator): Set<String> {
        val args = mutableListOf("grep", "-l", "-I")
        if (!options.caseSensitive) args += "-i"
        args += if (options.mode == QueryMode.TEXT) "-F" else "-E"
        args += listOf("-e", options.query, commit, "--")
        return git.run(repo.directory, args, indicator, acceptedExitCodes = setOf(0, 1))
            .stdout.toString(Charsets.UTF_8).lineSequence()
            .map { it.removePrefix("$commit:") }.filter { it.isNotBlank() }.toSet()
    }

    fun readFile(repo: PreparedRepository, commit: String, path: String, indicator: ProgressIndicator): ByteArray =
        git.run(repo.directory, listOf("cat-file", "blob", "$commit:$path"), indicator, 5).stdout

    fun deleteAll() {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
