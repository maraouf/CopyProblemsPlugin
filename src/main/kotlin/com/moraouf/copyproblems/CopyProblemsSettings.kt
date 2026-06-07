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

    /**
     * Which files the action collects diagnostics from. ACTIVE_FILE preserves the original
     * single-file behavior (and is the only scope the "reformat before copy" option applies to);
     * the others gather multiple files and emit a per-file grouped list. VCS_CHANGED reads the git
     * working-tree changes via ChangeListManager and is a no-op when the project is not under VCS —
     * it deliberately does NOT fall back to scanning the whole project. CURRENT_DIRECTORY walks the
     * active file's directory recursively (project content files only).
     */
    enum class CopyScope {
        ACTIVE_FILE,
        OPEN_EDITORS,
        VCS_CHANGED,
        CURRENT_DIRECTORY,
    }

    /**
     * How the collected problems are serialized to the clipboard. PLAIN is the original
     * one-line-per-problem format (`path:line:col`, an optional severity tag, then the description)
     * and honors the includeColumn / includeSeverityTag flags. MARKDOWN_TABLE and JSON are
     * structured: they always carry the path and severity (the includeSeverityTag flag is plain-only)
     * and honor includeColumn for the column field.
     */
    enum class OutputFormat {
        PLAIN,
        MARKDOWN_TABLE,
        JSON,
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

        // For files that live outside the project root (library sources, scratch files) there is no
        // project-relative path. When true, emit the full absolute path; when false, just the file name.
        var fullPathForExternalFiles: Boolean = false,

        // Behavior options.
        // When true, reformat the file (the IDE "Reformat Code" action) before collecting
        // diagnostics, so whitespace/formatting warnings are resolved and excluded from the copy.
        var reformatBeforeCopy: Boolean = false,

        // How to surface the "copied N problems" / error message after the action runs.
        // MODAL is the default for backwards compatibility with 1.0.1+ (PyCharm 2025.x balloon issue).
        var notificationStyle: NotificationStyle = NotificationStyle.MODAL,

        // Which files the action collects from. Defaults to ACTIVE_FILE so existing behavior and
        // keymap bindings are unchanged for users who never touch the setting.
        var copyScope: CopyScope = CopyScope.ACTIVE_FILE,

        // How the result is serialized to the clipboard. Defaults to PLAIN (the original line format).
        var outputFormat: OutputFormat = OutputFormat.PLAIN,

        // When true, each problem is accompanied by its offending source line (integrated into the
        // active output format). Applies to every copy, but is most useful with the AI-prompt action.
        var includeCodeContext: Boolean = false,

        // Instruction text the "Copy as AI Prompt" action prepends before the (format-respecting)
        // problem list. Editable so users can tailor it to their assistant.
        var aiPromptHeader: String = "Fix the following diagnostics in my code:",
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
