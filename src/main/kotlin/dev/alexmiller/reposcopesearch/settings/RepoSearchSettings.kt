package dev.alexmiller.reposcopesearch.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.openapi.components.PersistentStateComponent
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(name = "RepoScopeSearchSettings", storages = [Storage("repoScopeSearch.xml")])
class RepoSearchSettings : PersistentStateComponent<RepoSearchSettings.State> {
    data class State(
        var gitLabUrl: String = "https://gitlab.com",
        var cacheDirectory: String = defaultCacheDirectory(),
        var contextLines: Int = 2,
        var maxFileMb: Int = 10,
        var maxResults: Int = 5000,
        var fuzzyThreshold: Double = 0.82
    )

    private var myState = State()

    val data: State get() = myState

    override fun getState(): State = myState
    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, myState)

    fun token(): String? = PasswordSafe.instance.getPassword(credentials())
    fun setToken(token: String) = PasswordSafe.instance.setPassword(credentials(), token.trim())
    fun clearToken() = PasswordSafe.instance.setPassword(credentials(), null)

    private fun credentials(): CredentialAttributes =
        CredentialAttributes("Repo Scope Search GitLab Token", normalizedBaseUrl(myState.gitLabUrl))

    companion object {
        fun get(project: Project): RepoSearchSettings = project.getService(RepoSearchSettings::class.java)
        fun normalizedBaseUrl(value: String): String = value.trim().trimEnd('/').removeSuffix("/api/v4")
        private fun defaultCacheDirectory(): String = Path.of(
            System.getProperty("user.home"), ".cache", "repo-scope-search"
        ).toString()
    }
}
