package ir.gapgpt.context

import com.intellij.openapi.vfs.LocalFileSystem
import ir.gapgpt.PluginLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One thing that can be imported into `.gapgpt/`. */
data class InstructionItem(
    val kind: String, // "rule" | "skill" | "command" | "agent" | "memory"
    val name: String,
    val source: File,
    val target: File,
)

/**
 * Copies rules / skills / memory files of other AI tools into the neutral `.gapgpt/` folder.
 * The originals are never moved, changed or deleted, so the other tools keep working side by side.
 * This is the only file that knows the names of those foreign tools' files.
 */
object InstructionsImporter {

    // ---- foreign-tool file names (kept together on purpose) ----
    private const val FOREIGN_DIR = ".claude"
    private const val FOREIGN_RULES = "$FOREIGN_DIR/rules"
    private const val FOREIGN_SKILLS = "$FOREIGN_DIR/skills"
    private const val FOREIGN_COMMANDS = "$FOREIGN_DIR/commands"
    private const val FOREIGN_AGENTS = "$FOREIGN_DIR/agents"
    private const val FOREIGN_MEMORY = "CLAUDE.md"
    private const val FOREIGN_DIR_MEMORY = "$FOREIGN_DIR/CLAUDE.md"
    private const val FOREIGN_LOCAL_MEMORY = "CLAUDE.local.md"
    private const val GENERIC_MEMORY = "AGENTS.md"
    private const val CURSOR_DIR = ".cursor"
    private const val CURSOR_RULES_DIR = "$CURSOR_DIR/rules"
    private const val CURSOR_RULES_FILE = ".cursorrules"
    private const val WINDSURF_RULES_FILE = ".windsurfrules"
    private const val COPILOT_FILE = ".github/copilot-instructions.md"

    // ---- neutral targets ----
    private const val OWN = ProjectInstructions.DIR

    data class ImportResult(val copied: List<InstructionItem>, val skipped: List<Pair<InstructionItem, String>>)

    fun scan(projectDir: String): List<InstructionItem> {
        val root = File(projectDir)
        val canonRoot = canonical(root) ?: return emptyList()
        val own = File(root, OWN)

        val memory = mutableListOf<InstructionItem>()
        val rules = mutableListOf<InstructionItem>()
        val skills = mutableListOf<InstructionItem>()
        val commands = mutableListOf<InstructionItem>()
        val agents = mutableListOf<InstructionItem>()

        fun inside(f: File): Boolean = runCatching {
            !Files.isSymbolicLink(f.toPath()) && isUnder(canonRoot, f.canonicalFile)
        }.getOrDefault(false)

        // memory: fixed targets, so a file that was already copied is not offered again
        listOf(
            GENERIC_MEMORY to "rules/project-memory.md",
            FOREIGN_MEMORY to "rules/project-memory-2.md",
            FOREIGN_DIR_MEMORY to "rules/project-memory-3.md",
        ).forEach { (rel, target) ->
            val f = File(root, rel)
            if (f.isFile && inside(f)) memory += InstructionItem("memory", f.name, f, File(own, target))
        }
        File(root, FOREIGN_LOCAL_MEMORY).takeIf { it.isFile && inside(it) }?.let {
            memory += InstructionItem("memory", it.name, it, File(own, "rules/local-memory.md"))
        }

        // tree helpers
        fun mdFiles(dir: File, exts: Set<String>): List<File> = runCatching {
            if (!dir.isDirectory || !inside(dir)) emptyList()
            else dir.walkTopDown()
                .onEnter { inside(it) }
                .filter { it.isFile && it.extension.lowercase() in exts && inside(it) }
                .sortedBy { it.path }
                .toList()
        }.getOrDefault(emptyList())

        fun rel(base: File, f: File) = f.relativeTo(base).invariantSeparatorsPath

        // rules
        val cRules = File(root, FOREIGN_RULES)
        mdFiles(cRules, setOf("md")).forEach {
            val r = rel(cRules, it)
            rules += InstructionItem("rule", r.removeSuffix(".md"), it, File(own, "rules/$r"))
        }
        val cursor = File(root, CURSOR_RULES_DIR)
        mdFiles(cursor, setOf("md", "mdc")).forEach {
            val r = rel(cursor, it).replace(Regex("\\.mdc$", RegexOption.IGNORE_CASE), ".md")
            rules += InstructionItem("rule", it.nameWithoutExtension, it, File(own, "rules/cursor/$r"))
        }
        listOf(
            CURSOR_RULES_FILE to "cursor-rules.md",
            WINDSURF_RULES_FILE to "windsurf-rules.md",
            COPILOT_FILE to "copilot-instructions.md",
        ).forEach { (src, tgt) ->
            val f = File(root, src)
            if (f.isFile && inside(f)) rules += InstructionItem("rule", tgt.removeSuffix(".md"), f, File(own, "rules/$tgt"))
        }

        // skills (whole folders)
        val cSkills = File(root, FOREIGN_SKILLS)
        runCatching {
            if (cSkills.isDirectory && inside(cSkills)) {
                cSkills.listFiles()?.filter { it.isDirectory && inside(it) }?.sortedBy { it.name }?.forEach {
                    skills += InstructionItem("skill", it.name, it, File(own, "skills/${it.name}"))
                }
            }
        }

        // commands / agents
        val cCmds = File(root, FOREIGN_COMMANDS)
        mdFiles(cCmds, setOf("md")).forEach {
            val r = rel(cCmds, it)
            commands += InstructionItem("command", r.removeSuffix(".md"), it, File(own, "commands/$r"))
        }
        val cAgents = File(root, FOREIGN_AGENTS)
        mdFiles(cAgents, setOf("md")).forEach {
            val r = rel(cAgents, it)
            agents += InstructionItem("agent", r.removeSuffix(".md"), it, File(own, "agents/$r"))
        }

        // Already copied (or the user has their own file there): nothing to offer.
        return (memory + rules + skills + commands + agents).filter { !it.target.exists() }
    }

