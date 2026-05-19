package com.moraouf.copyproblems

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "CopyProblemsSettings",
    storages = [Storage("copyProblems.xml")],
)
class CopyProblemsSettings : PersistentStateComponent<CopyProblemsSettings.State> {

    enum class NotificationStyle {
        MODAL,
        BALLOON,
        EDITOR_HINT,
        SILENT,
    }

    data class State(
        // Severity filters — checkboxes in the settings panel.
        var includeError: Boolean = true,
        var includeWarning: Boolean = true,
        var includeWeakWarning: Boolean = true,
        var includeInformation: Boolean = true,
        var includeGrammarError: Boolean = false,
        var includeTypo: Boolean = true,
        var includeServerProblem: Boolean = true,
        var includeStyleSuggestion: Boolean = false,
        var includeUnknownSeverities: Boolean = true,

        // Output format options.
        var includeColumn: Boolean = true,
        var includeSeverityTag: Boolean = true,
        var sortBySeverityFirst: Boolean = false,

        // How to surface the "copied N problems" / error message after the action runs.
        // MODAL is the default for backwards compatibility with 1.0.1+ (PyCharm 2025.x balloon issue).
        var notificationStyle: NotificationStyle = NotificationStyle.MODAL,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /** True if a HighlightInfo with this severity name should be included. */
    fun isSeverityEnabled(severityName: String): Boolean = when (severityName) {
        "ERROR" -> myState.includeError
        "WARNING" -> myState.includeWarning
        "WEAK WARNING", "WEAK_WARNING" -> myState.includeWeakWarning
        "INFORMATION", "INFO" -> myState.includeInformation
        "GRAMMAR_ERROR", "GRAMMAR ERROR" -> myState.includeGrammarError
        "TYPO" -> myState.includeTypo
        "SERVER PROBLEM", "SERVER_PROBLEM" -> myState.includeServerProblem
        "STYLE_SUGGESTION", "STYLE SUGGESTION" -> myState.includeStyleSuggestion
        else -> myState.includeUnknownSeverities
    }

    companion object {
        fun getInstance(): CopyProblemsSettings =
            ApplicationManager.getApplication().getService(CopyProblemsSettings::class.java)
    }
}
