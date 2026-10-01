package dev.alexmiller.reposcopesearch.git

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GitCommandException(message: String) : RuntimeException(message)

data class GitCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    fun stdoutText(): String = stdout.toString(StandardCharsets.UTF_8)
}

class GitCommandRunner {
    fun run(
        workingDirectory: Path?,
        args: List<String>,
        indicator: ProgressIndicator,
        environment: Map<String, String> = emptyMap(),
        binaryOutput: Boolean = false
    ): GitCommandResult {
        indicator.checkCanceled()
        val command = listOf("git") + args
        val builder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        builder.environment().putAll(environment)
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"

        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw GitCommandException("Не удалось запустить git. Установите Git и проверьте PATH: ${e.message}")
        }
        process.outputStream.close()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = thread(name = "repo-search-git-stdout", isDaemon = true) {
            process.inputStream.use { it.copyTo(stdout) }
        }
        val errThread = thread(name = "repo-search-git-stderr", isDaemon = true) {
            process.errorStream.use { it.copyTo(stderr) }
        }

        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled()
            }
        } catch (e: ProcessCanceledException) {
            process.destroyForcibly()
            throw e
        } finally {
            outThread.join(5000)
            errThread.join(5000)
        }

        val result = GitCommandResult(process.exitValue(), stdout.toByteArray(), stderr.toString(StandardCharsets.UTF_8))
        if (result.exitCode != 0) {
            throw GitCommandException(sanitize(result.stderr.ifBlank { result.stdoutText() }, environment))
        }
        if (!binaryOutput && stdout.size() > 128 * 1024 * 1024) {
            throw GitCommandException("Команда git вернула слишком большой ответ")
        }
        return result
    }

    fun credentialEnvironment(baseUrl: String, token: String, cacheRoot: Path): Pair<Map<String, String>, () -> Unit> {
        Files.createDirectories(cacheRoot)
        val askPass = Files.createTempFile(cacheRoot, "askpass-", if (isWindows()) ".bat" else ".sh")
        val script = if (isWindows()) {
            "@echo off\r\n" +
                "echo %~1 | findstr /I \"Username\" >nul\r\n" +
                "if %errorlevel%==0 (echo oauth2) else (echo %GITLAB_SEARCH_TOKEN%)\r\n"
        } else {
            "#!/bin/sh\n" +
                "case \"${'$'}1\" in\n" +
                "  *Username*) printf '%s\\n' 'oauth2' ;;\n" +
                "  *) printf '%s\\n' \"${'$'}GITLAB_SEARCH_TOKEN\" ;;\n" +
                "esac\n"
        }
        Files.writeString(askPass, script)
        askPass.toFile().setExecutable(true, true)
        val env = mapOf(
            "GIT_ASKPASS" to askPass.toAbsolutePath().toString(),
            "GITLAB_SEARCH_TOKEN" to token
        )
        return env to { Files.deleteIfExists(askPass) }
    }

    private fun sanitize(message: String, environment: Map<String, String>): String {
        var result = message
        environment["GITLAB_SEARCH_TOKEN"]?.takeIf { it.isNotBlank() }?.let { result = result.replace(it, "***") }
        return result.trim().take(2000)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
