package ir.gapgpt.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Reads Claude Code's on-disk configuration for the Skills and Rules tabs:
 * skills / commands / agents (project, user, plugins), CLAUDE.md memory files,
 * `.claude/rules`, permission rules and hooks from the settings files.
 */
object ClaudeConfig {

    private val home = File(System.getProperty("user.home"))
    private val userDir = File(home, ".claude")

    // ------------------------------------------------------------------ skills / commands / agents

    fun skills(projectDir: String?, extraPluginPaths: List<String>): JsonObject {
        val skills = JsonArray()
        val commands = JsonArray()
        val agents = JsonArray()
        val seen = HashSet<String>()

        fun item(arr: JsonArray, name: String, f: File, source: String) {
            val kind = if (arr === skills) "s" else if (arr === commands) "c" else "a"
            if (!seen.add("$kind:$name:$source")) return
            arr.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", describe(f))
                addProperty("source", source)
                addProperty("path", f.absolutePath)
            })
        }

        fun scanSkills(dir: File, prefix: String, source: String) {
            if (!dir.isDirectory) return
            dir.walkTopDown().maxDepth(3).filter { it.isFile && it.name == "SKILL.md" }.forEach { f ->
                item(skills, prefix + f.parentFile.name, f, source)
            }
        }

        fun scanMd(dir: File, arr: JsonArray, prefix: String, source: String) {
            if (!dir.isDirectory) return
            dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "md" }.forEach { f ->
                item(arr, prefix + f.relativeTo(dir).path.removeSuffix(".md").replace(File.separatorChar, ':'), f, source)
            }
        }

        projectDir?.let { p ->
            scanSkills(File(p, ".claude/skills"), "", "project")
            scanMd(File(p, ".claude/commands"), commands, "", "project")
            scanMd(File(p, ".claude/agents"), agents, "", "project")
        }
        scanSkills(File(userDir, "skills"), "", "user")
        scanMd(File(userDir, "commands"), commands, "", "user")
        scanMd(File(userDir, "agents"), agents, "", "user")

        val pluginDirs = LinkedHashMap<String, File>()
        runCatching {
            val o = JsonParser.parseString(File(userDir, "plugins/installed_plugins.json").readText()).asJsonObject
            o.getAsJsonObject("plugins")?.entrySet()?.forEach { (key, v) ->
                val path = v.asJsonArray.lastOrNull()?.asJsonObject?.get("installPath")?.asString ?: return@forEach
                pluginDirs[key.substringBefore('@')] = File(path)
            }
        }
        extraPluginPaths.forEach { p ->
            val d = File(p).takeIf { it.isDirectory } ?: return@forEach
            // ".../<plugin>/<version>" (cache) or ".../<plugin>" (synced)
            val name = if (d.name.firstOrNull()?.isDigit() == true) d.parentFile?.name ?: d.name else d.name
            if (pluginDirs.values.none { it.absolutePath == d.absolutePath }) pluginDirs.putIfAbsent(name, d)
        }
        // Plugin name = the cache folder name for ".../<plugin>/<version>" layouts.
        pluginDirs.forEach { (name, dir) ->
            val plugin = name.ifBlank { dir.name }
            scanSkills(File(dir, "skills"), "$plugin:", "plugin:$plugin")
            scanMd(File(dir, "commands"), commands, "$plugin:", "plugin:$plugin")
            scanMd(File(dir, "agents"), agents, "$plugin:", "plugin:$plugin")
        }

        return JsonObject().apply {
            add("skills", skills)
            add("commands", commands)
            add("agents", agents)
        }
    }

    /** `description:` from YAML front matter, else the first meaningful line. */
    fun describe(f: File): String {
        val lines = runCatching { f.useLines { it.take(60).toList() } }.getOrDefault(emptyList())
        lines.firstOrNull { it.trimStart().startsWith("description:") }
            ?.substringAfter("description:")?.trim()?.trim('"', '\'', '>', '|')?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it.take(220) }
        var inFront = lines.firstOrNull()?.trim() == "---"
        for ((i, l) in lines.withIndex()) {
            if (i == 0 && inFront) continue
            if (inFront) {
                if (l.trim() == "---") inFront = false
                continue
            }
            val t = l.trim().trimStart('#').trim()
            if (t.isNotBlank()) return t.take(220)
        }
        return ""
    }

    // ------------------------------------------------------------------ rules

    fun rules(projectDir: String?): JsonObject {
        val memory = JsonArray()

        fun mem(f: File, scope: String, missingOk: Boolean = false) {
            if (!f.isFile && !missingOk) return
            memory.add(JsonObject().apply {
                addProperty("path", f.absolutePath)
                addProperty("scope", scope)
                addProperty("exists", f.isFile)
                if (f.isFile) {
                    val text = runCatching { f.readText() }.getOrDefault("")
                    addProperty("lines", text.lines().size)
                    addProperty("preview", text.lines().filter { it.isNotBlank() }.take(8).joinToString("\n").take(800))
                }
            })
        }

        projectDir?.let { p ->
            val root = File(p)
            mem(File(root, "CLAUDE.md"), "project", missingOk = true)
            mem(File(root, ".claude/CLAUDE.md"), "project")
            mem(File(root, "CLAUDE.local.md"), "local")
            File(root, ".claude/rules").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }
                ?.forEach { mem(it, "project-rule") }
            // Parent directories' CLAUDE.md are loaded too.
            var parent = root.parentFile
            while (parent != null && parent.absolutePath.startsWith(home.absolutePath) && parent != home) {
                mem(File(parent, "CLAUDE.md"), "parent")
                parent = parent.parentFile
            }
            // Auto memory: ~/.claude/projects/<encoded path>/memory/MEMORY.md
            val encoded = root.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-")
            mem(File(userDir, "projects/$encoded/memory/MEMORY.md"), "auto-memory")
        }
        mem(File(userDir, "CLAUDE.md"), "user")
        File(userDir, "rules").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }
            ?.forEach { mem(it, "user-rule") }

        val settings = JsonArray()
        val files = buildList {
            add(File(userDir, "settings.json") to "user")
            projectDir?.let {
                add(File(it, ".claude/settings.json") to "project")
                add(File(it, ".claude/settings.local.json") to "local")
            }
        }
        for ((f, scope) in files) {
            if (!f.isFile) continue
            val o = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull() ?: continue
            settings.add(JsonObject().apply {
                addProperty("path", f.absolutePath)
                addProperty("scope", scope)
                o.getAsJsonObject("permissions")?.let { perms ->
                    listOf("allow", "ask", "deny", "additionalDirectories").forEach { k ->
                        perms.getAsJsonArray(k)?.let { add(k, it) }
                    }
                    perms.get("defaultMode")?.let { addProperty("defaultMode", it.asString) }
                }
                o.getAsJsonObject("hooks")?.let { hooks ->
                    add("hooks", JsonArray().apply {
                        hooks.entrySet().forEach { (event, v) ->
                            val cmds = JsonArray()
                            runCatching {
                                v.asJsonArray.forEach { m ->
                                    m.asJsonObject.getAsJsonArray("hooks")?.forEach { h ->
                                        h.asJsonObject.get("command")?.asString?.let { cmds.add(it.take(200)) }
                                    }
                                }
                            }
                            add(JsonObject().apply {
                                addProperty("event", event)
                                add("commands", cmds)
                            })
                        }
                    })
                }
                o.getAsJsonObject("enabledPlugins")?.let { ep ->
                    add("plugins", JsonArray().apply { ep.entrySet().filter { it.value.asBoolean }.forEach { add(it.key) } })
                }
            })
        }

        return JsonObject().apply {
            add("memory", memory)
            add("settings", settings)
        }
    }
}
