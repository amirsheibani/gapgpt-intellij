package ir.gapgpt.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.LocalDate

/** Token usage of the GapGPT engine per model, kept across IDE restarts (local estimate, not the bill). */
@Service(Service.Level.APP)
@State(name = "GapGptUsage", storages = [Storage("gapgpt-usage.xml")])
class GapGptUsage : PersistentStateComponent<GapGptUsage.State> {

    class State {
        /** model id -> "requests|input|cached|output|reasoning|costUsd|lastUsedEpochMs" */
        var models: MutableMap<String, String> = mutableMapOf()
        var since: String = LocalDate.now().toString()
    }

    data class Totals(
        val requests: Long = 0, val input: Long = 0, val cached: Long = 0, val output: Long = 0,
        val reasoning: Long = 0, val cost: Double = 0.0, val lastUsed: Long = 0,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, myState)

    @Synchronized
    fun add(model: String, input: Long, cached: Long, output: Long, reasoning: Long, cost: Double) {
        val t = get(model)
        myState.models[model] = encode(
            Totals(t.requests + 1, t.input + input, t.cached + cached, t.output + output,
                t.reasoning + reasoning, t.cost + cost, System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun get(model: String): Totals = myState.models[model]?.let(::decode) ?: Totals()

    @Synchronized
    fun all(): Map<String, Totals> = myState.models.mapValues { decode(it.value) }

    val since: String get() = myState.since

    @Synchronized
    fun reset() {
        myState.models = mutableMapOf()
        myState.since = LocalDate.now().toString()
    }

    private fun encode(t: Totals) = listOf(t.requests, t.input, t.cached, t.output, t.reasoning, t.cost, t.lastUsed).joinToString("|")

    private fun decode(s: String): Totals {
        val p = s.split('|')
        fun l(i: Int) = p.getOrNull(i)?.toLongOrNull() ?: 0
        return Totals(l(0), l(1), l(2), l(3), l(4), p.getOrNull(5)?.toDoubleOrNull() ?: 0.0, l(6))
    }

    companion object {
        fun get(): GapGptUsage = ApplicationManager.getApplication().getService(GapGptUsage::class.java)
    }
}
