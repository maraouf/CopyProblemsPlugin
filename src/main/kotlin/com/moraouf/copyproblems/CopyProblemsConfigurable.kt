package com.moraouf.copyproblems

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
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

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return cbError.isSelected != s.includeError
            || cbWarning.isSelected != s.includeWarning
            || cbWeakWarning.isSelected != s.includeWeakWarning
            || cbInformation.isSelected != s.includeInformation
            || cbGrammarError.isSelected != s.includeGrammarError
            || cbTypo.isSelected != s.includeTypo
            || cbServerProblem.isSelected != s.includeServerProblem
            || cbStyleSuggestion.isSelected != s.includeStyleSuggestion
            || cbUnknownSeverities.isSelected != s.includeUnknownSeverities
            || cbIncludeColumn.isSelected != s.includeColumn
            || cbIncludeSeverityTag.isSelected != s.includeSeverityTag
            || cbSortBySeverityFirst.isSelected != s.sortBySeverityFirst
    }

    override fun apply() {
        val s = settings.state
        s.includeError = cbError.isSelected
        s.includeWarning = cbWarning.isSelected
        s.includeWeakWarning = cbWeakWarning.isSelected
        s.includeInformation = cbInformation.isSelected
        s.includeGrammarError = cbGrammarError.isSelected
        s.includeTypo = cbTypo.isSelected
        s.includeServerProblem = cbServerProblem.isSelected
        s.includeStyleSuggestion = cbStyleSuggestion.isSelected
        s.includeUnknownSeverities = cbUnknownSeverities.isSelected
        s.includeColumn = cbIncludeColumn.isSelected
        s.includeSeverityTag = cbIncludeSeverityTag.isSelected
        s.sortBySeverityFirst = cbSortBySeverityFirst.isSelected
    }

    override fun reset() {
        val s = settings.state
        cbError.isSelected = s.includeError
        cbWarning.isSelected = s.includeWarning
        cbWeakWarning.isSelected = s.includeWeakWarning
        cbInformation.isSelected = s.includeInformation
        cbGrammarError.isSelected = s.includeGrammarError
        cbTypo.isSelected = s.includeTypo
        cbServerProblem.isSelected = s.includeServerProblem
        cbStyleSuggestion.isSelected = s.includeStyleSuggestion
        cbUnknownSeverities.isSelected = s.includeUnknownSeverities
        cbIncludeColumn.isSelected = s.includeColumn
        cbIncludeSeverityTag.isSelected = s.includeSeverityTag
        cbSortBySeverityFirst.isSelected = s.sortBySeverityFirst
    }
}
