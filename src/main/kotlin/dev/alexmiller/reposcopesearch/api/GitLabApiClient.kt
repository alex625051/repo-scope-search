package dev.alexmiller.reposcopesearch.api

import dev.alexmiller.reposcopesearch.model.GitLabProject
import dev.alexmiller.reposcopesearch.model.GitLabUser
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GitLabApiClient(baseUrl: String, private val token: String) {
    private val baseUrl = baseUrl.trim().trimEnd('/').removeSuffix("/api/v4")
    private val apiUrl = "$baseUrl/api/v4"
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun currentUser(): GitLabUser {
        val obj = getObject("$apiUrl/user")
        return GitLabUser(obj.long("id"), obj.string("username"), obj.string("name"))
    }

    fun listAccessibleProjects(): List<GitLabProject> {
        val result = ArrayList<GitLabProject>()
        var page = 1
        do {
            val response = get("$apiUrl/projects?simple=false&archived=false&per_page=100&page=$page&order_by=id&sort=asc")
            val array = json.parseToJsonElement(response.body()).jsonArray
            result += array.map { parseProject(it.jsonObject) }
            page = response.headers().firstValue("x-next-page").orElse("").toIntOrNull() ?: 0
        } while (page > 0)
        return result.filterNot { it.emptyRepo }
    }

    fun tokenCreationUrl(): String {
        val name = enc("Repo Scope Search")
        val description = enc("Read-only access for PyCharm cross-repository search")
        return "$baseUrl/-/user_settings/personal_access_tokens?name=$name&description=$description&scopes=read_api,read_repository"
    }

    private fun parseProject(obj: JsonObject): GitLabProject {
        val permissions = obj.objectOrNull("permissions")
        val direct = permissions?.objectOrNull("project_access")?.nullableLong("access_level")?.toInt() ?: 0
        val group = permissions?.objectOrNull("group_access")?.nullableLong("access_level")?.toInt() ?: 0
        val namespace = obj.objectOrNull("namespace")
        val owner = obj.objectOrNull("owner")
        return GitLabProject(
            id = obj.long("id"),
            name = obj.string("name"),
            pathWithNamespace = obj.string("path_with_namespace"),
            webUrl = obj.string("web_url"),
            httpUrlToRepo = obj.string("http_url_to_repo"),
            defaultBranch = obj.nullableString("default_branch"),
            namespaceKind = namespace?.nullableString("kind"),
            namespaceId = namespace?.nullableLong("id"),
            ownerId = owner?.nullableLong("id"),
            creatorId = obj.nullableLong("creator_id"),
            accessLevel = maxOf(direct, group),
            archived = obj.boolean("archived"),
            emptyRepo = obj.boolean("empty_repo")
        )
    }

    private fun getObject(url: String): JsonObject = json.parseToJsonElement(get(url).body()).jsonObject

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("PRIVATE-TOKEN", token)
            .header("Accept", "application/json")
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val body = response.body().take(500)
            throw IOException("GitLab API ${response.statusCode()}: $body")
        }
        return response
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.nullableString(name: String): String? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.long ?: 0
    private fun JsonObject.nullableLong(name: String): Long? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.boolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false
}
