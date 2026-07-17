package com.moraouf.copyproblems

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

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
    private val cbIncludeCodeContext =
        JBCheckBox("Include the offending source line as context (indented line / Code column / code field)")

    private val aiPromptHeaderField = JBTextField(36)

    private val cbReformatBeforeCopy =
        JBCheckBox("Reformat the file (Reformat Code) before copying, to clear whitespace/formatting warnings")

    private val scopeCombo = ComboBox(CopyProblemsSettings.CopyScope.values()).apply {
        renderer = labelRenderer { (it as? CopyProblemsSettings.CopyScope)?.let(::scopeLabel).orEmpty() }
    }

    private val formatCombo = ComboBox(CopyProblemsSettings.OutputFormat.values()).apply {
        renderer = labelRenderer { (it as? CopyProblemsSettings.OutputFormat)?.let(::formatLabel).orEmpty() }
    }

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
        CheckboxBinding(cbIncludeCodeContext, { it.includeCodeContext }) { s, v -> s.includeCodeContext = v },
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

        panel.add(JBLabel("<html><b>Default scope</b> &mdash; which files the action copies problems from:</html>"))
        panel.add(Box.createVerticalStrut(8))
        val scopeRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        scopeRow.alignmentX = Component.LEFT_ALIGNMENT
        scopeRow.add(scopeCombo)
        panel.add(scopeRow)
        panel.add(Box.createVerticalStrut(4))
        panel.add(
            JBLabel(
                "<html><i>Open editors and the active file read already-computed problems; VCS-changed " +
                    "and directory scopes run analysis (with a progress bar) and report errors/warnings.</i></html>",
            ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND },
        )

        panel.add(Box.createVerticalStrut(16))
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
        val formatRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        formatRow.alignmentX = Component.LEFT_ALIGNMENT
        formatRow.add(JBLabel("Copy as: "))
        formatRow.add(formatCombo)
        panel.add(formatRow)
        panel.add(Box.createVerticalStrut(8))
        panel.add(cbIncludeColumn)
        panel.add(cbIncludeSeverityTag)
        panel.add(cbSortBySeverityFirst)
        panel.add(cbFullPathForExternalFiles)
        panel.add(cbIncludeCodeContext)
        panel.add(Box.createVerticalStrut(4))
        panel.add(
            JBLabel(
                "<html><i>Plain uses all options above. Markdown and JSON always include the path " +
                    "and severity (so the [SEVERITY] tag option is plain-only) and honor the column option.</i></html>",
            ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND },
        )

        panel.add(Box.createVerticalStrut(16))
        panel.add(JBLabel("<html><b>AI prompt</b> &mdash; header for the <i>Copy All Problems as AI Prompt</i> action:</html>"))
        panel.add(Box.createVerticalStrut(8))
        val aiPromptRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        aiPromptRow.alignmentX = Component.LEFT_ALIGNMENT
        aiPromptRow.add(JBLabel("Instruction: "))
        aiPromptRow.add(aiPromptHeaderField)
        panel.add(aiPromptRow)
        panel.add(Box.createVerticalStrut(4))
        panel.add(
            JBLabel(
                "<html><i>Prepended (followed by a blank line) before the problem list, which still " +
                    "follows the output format above. Enable “Include the offending source line” for code context.</i></html>",
            ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND },
        )

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

        panel.add(Box.createVerticalStrut(16))
        val version = pluginVersion()
        val versionText = if (version != null) "Copy All Problems v$version" else "Copy All Problems"
        val versionLabel = JBLabel("<html><i>$versionText</i></html>")
        versionLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(versionLabel)

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return checkboxBindings.any { it.isModified(s) } ||
            (selectedStyle() != s.notificationStyle) ||
            (selectedScope() != s.copyScope) ||
            (selectedFormat() != s.outputFormat) ||
            (aiPromptHeaderField.text != s.aiPromptHeader)
    }

    override fun apply() {
        val s = settings.state
        checkboxBindings.forEach { it.save(s) }
        s.notificationStyle = selectedStyle()
        s.copyScope = selectedScope()
        s.outputFormat = selectedFormat()
        s.aiPromptHeader = aiPromptHeaderField.text
    }

    override fun reset() {
        val s = settings.state
        checkboxBindings.forEach { it.load(s) }
        scopeCombo.selectedItem = s.copyScope
        formatCombo.selectedItem = s.outputFormat
        aiPromptHeaderField.text = s.aiPromptHeader
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

    private fun selectedScope(): CopyProblemsSettings.CopyScope =
        scopeCombo.selectedItem as? CopyProblemsSettings.CopyScope
            ?: CopyProblemsSettings.CopyScope.ACTIVE_FILE

    private fun scopeLabel(scope: CopyProblemsSettings.CopyScope): String = when (scope) {
        CopyProblemsSettings.CopyScope.ACTIVE_FILE -> "Active file"
        CopyProblemsSettings.CopyScope.OPEN_EDITORS -> "All open editors"
        CopyProblemsSettings.CopyScope.VCS_CHANGED -> "VCS-changed files (git working tree)"
        CopyProblemsSettings.CopyScope.CURRENT_DIRECTORY -> "Current file's directory (recursive)"
    }

    private fun selectedFormat(): CopyProblemsSettings.OutputFormat =
        formatCombo.selectedItem as? CopyProblemsSettings.OutputFormat
            ?: CopyProblemsSettings.OutputFormat.PLAIN

    /**
     * A combo-box renderer that shows [textFn] applied to each value. Built on the core-Swing
     * DefaultListCellRenderer rather than com.intellij.ui.SimpleListCellRenderer, whose create(...)
     * factories are scheduled for removal and whose class is deprecated outright on 2026.2+.
     * DefaultListCellRenderer is never deprecated, exists on every supported platform, and already
     * honors the IDE's list selection colors via super.getListCellRendererComponent.
     */
    private fun labelRenderer(textFn: (Any?) -> String): ListCellRenderer<Any?> =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = textFn(value)
                return this
            }
        }

    private fun formatLabel(format: CopyProblemsSettings.OutputFormat): String = when (format) {
        CopyProblemsSettings.OutputFormat.PLAIN -> "Plain text (path:line:col [SEVERITY] description)"
        CopyProblemsSettings.OutputFormat.MARKDOWN_TABLE -> "Markdown table"
        CopyProblemsSettings.OutputFormat.JSON -> "JSON array"
    }

    /**
     * The plugin version, read from a resource that Gradle stamps at build time (see
     * processResources in build.gradle.kts). Reading it this way avoids the plugin-descriptor
     * lookup APIs (PluginManager/PluginManagerCore.getPlugin), which are marked @ApiStatus.Internal
     * on 2026.2+, and keeps the version single-sourced from the Gradle `version`.
     */
    private fun pluginVersion(): String? =
        javaClass.getResourceAsStream("version.properties")?.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version")?.takeIf { it.isNotBlank() }
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
