package ir.gapgpt

import com.intellij.openapi.diagnostic.Logger
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * In-memory ring buffer of plugin log lines, shown in the chat's Log tab.
 * Also forwards to the IDE log (idea.log).
 */
object PluginLog {

    data class Line(val time: String, val level: String, val source: String, val message: String)

    private const val MAX = 2000
    private val lines = ArrayDeque<Line>()
    private val listeners = mutableListOf<(Line) -> Unit>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val ideLog = Logger.getInstance("GapGPT")

    @Volatile
    var debugEnabled = false

    fun info(source: String, message: String) = add("INFO", source, message)
    fun warn(source: String, message: String) = add("WARN", source, message)
    fun debug(source: String, message: String) {
        if (debugEnabled) add("DEBUG", source, message)
    }

    private fun add(level: String, source: String, message: String) {
        val line = Line(LocalTime.now().format(fmt), level, source, message)
        val ls = synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            listeners.toList()
        }
        when (level) {
            "WARN" -> ideLog.warn("[$source] $message")
            "INFO" -> ideLog.info("[$source] $message")
            else -> ideLog.debug("[$source] $message")
        }
        ls.forEach { runCatching { it(line) } }
    }

    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    fun addListener(l: (Line) -> Unit) = synchronized(lines) { listeners += l }
    fun removeListener(l: (Line) -> Unit) = synchronized(lines) { listeners -= l }
}
