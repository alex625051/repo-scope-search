package dev.alexananev.reposcopesearch.api

import dev.alexananev.reposcopesearch.model.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class GitLabApiClient(private val baseUrl: String, private val token: String) {
    private val api = "${baseUrl.trimEnd('/')}/api/v4"
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun currentUser(): GitLabUser {
        val o = getObject("$api/user")
        return GitLabUser(o.long("id"), o.string("username"))
    }

    fun projects(): List<ProjectInfo> =
        paged(
            "$api/projects" +
                    "?simple=false" +
                    "&archived=false" +
                    "&order_by=id" +
                    "&sort=asc"
        ) { o ->

            val namespace =
                o["namespace"].asObjectOrNull()

            val permissions =
                o["permissions"].asObjectOrNull()

            val projectAccess =
                permissions
                    ?.get("project_access")
                    .asObjectOrNull()
                    ?.intOrNull("access_level")
                    ?: 0

            val groupAccess =
                permissions
                    ?.get("group_access")
                    .asObjectOrNull()
                    ?.intOrNull("access_level")
                    ?: 0

            ProjectInfo(
                id = o.long("id"),
                name = o.string("name"),
                pathWithNamespace = o.string("path_with_namespace"),
                webUrl = o.string("web_url"),
                httpUrl = o.string("http_url_to_repo"),
                defaultBranch = o.stringOrNull("default_branch"),
                namespaceKind = namespace?.stringOrNull("kind"),
                namespaceId = namespace?.longOrNull("id"),
                creatorId = o.longOrNull("creator_id"),
                accessLevel = maxOf(projectAccess, groupAccess)
            )
        }

    fun branches(projectId: Long): List<BranchRef> = paged("$api/projects/$projectId/repository/branches") { o ->
        BranchRef(o.string("name"), o["commit"]!!.jsonObject.string("id"))
    }

    data class ServerHit(val path: String, val data: String, val startLine: Int, val ref: String?)

    fun searchBlobs(projectId: Long, query: String, branch: String?, regex: Boolean): List<ServerHit> {
        val params = linkedMapOf("scope" to "blobs", "search" to query, "search_type" to "zoekt")
        if (branch != null) params["ref"] = branch
        if (regex) params["regex"] = "true"
        val base = "$api/projects/$projectId/search?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return paged(base) { o ->
            ServerHit(
                o.stringOrNull("path") ?: o.stringOrNull("filename") ?: "",
                o.stringOrNull("data") ?: "",
                o.intOrNull("startline") ?: 1,
                o.stringOrNull("ref")
            )
        }
    }

    private fun <T> paged(base: String, mapper: (JsonObject) -> T): List<T> {
        val result = ArrayList<T>(); var page = 1
        while (true) {
            val separator = if ('?' in base) "&" else "?"
            val response = get("$base${separator}per_page=100&page=$page")
            val array = json.parseToJsonElement(response.body()).jsonArray
            result += array.map { mapper(it.jsonObject) }
            val next = response.headers().firstValue("X-Next-Page").orElse("")
            if (next.isBlank()) break
            page = next.toInt()
        }
        return result
    }

    private fun getObject(url: String) = json.parseToJsonElement(get(url).body()).jsonObject
    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url)).header("PRIVATE-TOKEN", token).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) throw IllegalStateException("GitLab HTTP ${response.statusCode()}: ${response.body().take(500)}")
        return response
    }
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
    private fun JsonObject.string(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.stringOrNull(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.longOrNull(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.intOrNull(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        this as? JsonObject
}
