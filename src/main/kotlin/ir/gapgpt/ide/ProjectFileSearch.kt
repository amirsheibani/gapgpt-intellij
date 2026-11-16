package ir.gapgpt.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore

/** Fuzzy project file search for `@` mentions in the chat input. */
@Service(Service.Level.PROJECT)
class ProjectFileSearch(private val project: Project) {

    @Volatile
    private var cache: List<String> = emptyList()

    @Volatile
    private var cachedAt = 0L

    fun search(query: String, limit: Int = 15): List<String> {
        val files = files()
        val q = query.lowercase().replace('\\', '/')
        if (q.isEmpty()) return files.take(limit)
        return files.asSequence()
            .mapNotNull { path -> score(path, q)?.let { path to it } }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Lower is better; null = no match. */
    private fun score(path: String, q: String): Int? {
        val p = path.lowercase()
        val name = p.substringAfterLast('/')
        return when {
            name.startsWith(q) -> 0
            name.contains(q) -> 1
            p.contains(q) -> 2
            subsequence(name, q) -> 3
            subsequence(p, q) -> 4
            else -> null
        }
    }

    private fun subsequence(s: String, q: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }

    private fun files(): List<String> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 30_000 && cache.isNotEmpty()) return cache
        val base = project.guessProjectDir() ?: return emptyList()
        val out = ArrayList<String>()
        ReadAction.run<RuntimeException> {
            ProjectFileIndex.getInstance(project).iterateContent { f ->
                if (!f.isDirectory) VfsUtilCore.getRelativePath(f, base)?.let { out += it }
                out.size < 50_000
            }
        }
        out.sort()
        cache = out
        cachedAt = now
        return out
    }

    companion object {
        fun getInstance(project: Project): ProjectFileSearch = project.getService(ProjectFileSearch::class.java)
    }
}
