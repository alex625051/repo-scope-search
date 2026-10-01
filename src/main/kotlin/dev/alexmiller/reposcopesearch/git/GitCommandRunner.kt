package dev.alexananev.reposcopesearch.git

import com.intellij.openapi.progress.ProgressIndicator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GitCommandRunner(private val token: String) {
    data class Output(val stdout: ByteArray, val stderr: String, val exitCode: Int)

    fun run(
        directory: Path?,
        args: List<String>,
        indicator: ProgressIndicator,
        timeoutMinutes: Long = 30,
        acceptedExitCodes: Set<Int> = setOf(0)
    ): Output {
        indicator.checkCanceled()
        val askPass = createAskPass()
        try {
            val command = mutableListOf("git").apply { addAll(args) }
            val process = ProcessBuilder(command).apply {
                if (directory != null) directory(directory.toFile())
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_ASKPASS"] = askPass.toAbsolutePath().toString()
                environment()["GIT_ASKPASS_REQUIRE"] = "force"
                environment()["REPO_SCOPE_TOKEN"] = token
                redirectErrorStream(false)
            }.start()
            val outFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readBytes() }
            val errFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
            while (process.isAlive) {
                indicator.checkCanceled()
                if (System.nanoTime() > deadline) { process.destroyForcibly(); throw IllegalStateException("Git timeout") }
                Thread.sleep(100)
            }
            val output = Output(outFuture.get(), errFuture.get(), process.exitValue())
            if (output.exitCode !in acceptedExitCodes) throw IllegalStateException(output.stderr.trim().ifBlank { "git exited ${output.exitCode}" })
            return output
        } finally { Files.deleteIfExists(askPass) }
    }

    private fun createAskPass(): Path {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val path = Files.createTempFile("repo-scope-askpass", if (windows) ".cmd" else ".sh")
        val body = if (windows) "@echo off\r\necho %REPO_SCOPE_TOKEN%\r\n" else "#!/bin/sh\nprintf '%s\\n' \"${'$'}REPO_SCOPE_TOKEN\"\n"
        Files.writeString(path, body)
        path.toFile().setExecutable(true, true)
        return path
    }
}
