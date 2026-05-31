package com.moraouf.copyproblems

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

class CopyProblemsConfigurable : Configurable {

    private val settings get() = CopyProblemsSettings.getInstance()

    private val cbError = JBCheckBox("ERROR")
    private val cbWarning = JBCheckBox("WARNING")
    private val cbWeakWarning = JBCheckBox("WEAK WARNING")
    private val cbInformation = JBCheckBox("INFORMATION")
    private val cbGrammarError = JBCheckBox("GRAMMAR_ERROR")
    private val cbTypo = JBCheckBox("TYPO")
    private val cbServerProblem = JBCheckBox("SERVER_PROBLEM")
    private val cbStyleSuggestion = JBCheckBox("STYLE_SUGGESTION")
    private val cbUnknownSeverities = JBCheckBox("Include other / unknown severities (catch-all)")

    private val cbIncludeColumn = JBCheckBox("Include column number (line:col vs. line only)")
    private val cbIncludeSeverityTag = JBCheckBox("Include [SEVERITY] tag in each line")
    private val cbSortBySeverityFirst = JBCheckBox("Sort by severity first (errors before warnings), then by line")
    private val cbFullPathForExternalFiles =
        JBCheckBox("For files outside the project, use the full absolute path (otherwise file name only)")

    private val cbReformatBeforeCopy =
        JBCheckBox("Reformat the file (Reformat Code) before copying, to clear whitespace/formatting warnings")

    private val rbModal = JBRadioButton("Modal popup with OK button")
    private val rbBalloon = JBRadioButton("Balloon notification (auto-dismisses in IDE corner)")
    private val rbEditorHint = JBRadioButton("Editor hint (small popup near the caret)")
    private val rbSilent = JBRadioButton("Silent (copy without any notification)")

    // Single source of truth for the parallel checkbox <-> State plumbing used by isModified / apply / reset.
    private val checkboxBindings: List<CheckboxBinding> = listOf(
        CheckboxBinding(cbError, { it.includeError }) { s, v -> s.includeError = v },
        CheckboxBinding(cbWarning, { it.includeWarning }) { s, v -> s.includeWarning = v },
        CheckboxBinding(cbWeakWarning, { it.includeWeakWarning }) { s, v -> s.includeWeakWarning = v },
        CheckboxBinding(cbInformation, { it.includeInformation }) { s, v -> s.includeInformation = v },
        CheckboxBinding(cbGrammarError, { it.includeGrammarError }) { s, v -> s.includeGrammarError = v },
        CheckboxBinding(cbTypo, { it.includeTypo }) { s, v -> s.includeTypo = v },
        CheckboxBinding(cbServerProblem, { it.includeServerProblem }) { s, v -> s.includeServerProblem = v },
        CheckboxBinding(cbStyleSuggestion, { it.includeStyleSuggestion }) { s, v -> s.includeStyleSuggestion = v },
        CheckboxBinding(
            cbUnknownSeverities,
            { it.includeUnknownSeverities },
        ) { s, v -> s.includeUnknownSeverities = v },
        CheckboxBinding(cbIncludeColumn, { it.includeColumn }) { s, v -> s.includeColumn = v },
        CheckboxBinding(cbIncludeSeverityTag, { it.includeSeverityTag }) { s, v -> s.includeSeverityTag = v },
        CheckboxBinding(cbSortBySeverityFirst, { it.sortBySeverityFirst }) { s, v -> s.sortBySeverityFirst = v },
        CheckboxBinding(
            cbFullPathForExternalFiles,
            { it.fullPathForExternalFiles },
        ) { s, v -> s.fullPathForExternalFiles = v },
        CheckboxBinding(cbReformatBeforeCopy, { it.reformatBeforeCopy }) { s, v -> s.reformatBeforeCopy = v },
    )

    init {
        val styleGroup = ButtonGroup()
        styleGroup.add(rbModal)
        styleGroup.add(rbBalloon)
        styleGroup.add(rbEditorHint)
        styleGroup.add(rbSilent)
    }

    override fun getDisplayName(): String = "Copy All Problems"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(10)

        panel.add(JBLabel("<html><b>Severity filters</b> &mdash; unchecked severities are skipped:</html>"))
        panel.add(Box.createVerticalStrut(8))
        panel.add(cbError)
        panel.add(cbWarning)
        panel.add(cbWeakWarning)
        panel.add(cbInformation)
        panel.add(cbGrammarError)
        panel.add(cbTypo)
        panel.add(cbServerProblem)
        panel.add(cbStyleSuggestion)
        panel.add(Box.createVerticalStrut(4))
        panel.add(cbUnknownSeverities)

        panel.add(Box.createVerticalStrut(16))
        panel.add(JBLabel("<html><b>Output format</b></html>"))
        panel.add(Box.createVerticalStrut(8))
        panel.add(cbIncludeColumn)
        panel.add(cbIncludeSeverityTag)
        panel.add(cbSortBySeverityFirst)
        panel.add(cbFullPathForExternalFiles)

        panel.add(Box.createVerticalStrut(16))
        panel.add(JBLabel("<html><b>Behavior</b></html>"))
        panel.add(Box.createVerticalStrut(8))
        panel.add(cbReformatBeforeCopy)

        panel.add(Box.createVerticalStrut(16))
        panel.add(JBLabel("<html><b>Notification style</b> &mdash; how the result is reported:</html>"))
        panel.add(Box.createVerticalStrut(8))
        panel.add(rbModal)
        panel.add(rbBalloon)
        panel.add(rbEditorHint)
        panel.add(rbSilent)

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return checkboxBindings.any { it.isModified(s) } || (selectedStyle() != s.notificationStyle)
    }

    override fun apply() {
        val s = settings.state
        checkboxBindings.forEach { it.save(s) }
        s.notificationStyle = selectedStyle()
    }

    override fun reset() {
        val s = settings.state
        checkboxBindings.forEach { it.load(s) }
        when (s.notificationStyle) {
            CopyProblemsSettings.NotificationStyle.MODAL -> rbModal.isSelected = true
            CopyProblemsSettings.NotificationStyle.BALLOON -> rbBalloon.isSelected = true
            CopyProblemsSettings.NotificationStyle.EDITOR_HINT -> rbEditorHint.isSelected = true
            CopyProblemsSettings.NotificationStyle.SILENT -> rbSilent.isSelected = true
        }
    }

    private fun selectedStyle(): CopyProblemsSettings.NotificationStyle = when {
        rbBalloon.isSelected -> CopyProblemsSettings.NotificationStyle.BALLOON
        rbEditorHint.isSelected -> CopyProblemsSettings.NotificationStyle.EDITOR_HINT
        rbSilent.isSelected -> CopyProblemsSettings.NotificationStyle.SILENT
        else -> CopyProblemsSettings.NotificationStyle.MODAL
    }

    private class CheckboxBinding(
        private val cb: JBCheckBox,
        private val get: (CopyProblemsSettings.State) -> Boolean,
        private val set: (CopyProblemsSettings.State, Boolean) -> Unit,
    ) {
        fun isModified(s: CopyProblemsSettings.State): Boolean = cb.isSelected != get(s)

        fun load(s: CopyProblemsSettings.State) {
            cb.isSelected = get(s)
        }

        fun save(s: CopyProblemsSettings.State) {
            set(s, cb.isSelected)
        }
    }
}
