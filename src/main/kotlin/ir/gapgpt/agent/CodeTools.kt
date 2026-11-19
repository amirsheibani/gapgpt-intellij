package ir.gapgpt.agent

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import java.io.File
import java.util.concurrent.Callable

/**
 * Code navigation for the agent on top of the IDE's own index and PSI (any language the IDE understands):
 * the same questions an external code graph answers (where is X, who uses X, what is in this file, which files
 * depend on each other), but always up to date and without extra tools.
 *
 * Every query runs as a non-blocking read action in smart mode: it waits for indexing to finish and restarts
 * if a write action interrupts it. Call from a background thread only.
 */
class CodeTools(private val project: Project, private val root: File) {

    class Answer(val ok: Boolean, val text: String, val summary: String)

    private fun <T> read(ctx: AgentTools.RunContext, body: () -> T): T =
        ReadAction.nonBlocking(Callable { body() })
            .inSmartMode(project)
            .expireWhen { ctx.cancelled || project.isDisposed }
            .executeSynchronously()

    private fun vfile(f: File): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)

    private fun rel(vf: VirtualFile): String {
        val p = vf.path
        val r = root.path.replace(File.separatorChar, '/')
        return if (p.startsWith("$r/")) p.substring(r.length + 1) else p
    }

    private fun inProject(vf: VirtualFile?) = vf != null && ProjectFileIndex.getInstance(project).isInContent(vf)

    /** 1-based line of [e] in its file, or 0. */
    private fun lineOf(e: PsiElement): Int {
        val file = e.containingFile ?: return 0
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return 0
        val off = e.textOffset.coerceIn(0, doc.textLength)
        return doc.getLineNumber(off) + 1
    }

    private fun lineText(file: PsiFile, line: Int): String {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return ""
        if (line < 1 || line > doc.lineCount) return ""
        return doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line - 1), doc.getLineEndOffset(line - 1))).trim().take(MAX_LINE)
    }

    private fun where(e: PsiElement): String? {
        val vf = e.containingFile?.virtualFile ?: return null
        return "${rel(vf)}:${lineOf(e)}"
    }

    // ---------------------------------------------------------------- find_symbol

    /** Declarations (classes, functions, fields …) whose name matches [query]: exact first, then containing it. */
    fun findSymbol(query: String, ctx: AgentTools.RunContext): Answer {
        val q = query.trim().substringAfterLast('.')
        if (q.isEmpty()) return Answer(false, "Error: empty query.", "Empty query")
        val lines = read(ctx) {
            val scope = GlobalSearchScope.projectScope(project)
            val params = FindSymbolParameters.wrap(q, scope)
            val out = LinkedHashMap<String, String>()
            for (ep in listOf(CLASS_EP, SYMBOL_EP)) {
                for (c in ep.extensionList) {
                    ProgressManager.checkCanceled()
                    val names = HashSet<String>()
                    try {
                        if (c is ChooseByNameContributorEx) {
                            c.processNames(Processor { n -> if (n.contains(q, ignoreCase = true)) names += n; true }, scope, null)
                        } else {
                            @Suppress("DEPRECATION")
                            c.getNames(project, false).filterTo(names) { it.contains(q, ignoreCase = true) }
                        }
                    } catch (e: Exception) {
                        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                        continue
                    }
                    // Exact names first, then the shortest (closest) partial matches.
                    val ordered = names.sortedWith(compareBy({ !it.equals(q, ignoreCase = true) }, { it.length }, { it }))
                    for (n in ordered.take(MAX_NAMES)) {
                        val items = ArrayList<NavigationItem>()
                        try {
                            if (c is ChooseByNameContributorEx) {
                                c.processElementsWithName(n, Processor { items += it; items.size < MAX_SYMBOLS }, params)
                            } else {
                                @Suppress("DEPRECATION")
                                items += c.getItemsByName(n, q, project, false)
                            }
                        } catch (e: Exception) {
                            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                            continue
                        }
                        for (it in items) {
                            val el = it as? PsiElement ?: continue
                            val vf = el.containingFile?.virtualFile
                            if (!inProject(vf)) continue
                            val loc = where(el) ?: continue
                            val p = it.presentation
                            val kind = el.javaClass.simpleName.removePrefix("Kt").removePrefix("Psi").removeSuffix("Impl")
                            val container = p?.locationString?.takeIf { s -> s.isNotBlank() }?.let { s -> "  ($s)" }.orEmpty()
                            out.putIfAbsent(loc, "$loc  $kind ${p?.presentableText ?: n}$container")
                            if (out.size >= MAX_SYMBOLS) break
                        }
                        if (out.size >= MAX_SYMBOLS) break
                    }
                }
            }
            out.values.toList()
        }
        if (lines.isEmpty()) return Answer(true, "No declarations named like \"$q\" in the project. Try grep for text matches.", "No symbols found")
        return Answer(true, lines.joinToString("\n") + if (lines.size >= MAX_SYMBOLS) "\n… (more; use a more specific name)" else "",
            "Found ${lines.size} symbols")
    }

    // ---------------------------------------------------------------- file_outline

    /** Classes / functions / fields of a file with line numbers, from the IDE's structure view. */
    fun outline(file: File, ctx: AgentTools.RunContext): Answer {
        val vf = vfile(file) ?: return Answer(false, "Error: file not found.", "File not found")
        val lines = read(ctx) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@read null
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi) as? TreeBasedStructureViewBuilder
                ?: return@read null
            val model = builder.createStructureViewModel(null)
            try {
                val out = ArrayList<String>()
                fun walk(el: StructureViewTreeElement, depth: Int) {
                    if (out.size >= MAX_OUTLINE) return
                    ProgressManager.checkCanceled()
                    val text = el.presentation.presentableText
                    val value = el.value as? PsiElement
                    if (depth >= 0 && !text.isNullOrBlank()) {
                        val line = value?.let { lineOf(it) } ?: 0
                        out += "  ".repeat(depth) + text + (if (line > 0) "  :$line" else "")
                    }
                    if (depth < MAX_DEPTH) el.children.forEach { (it as? StructureViewTreeElement)?.let { c -> walk(c, depth + 1) } }
                }
                walk(model.root, -1)
                out
            } finally {
                Disposer.dispose(model)
            }
        } ?: return Answer(false, "Error: the IDE has no structure view for this file type. Use read_file or grep.", "No outline")
        if (lines.isEmpty()) return Answer(true, "(no declarations)", "No declarations")
        return Answer(true, lines.joinToString("\n") + if (lines.size >= MAX_OUTLINE) "\n… (truncated)" else "", "${lines.size} entries")
    }

    // ---------------------------------------------------------------- find_references / go_to_definition

    /**
     * The declaration [symbol] refers to on [line] of [file]: the declaration itself when the name is declared
     * there, otherwise what the reference resolves to.
     */
    private fun target(psi: PsiFile, line: Int, symbol: String): PsiElement? {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        val lines = if (line in 1..doc.lineCount) listOf(line) + ((line - 3)..(line + 3)).filter { it != line && it in 1..doc.lineCount }
        else (1..doc.lineCount).toList()
        val word = Regex("(?<![\\w$])" + Regex.escape(symbol) + "(?![\\w$])")
        for (l in lines) {
            val start = doc.getLineStartOffset(l - 1)
            val text = doc.getText(com.intellij.openapi.util.TextRange(start, doc.getLineEndOffset(l - 1)))
            for (m in word.findAll(text)) {
                val off = start + m.range.first
                var e: PsiElement? = psi.findElementAt(off)
                // Declared here?
                while (e != null && e !is PsiFile) {
                    if (e is PsiNameIdentifierOwner && e.nameIdentifier?.textRange?.contains(off) == true) return e
                    if (e is PsiNamedElement && e.name == symbol && e.textOffset == off) return e
                    e = e.parent
                }
                // A reference to something else?
                psi.findReferenceAt(off)?.resolve()?.let { return it.navigationElement ?: it }
            }
        }
        return null
    }

    fun findReferences(file: File, line: Int, symbol: String, ctx: AgentTools.RunContext): Answer {
        val vf = vfile(file) ?: return Answer(false, "Error: file not found.", "File not found")
        val result = read(ctx) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@read null
            val t = target(psi, line, symbol) ?: return@read null
            val out = LinkedHashMap<String, String>()
            var more = false
            ReferencesSearch.search(t, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                val el = ref.element
                val f = el.containingFile ?: return@Processor true
                val vfr = f.virtualFile ?: return@Processor true
                val ln = lineOf(el)
                val key = "${rel(vfr)}:$ln"
                if (out.size >= MAX_REFS) { more = true; return@Processor false }
                out.putIfAbsent(key, "$key: ${lineText(f, ln)}")
                true
            })
            Triple(where(t) ?: "?", out.values.toList(), more)
        } ?: return Answer(false, "Error: no declaration or reference named \"$symbol\" near line $line. " +
            "Pass the line where the name appears, or use find_symbol.", "Symbol not found")
        val (decl, refs, more) = result
        if (refs.isEmpty()) return Answer(true, "Declared at $decl. No references in the project.", "No references")
        return Answer(true, "Declared at $decl. ${refs.size}${if (more) "+" else ""} references:\n" + refs.joinToString("\n") +
            if (more) "\n… (more)" else "", "${refs.size}${if (more) "+" else ""} references")
    }

    fun goToDefinition(file: File, line: Int, symbol: String, ctx: AgentTools.RunContext): Answer {
        val vf = vfile(file) ?: return Answer(false, "Error: file not found.", "File not found")
        val text = read(ctx) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@read null
            val t = target(psi, line, symbol) ?: return@read null
            val f = t.containingFile ?: return@read null
            val tvf = f.virtualFile
            val loc = if (tvf != null) "${rel(tvf)}:${lineOf(t)}" else f.name
            val inside = if (inProject(tvf)) "" else " (library / SDK, outside the project)"
            val ln = lineOf(t)
            "$loc$inside: ${lineText(f, ln)}"
        } ?: return Answer(false, "Error: could not resolve \"$symbol\" near line $line.", "Not resolved")
        return Answer(true, text, text.substringBefore(": ").substringBefore(" ("))
    }

    // ---------------------------------------------------------------- file_relations

    /** Project files this file uses (outgoing references) and files that use its declarations (incoming). */
    fun relations(file: File, ctx: AgentTools.RunContext): Answer {
        val vf = vfile(file) ?: return Answer(false, "Error: file not found.", "File not found")
        val result = read(ctx) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@read null
            val uses = LinkedHashMap<String, Int>()
            var seen = 0
            psi.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (seen >= MAX_REF_SCAN) return stopWalking()
                    for (ref in element.references) {
                        seen++
                        ProgressManager.checkCanceled()
                        val target = try { ref.resolve() } catch (e: Exception) {
                            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e else null
                        } ?: continue
                        val tvf = target.containingFile?.virtualFile ?: continue
                        if (tvf == vf || !inProject(tvf)) continue
                        uses.merge(rel(tvf), 1, Int::plus)
                    }
                    super.visitElement(element)
                }
            })
            // Incoming: references to the file's top-level declarations.
            val usedBy = LinkedHashMap<String, Int>()
            // Top-level declarations and their direct members; locals and parameters are skipped (local use scope).
            val decls = ArrayList<PsiElement>()
            fun collect(e: PsiElement, depth: Int) {
                var c = e.firstChild
                while (c != null && decls.size < MAX_DECLS) {
                    if (c is PsiNameIdentifierOwner && c.name != null) {
                        if (c.useScope is GlobalSearchScope) decls += c
                        if (depth == 0) collect(c, 1)
                    } else {
                        collect(c, depth)
                    }
                    c = c.nextSibling
                }
            }
            collect(psi, 0)
            val scope = GlobalSearchScope.projectScope(project)
            for (d in decls) {
                ReferencesSearch.search(d, scope).forEach(Processor { ref ->
                    val rvf = ref.element.containingFile?.virtualFile ?: return@Processor true
                    if (rvf != vf) usedBy.merge(rel(rvf), 1, Int::plus)
                    usedBy.size < MAX_FILES
                })
            }
            uses to usedBy
        } ?: return Answer(false, "Error: the IDE cannot parse this file.", "Not parsed")
        val (uses, usedBy) = result
        fun fmt(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(MAX_FILES)
            .joinToString("\n") { "  ${it.key} (${it.value})" }.ifEmpty { "  (none)" }
        return Answer(true, "Uses (project files it references, with reference counts):\n${fmt(uses)}\n" +
            "Used by (files referencing its declarations):\n${fmt(usedBy)}",
            "Uses ${uses.size} files, used by ${usedBy.size}")
    }

    companion object {
        private val CLASS_EP = ExtensionPointName<ChooseByNameContributor>("com.intellij.gotoClassContributor")
        private val SYMBOL_EP = ExtensionPointName<ChooseByNameContributor>("com.intellij.gotoSymbolContributor")
        private const val MAX_NAMES = 30
        private const val MAX_SYMBOLS = 60
        private const val MAX_OUTLINE = 400
        private const val MAX_DEPTH = 3
        private const val MAX_REFS = 200
        private const val MAX_REF_SCAN = 20_000
        private const val MAX_DECLS = 200
        private const val MAX_FILES = 60
        private const val MAX_LINE = 200
    }
}
