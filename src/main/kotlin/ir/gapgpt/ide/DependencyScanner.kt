package ir.gapgpt.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.gapgpt.PluginLog
import ir.gapgpt.settings.GapGptSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Finds dependency lockfiles in the project and checks them against OSV.dev — only when the user
 * explicitly allows it in the Vulnerabilities tab. Only (ecosystem, name, version) triples are sent.
 */
object DependencyScanner {

    data class Dep(val ecosystem: String, val name: String, val version: String, val file: String)

    private val SKIP = setOf("node_modules", ".git", "build", ".gradle", ".dart_tool", "target", "dist", ".idea", "vendor", "Pods")

    fun inventory(projectDir: String?): List<Dep> {
        val root = projectDir?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        val out = LinkedHashSet<Dep>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in SKIP }
            .maxDepth(5)
            .filter { it.isFile }
            .forEach { f ->
                val rel = f.relativeTo(root).path
                runCatching {
                    when (f.name) {
                        "package-lock.json" -> npm(f, rel, out)
                        "pubspec.lock" -> pub(f, rel, out)
                        "requirements.txt" -> pip(f, rel, out)
                        "poetry.lock", "Cargo.lock" -> tomlPackages(f, rel, if (f.name == "Cargo.lock") "crates.io" else "PyPI", out)
                        "go.sum" -> goSum(f, rel, out)
                        "composer.lock" -> composer(f, rel, out)
                    }
                }.onFailure { PluginLog.warn("vulns", "Could not parse $rel: ${it.message}") }
            }
        return out.toList()
    }

    private fun npm(f: File, rel: String, out: MutableSet<Dep>) {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        o.getAsJsonObject("packages")?.entrySet()?.forEach { (k, v) ->
            if (!k.contains("node_modules/")) return@forEach
            val ver = v.asJsonObject.get("version")?.asString ?: return@forEach
            out += Dep("npm", k.substringAfterLast("node_modules/"), ver, rel)
        }
    }

    private fun pub(f: File, rel: String, out: MutableSet<Dep>) {
        var name: String? = null
        f.forEachLine { line ->
            Regex("""^  ([a-zA-Z0-9_]+):\s*$""").find(line)?.let { name = it.groupValues[1] }
            Regex("""^    version:\s*"?([^"\s]+)"?""").find(line)?.let { m ->
                name?.let { out += Dep("Pub", it, m.groupValues[1], rel) }
                name = null
            }
        }
    }

    private fun pip(f: File, rel: String, out: MutableSet<Dep>) = f.forEachLine { line ->
        Regex("""^\s*([A-Za-z0-9_.\-]+)\s*==\s*([A-Za-z0-9_.\-]+)""").find(line)?.let {
            out += Dep("PyPI", it.groupValues[1], it.groupValues[2], rel)
        }
    }

    private fun tomlPackages(f: File, rel: String, eco: String, out: MutableSet<Dep>) {
        f.readText().split("[[package]]").drop(1).forEach { block ->
            val n = Regex("""(?m)^name\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)
            val v = Regex("""(?m)^version\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)
            if (n != null && v != null) out += Dep(eco, n, v, rel)
        }
    }

    private fun goSum(f: File, rel: String, out: MutableSet<Dep>) = f.forEachLine { line ->
        val p = line.split(" ")
        if (p.size >= 2 && !p[1].endsWith("/go.mod")) out += Dep("Go", p[0], p[1].removePrefix("v"), rel)
    }

    private fun composer(f: File, rel: String, out: MutableSet<Dep>) {
        val o = JsonParser.parseString(f.readText()).asJsonObject
        listOf("packages", "packages-dev").forEach { key ->
            o.getAsJsonArray(key)?.forEach { p ->
                val po = p.asJsonObject
                out += Dep("Packagist", po.get("name").asString, po.get("version").asString.removePrefix("v"), rel)
            }
        }
    }

    private fun client(): HttpClient {
        val b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        GapGptSettings.get().httpsProxy.trim().takeIf { it.isNotBlank() }?.let { p ->
            runCatching {
                val u = URI(p)
                b.proxy(ProxySelector.of(InetSocketAddress(u.host, if (u.port > 0) u.port else 80)))
            }
        }
        return b.build()
    }

    /** Sends the inventory to OSV.dev and returns the vulnerable packages with advisory summaries. */
    fun scan(deps: List<Dep>): JsonObject {
        val http = client()
        val vulnerable = JsonArray()
        deps.chunked(500).forEach { chunk ->
            val body = JsonObject().apply {
                add("queries", JsonArray().apply {
                    chunk.forEach { d ->
                        add(JsonObject().apply {
                            add("package", JsonObject().apply {
                                addProperty("name", d.name)
                                addProperty("ecosystem", d.ecosystem)
                            })
                            addProperty("version", d.version)
                        })
                    }
                })
            }
            val req = HttpRequest.newBuilder(URI("https://api.osv.dev/v1/querybatch"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw IllegalStateException("OSV.dev HTTP ${resp.statusCode()}")
            val results = JsonParser.parseString(resp.body()).asJsonObject.getAsJsonArray("results")
            results.forEachIndexed { i, r ->
                val vulns = r.asJsonObject.getAsJsonArray("vulns") ?: return@forEachIndexed
                if (vulns.isEmpty) return@forEachIndexed
                val d = chunk[i]
                vulnerable.add(JsonObject().apply {
                    addProperty("ecosystem", d.ecosystem)
                    addProperty("name", d.name)
                    addProperty("version", d.version)
                    addProperty("file", d.file)
                    add("ids", JsonArray().apply { vulns.forEach { add(it.asJsonObject.get("id").asString) } })
                })
            }
        }
        // Fetch summaries for the first advisories (public, unauthenticated).
        var fetched = 0
        vulnerable.forEach { v ->
            val ids = v.asJsonObject.getAsJsonArray("ids")
            val details = JsonArray()
            ids.take(3).forEach { id ->
                if (fetched++ > 60) return@forEach
                runCatching {
                    val r = http.send(
                        HttpRequest.newBuilder(URI("https://api.osv.dev/v1/vulns/${id.asString}")).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    val o = JsonParser.parseString(r.body()).asJsonObject
                    details.add(JsonObject().apply {
                        addProperty("id", id.asString)
                        addProperty("summary", o.get("summary")?.asString ?: o.get("details")?.asString?.take(200) ?: "")
                        o.getAsJsonObject("database_specific")?.get("severity")?.let { addProperty("severity", it.asString) }
                    })
                }
            }
            v.asJsonObject.add("details", details)
        }
        PluginLog.info("vulns", "Scanned ${deps.size} components, ${vulnerable.size()} vulnerable")
        return JsonObject().apply {
            addProperty("scanned", deps.size)
            add("vulnerable", vulnerable)
        }
    }
}
