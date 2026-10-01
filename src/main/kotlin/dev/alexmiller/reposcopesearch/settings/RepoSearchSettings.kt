package dev.alexananev.reposcopesearch.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(name = "RepoScopeSearch", storages = [Storage("repo-scope-search.xml")])
class RepoSearchSettings : PersistentStateComponent<RepoSearchSettings.Data> {
    data class Data(
        var gitLabUrl: String = "https://gitlab.com",
        var contextLines: Int = 2,
        var maxFileMb: Int = 5,
        var maxResults: Int = 10_000,
        var cacheDirectory: String = "${PathManager.getSystemPath()}/repo-scope-search-cache",
        var parallelism: Int = 4,
        var fileMask: String = "",
        var defaultBranchOnly: Boolean = false
    )
    private var value = Data()
    val data: Data get() = value
    override fun getState(): Data = value
    override fun loadState(state: Data) { value = state }
    fun token(): String = PasswordSafe.instance.getPassword(CredentialAttributes(TOKEN_KEY)).orEmpty()
    fun setToken(token: String) = PasswordSafe.instance.set(CredentialAttributes(TOKEN_KEY), Credentials("gitlab", token.ifBlank { null }))
    companion object {
        private const val TOKEN_KEY = "RepoScopeSearch.GitLabToken"
        fun instance(): RepoSearchSettings = service()
        fun normalizedBaseUrl(value: String) = value.trim().trimEnd('/').removeSuffix("/api/v4")
    }
}