    fun copy(projectDir: String, items: List<InstructionItem>): ImportResult {
        val copied = mutableListOf<InstructionItem>()
        val skipped = mutableListOf<Pair<InstructionItem, String>>()
        val root = File(projectDir)
        val canonRoot = canonical(root)
        if (canonRoot == null) {
            items.forEach { skipped += it to "project root not found" }
            return ImportResult(copied, skipped)
        }

        for (item in items) {
            try {
                val src = item.source
                val tgt = item.target
                if (!src.exists()) { skipped += item to "source missing"; continue }
                if (Files.isSymbolicLink(src.toPath())) { skipped += item to "source is a symlink"; continue }
                val cs = src.canonicalFile
                val ct = File(tgt.absoluteFile.toPath().normalize().toString())
                if (!isUnder(canonRoot, cs) || !isUnder(canonRoot, ct.parentFile?.let { canonicalOrSelf(it) } ?: ct)) {
                    skipped += item to "path outside project"; continue
                }
                if (tgt.exists()) { skipped += item to "target exists"; continue }
                tgt.parentFile?.mkdirs()

                // Copy only: the source stays where it is for the tool that owns it.
                if (src.isDirectory) copyTree(src, tgt) else Files.copy(src.toPath(), tgt.toPath(), StandardCopyOption.COPY_ATTRIBUTES)

                runCatching { rewriteTree(tgt, stripCursorFrontMatter = src.extension.equals("mdc", true)) }
                copied += item
                PluginLog.info("import", "copied ${item.kind} ${src.path} -> ${tgt.path}")
            } catch (e: Throwable) {
                skipped += item to (e.message ?: e.javaClass.simpleName)
                PluginLog.warn("import", "failed ${item.source.path}: ${e.message}")
            }
        }

        runCatching { LocalFileSystem.getInstance().refreshIoFiles(listOf(File(root, OWN)), true, true, null) }
        PluginLog.info("import", "done: copied=${copied.size} skipped=${skipped.size}")
        return ImportResult(copied, skipped)
    }

    // ---- helpers ----

    private fun canonical(f: File): File? = runCatching { f.canonicalFile }.getOrNull()
    private fun canonicalOrSelf(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f)

    private fun isUnder(root: File, f: File): Boolean {
        val r = root.toPath()
        val p = f.toPath()
        return p.startsWith(r)
    }



    private fun copyTree(src: File, tgt: File) {
        src.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            if (Files.isSymbolicLink(f.toPath())) return@forEach
            val dest = File(tgt, f.relativeTo(src).path)
            if (f.isDirectory) dest.mkdirs()
            else {
                dest.parentFile?.mkdirs()
                Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun rewriteTree(target: File, stripCursorFrontMatter: Boolean) {
        if (target.isDirectory) {
            target.walkTopDown().filter { it.isFile && it.extension.lowercase() == "md" && !Files.isSymbolicLink(it.toPath()) }
                .forEach { rewriteFile(it, false) }
        } else if (target.extension.lowercase() == "md") {
            rewriteFile(target, stripCursorFrontMatter)
        }
    }

    private fun rewriteFile(f: File, stripCursorFrontMatter: Boolean) {
        val bytes = f.readBytes()
        if (bytes.any { it == 0.toByte() }) return // binary
        val original = String(bytes, Charsets.UTF_8)
        var text = original
            .replace("$FOREIGN_DIR/", "$OWN/")
            .replace(FOREIGN_LOCAL_MEMORY, "local-memory.md")
            .replace(FOREIGN_MEMORY, GENERIC_MEMORY)
        if (stripCursorFrontMatter) {
            val m = Regex("^---\\r?\\n(.*?)\\r?\\n---[ \\t]*(\\r?\\n|$)", RegexOption.DOT_MATCHES_ALL).find(text)
            if (m != null && !m.groupValues[1].contains("description:")) {
                text = text.substring(m.range.last + 1).trimStart('\r', '\n')
            }
        }
        if (text != original) f.writeText(text, Charsets.UTF_8)
    }



}
