package com.antivocale.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.manager.LlmManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TASK-992 arm 1: on-device Q&A over the History DB for Gemma users.
 * The user asks a question; the app retrieves the most relevant transcripts
 * (the existing History search as the retrieval layer), builds a prompt with
 * them, and Gemma answers grounded in the user's own data. Fully on-device.
 *
 * GATED: the feature exists ONLY when the Gemma model is configured
 * (preferencesManager.modelPath non-blank; the same gate the summary and
 * punctuation passes use). Without Gemma the UI never surfaces the Ask
 * affordance: it is not assumed for every user.
 */
@HiltViewModel
class AskTranscriptsViewModel @Inject constructor(
    private val logDao: LogDao,
    private val preferencesManager: PreferencesManager,
    private val llmManager: LlmManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** True when the Gemma model is configured (the feature's gate). */
    val gemmaAvailable: StateFlow<Boolean> = preferencesManager.modelPath
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    sealed class AskState {
        data object Idle : AskState()
        data object Searching : AskState()
        data object Generating : AskState()
        /** [sourceCount] = 0 means "no matching transcripts found". */
        data class Answer(val text: String, val sourceCount: Int) : AskState()
        data class Error(val message: String) : AskState()
    }

    /** The in-flight ask (cancelled by [reset]: a dismissed dialog must
     *  not resurrect when the abandoned generation completes). */
    private var askJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    /** The last question, kept for the UI (re-ask, share the answer). */
    private val _lastQuestion = MutableStateFlow("")
    val lastQuestion: StateFlow<String> = _lastQuestion.asStateFlow()

    fun ask(question: String) {
        val q = question.trim()
        if (q.isBlank()) return
        if (_state.value is AskState.Searching || _state.value is AskState.Generating) return
        _lastQuestion.value = q
        _state.value = AskState.Searching

        askJob = viewModelScope.launch {
            try {
                // 1. RETRIEVAL: term-based (see retrieve); the History search
                //    is literal-substring, so the whole question would match
                //    almost nothing.
                val results = withContext(Dispatchers.IO) { retrieve(q) }

                if (results.isEmpty()) {
                    _state.value = AskState.Answer(text = "", sourceCount = 0)
                    return@launch
                }

                _state.value = AskState.Generating

                // 2. Build the grounded prompt (transcripts capped to Gemma's
                //    practical context, numbered, sender/date stamped).
                val prompt = buildPrompt(
                    appContext.getString(R.string.ask_transcripts_instruction), q, results)

                // 3. Ensure the LLM is loaded (the same Gemma the summary
                //    pass uses; the LlmManager handles the lifecycle).
                //    initialize is a blocking native call (10-30s cold):
                //    off Main, the ModelViewModel precedent.
                val modelPath = preferencesManager.modelPath.first()
                    ?: throw IllegalStateException("no Gemma model configured")
                val init = withContext(Dispatchers.IO) {
                    llmManager.initialize(appContext, modelPath)
                }
                if (init.isFailure) {
                    _state.value = AskState.Error(
                        "Gemma failed to load: ${init.exceptionOrNull()?.message ?: "unknown"}")
                    return@launch
                }

                // 4. GENERATION.
                val answer = llmManager.generateText(prompt)
                _state.value = answer.fold(
                    onSuccess = { text ->
                        AskState.Answer(text = text.trim(), sourceCount = results.size)
                    },
                    onFailure = { e ->
                        AskState.Error(e.message ?: "generation failed")
                    },
                )
            } catch (e: Exception) {
                _state.value = AskState.Error(e.message ?: "unknown error")
            }
        }
    }

    fun reset() {
        askJob?.cancel()
        askJob = null
        _state.value = AskState.Idle
    }

    /** TERM-BASED retrieval: the History search is literal-substring
     *  (searchAll does result LIKE '%q%'), so feeding it the whole
     *  natural-language question matches almost nothing. Split the
     *  question into terms, rank rows by how many distinct terms they
     *  contain (common words inflate every row equally, so the
     *  discriminative terms still separate), newest first. Falls back
     *  to the whole phrase when no term hits (verbatim quotes). Terms
     *  keep their typed case: SQLite's LIKE folds case for ASCII only,
     *  so lowercasing would silently miss capitalized non-ASCII content
     *  (Cyrillic names, accented words) that the user typed exactly as
     *  it appears in the transcript. */
    private suspend fun retrieve(question: String): List<LogEntity> {
        val terms = question
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_QUERY_TERMS)
        // ONE table scan: the OR-of-LIKEs union of all terms; per-term hit
        // counts (the ranking signal) are computed in memory over the union
        // rows, so one Ask tap costs one scan plus an in-memory pass, not
        // one full scan per term.
        val union = logDao.searchAnyRaw(logDao.searchAnySql(terms))
        val ranked = union
            .map { row -> row to terms.count { row.result.contains(it, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<LogEntity, Int>> { it.second }
                .thenByDescending { it.first.timestamp })
            .map { it.first }
        if (ranked.isNotEmpty()) return ranked.take(MAX_SOURCES)
        return logDao.searchAll(question).first()
            .sortedByDescending { it.timestamp }
            .take(MAX_SOURCES)
    }

    companion object {
        /** How many transcripts to include in the prompt. */
        const val MAX_SOURCES = 5

        /** Cap on extracted query terms (each is one DAO scan). */
        const val MAX_QUERY_TERMS = 8

        /** Builds the grounded prompt: context transcripts + question.
         *  Pure and testable (no ViewModel instance needed). The grounding
         *  instruction arrives LOCALIZED from the caller (the string
         *  resource every other Gemma prompt uses). If this ever gains a
         *  second consumer, lift it into a transcription/ object taking
         *  deconstructed strings (the SummaryPolicy pattern). */
        fun buildPrompt(instruction: String, question: String, sources: List<LogEntity>): String {
            val sb = StringBuilder()
            sb.append(instruction).append("\n\n")

            var budget = CONTEXT_BUDGET_CHARS
            var label = 0
            for (log in sources) {
                if (budget <= 0) break
                val candidate = log.result.take(budget)
                if (candidate.isBlank()) continue
                val header = "[Transcript ${label + 1}" +
                    (log.senderName?.let { ", $it" } ?: "") +
                    ", ${dateStamp(log.timestamp)}]\n"
                val available = budget - header.length - 2
                if (available <= 0) break
                label++
                val transcript =
                    if (candidate.length > available) candidate.take(available) else candidate
                sb.append(header).append(transcript).append("\n\n")
                budget -= header.length + transcript.length + 2
            }

            sb.append("Question: ").append(question)
            return sb.toString()
        }

        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

        private fun dateStamp(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE)

        /** The total transcript budget in chars (Gemma's practical context
         *  minus the prompt scaffolding and the answer space). Deliberately
         *  smaller than the single-transcript guard
         *  PunctuationPolicy.MAX_TRANSCRIPT_CHARS: here N sources share the
         *  budget with the scaffolding and the answer space. */
        const val CONTEXT_BUDGET_CHARS = 8_000
    }
}
