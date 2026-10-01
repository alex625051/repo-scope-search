plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.alexananev"
version = "0.2.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("pycharmLocalPath").orNull

        if (!localPath.isNullOrBlank()) {
            local(localPath)
        } else {
            pycharmCommunity("2025.2.6")
            bundledPlugin("PythonCore")
        }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        id = "dev.alexananev.repo-scope-search"
        name = "Repo Scope Search"
        version = provider { project.version.toString() }

        description = """
            <h2>Repo Scope Search</h2>

            <p>
                Search file contents across every GitLab repository accessible
                to your account.
            </p>

            <p>
                Repo Scope Search provides reliable, streaming content search
                across personal, group, and shared GitLab projects.
                It is intended for cases where the built-in GitLab or IDE
                search does not return complete results.
            </p>

            <h3>Features</h3>

            <ul>
                <li>Search across all GitLab projects available to the token.</li>
                <li>Stream results while repositories are being processed.</li>
                <li>Search default branches first, then other branches.</li>
                <li>Optional search only in default branches.</li>
                <li>Plain-text and regular-expression search.</li>
                <li>Case-sensitive and multiline modes.</li>
                <li>Approximate text search.</li>
                <li>Regular-expression file path filter.</li>
                <li>Local Git search and experimental GitLab server search.</li>
                <li>Configurable parallel repository processing.</li>
                <li>Persistent shallow bare-repository cache.</li>
            </ul>

            <h3>Local search</h3>

            <p>
                Local mode maintains a shallow bare Git cache. The first search
                may download a significant amount of repository data. Subsequent
                searches reuse the cache and fetch repository updates.
            </p>

            <p>
                For ordinary text searches, Git grep is used as a preliminary
                filter. Multiline and approximate searches require scanning
                matching files locally and can take considerably longer.
            </p>

            <h3>Server search</h3>

            <p>
                Server mode uses the GitLab Search API and does not clone
                repositories. Its availability and completeness depend on the
                GitLab edition, server configuration, enabled search engine,
                and index state.
            </p>

            <p>
                Searching non-default branches through the GitLab API is a
                best-effort feature. Some GitLab installations may ignore the
                requested branch and search only the indexed default branch.
            </p>

            <h3>Authentication</h3>

            <p>
                A GitLab personal access token with
                <code>read_api</code> and <code>read_repository</code>
                permissions is required. The token is stored using the
                IntelliJ Platform Password Safe.
            </p>

            <h3>Resource usage</h3>

            <ul>
                <li>The first local search can use substantial network traffic.</li>
                <li>The local repository cache can occupy significant disk space.</li>
                <li>Parallel search increases CPU, memory, disk, and network usage.</li>
                <li>Approximate and multiline modes are the most resource-intensive.</li>
            </ul>

            <h3>Recommended settings</h3>

            <p>
                For hundreds of repositories, start with default branches only,
                a restrictive file path expression, and four parallel workers.
            </p>

            <h3>Author</h3>

            <p>Developed by Alex Ananev.</p>

            <h3>License</h3>

            <p>Apache License 2.0.</p>
        """.trimIndent()

        changeNotes = """
            <h2>Version 0.2.3</h2>

            <h3>New features</h3>

            <ul>
                <li>Added a regular-expression file path filter.</li>
                <li>Added search limited to default branches.</li>
                <li>Added experimental GitLab server-side search.</li>
                <li>Added configurable parallel repository processing.</li>
                <li>Added cache size information and cache cleanup.</li>
            </ul>

            <h3>Performance</h3>

            <ul>
                <li>Default branches are fetched and searched first.</li>
                <li>Other branches are fetched only before the second pass.</li>
                <li>Local repositories use a shallow bare Git cache.</li>
                <li>Plain-text search uses Git grep as a preliminary filter.</li>
                <li>Large Git blobs are excluded from the initial fetch when supported.</li>
            </ul>

            <h3>Documentation</h3>

            <ul>
                <li>Added resource-usage documentation.</li>
                <li>Added server-search limitations and warnings.</li>
                <li>Added Apache License 2.0.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "252"
            untilBuild = "252.*"
        }

        vendor {
            name = "Alex Ananev"

            email = "globalresearching@yandex.com"
            url = "https://github.com/alex625051"
        }
    }
}