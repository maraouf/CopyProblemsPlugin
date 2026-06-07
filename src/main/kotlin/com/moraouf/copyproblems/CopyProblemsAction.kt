package com.moraouf.copyproblems

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.intellij.util.Processor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Copies diagnostics to the clipboard in the configured scope and output format. The
 * [CopyProblemsAsAiPromptAction] subclass flips [asAiPrompt] to additionally prepend the
 * instruction header from settings.
 */
open class CopyProblemsAction : AnAction() {

    /** When true, prepend `settings.state.aiPromptHeader` before the rendered problem list. */
    protected open val asAiPrompt: Boolean get() = false

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val settings = CopyProblemsSettings.getInstance()

        when (settings.state.copyScope) {
            CopyProblemsSettings.CopyScope.ACTIVE_FILE ->
                runActiveFile(project, editor, psiFile, settings)

            CopyProblemsSettings.CopyScope.OPEN_EDITORS ->
                finishMultiFile(
                    project, editor, settings,
                    emptyMessage = "No problems found in open editors (after applying severity filters).",
                ) { collectFromOpenEditors(project, settings) }

            CopyProblemsSettings.CopyScope.VCS_CHANGED -> {
                val files = vcsChangedFiles(project)
                if (files == null) {
                    // No active VCS: the VCS-changed scope deliberately does not fall back to scanning
                    // the whole project (that would be a heavy surprise), so there's nothing to do.
                    showInfo(
                        project, editor,
                        "This project is not under version control, so the VCS-changed scope has nothing to scan.",
                        settings,
                    )
                    return
                }
                finishMultiFile(
                    project, editor, settings,
                    emptyMessage = "No problems found in VCS-changed files (after applying severity filters).",
                ) { collectViaCodeSmell(project, files, settings) }
            }

            CopyProblemsSettings.CopyScope.CURRENT_DIRECTORY -> {
                val dir = psiFile.virtualFile?.parent
                if (dir == null) {
                    showError(project, editor, "The active file has no enclosing directory to scan.", settings)
                    return
                }
                val files = directoryFiles(project, dir)
                finishMultiFile(
                    project, editor, settings,
                    emptyMessage = "No problems found under ${dir.name} (after applying severity filters).",
                ) { collectViaCodeSmell(project, files, settings) }
            }
        }
    }

    // --- Active-file scope (original behavior, incl. the reformat-before-copy flow) -----------------

    private fun runActiveFile(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        settings: CopyProblemsSettings,
    ) {
        val document = editor.document
        val fileName = psiFile.name
        // Path relative to the project root for the clipboard output — disambiguates files that share
        // a name across directories. '/' keeps it stable across OSes. Files outside the project root
        // (library sources, scratch files) have no relative path; for those fall back to either the
        // full absolute path or the bare name, per the fullPathForExternalFiles setting.
        val displayPath = displayPathFor(project, psiFile.virtualFile, settings)

        // Optionally reformat the file first so whitespace/formatting warnings are fixed and
        // therefore don't show up in the copied list. Reformatting edits the document, but the
        // diagnostics the daemon has *already published* still describe the pre-reformat text — the
        // whitespace/formatting warnings the reformat just resolved keep sitting in the markup model
        // until the daemon re-analyzes. So we can't read highlights right after reformatting; we
        // restart the daemon and copy once it reports back (see scheduleCopyAfterReanalysis).
        // The reformat option intentionally applies to the active-file scope only — reformatting an
        // entire VCS changelist or directory on a copy action would be a surprising side effect.
        if (settings.state.reformatBeforeCopy) {
            try {
                PsiDocumentManager.getInstance(project).commitDocument(document)
                ReformatCodeProcessor(psiFile, false).run()
                PsiDocumentManager.getInstance(project).commitDocument(document)
            } catch (t: Throwable) {
                showError(project, editor, "Could not reformat $fileName: ${t.message}", settings)
                return
            }
            scheduleCopyAfterReanalysis(project, editor, psiFile, document, displayPath, fileName, settings)
            return
        }

        collectAndCopy(project, editor, document, displayPath, fileName, settings)
    }

    /**
     * After a reformat the daemon's published highlights are stale — they still include the
     * whitespace/formatting warnings the reformat resolved. We can't wait synchronously (the daemon
     * publishes its results on the EDT, so blocking it would deadlock), so restart highlighting and
     * perform the copy from the daemon-finished listener. A timeout fallback guarantees the copy
     * still happens if highlighting never reports (e.g. unavailable for this file type).
     */
    private fun scheduleCopyAfterReanalysis(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        document: Document,
        displayPath: String,
        fileName: String,
        settings: CopyProblemsSettings,
    ) {
        val lifetime = Disposer.newDisposable("CopyProblems.reanalysis")
        val done = AtomicBoolean(false)
        val finish = {
            if (done.compareAndSet(false, true)) {
                Disposer.dispose(lifetime)
                collectAndCopy(project, editor, document, displayPath, fileName, settings)
            }
        }
        try {
            project.messageBus.connect(lifetime).subscribe(
                DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                object : DaemonCodeAnalyzer.DaemonListener {
                    override fun daemonFinished() = finish()
                },
            )
            Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime).addRequest(finish, REANALYSIS_TIMEOUT_MS)
            restartDaemon(DaemonCodeAnalyzer.getInstance(project), psiFile)
        } catch (_: Throwable) {
            // If we couldn't wire up the wait, fall back to copying whatever is current.
            finish()
        }
    }

    /**
     * Force the daemon to re-highlight [psiFile]. 2026.1 deprecated the single-argument
     * `restart(PsiFile)` in favor of a reason-carrying `restart(PsiFile, Object)` overload that
     * doesn't exist on older platforms (222–251). We call whichever is present reflectively so a
     * single build spans the whole supported range and no deprecated symbol is referenced in the
     * bytecode (which keeps the plugin verifier quiet on the newer IDE).
     */
    private fun restartDaemon(daemon: DaemonCodeAnalyzer, psiFile: PsiFile) {
        try {
            daemon.javaClass
                .getMethod("restart", PsiFile::class.java, Any::class.java)
                .invoke(daemon, psiFile, "Copy All Problems: reanalyze after reformat")
        } catch (_: NoSuchMethodException) {
            daemon.javaClass
                .getMethod("restart", PsiFile::class.java)
                .invoke(daemon, psiFile)
        }
    }

    private fun collectAndCopy(
        project: Project,
        editor: Editor,
        document: Document,
        displayPath: String,
        fileName: String,
        settings: CopyProblemsSettings,
    ) {
        val problems = try {
            toRawProblems(document, readHighlights(project, document), settings)
        } catch (t: Throwable) {
            showError(project, editor, "Could not read diagnostics: ${t.message}", settings)
            return
        }

        if (problems.isEmpty()) {
            showInfo(
                project,
                editor,
                "No problems found in $fileName (after applying severity filters).",
                settings,
            )
            return
        }

        val output = composeOutput(listOf(FileProblems(displayPath, problems)), grouped = false, settings)
        CopyPasteManager.getInstance().setContents(StringSelection(output))
        showInfo(
            project,
            editor,
            "Copied ${problems.size} problem(s) from $fileName to clipboard.",
            settings,
        )
    }

    // --- Multi-file scopes -------------------------------------------------------------------------

    /**
     * Shared tail for the multi-file scopes: run [collector] (which may open a cancelable progress
     * dialog), then emit a per-file grouped list to the clipboard. A canceled collection (the user
     * dismissing the analysis progress) quietly copies nothing.
     */
    private fun finishMultiFile(
        project: Project,
        editor: Editor,
        settings: CopyProblemsSettings,
        emptyMessage: String,
        collector: () -> List<FileProblems>,
    ) {
        val files = try {
            collector()
        } catch (_: ProcessCanceledException) {
            return
        } catch (t: Throwable) {
            showError(project, editor, "Could not collect diagnostics: ${t.message}", settings)
            return
        }

        if (files.isEmpty()) {
            showInfo(project, editor, emptyMessage, settings)
            return
        }

        val output = composeOutput(files, grouped = true, settings)
        CopyPasteManager.getInstance().setContents(StringSelection(output))
        val total = files.sumOf { it.problems.size }
        showInfo(
            project,
            editor,
            "Copied $total problem(s) from ${files.size} file(s) to clipboard.",
            settings,
        )
    }

    /**
     * Open-editor files are already analyzed by the daemon, so read their published highlights
     * directly (full severity fidelity, no progress dialog) rather than re-running analysis.
     */
    private fun collectFromOpenEditors(project: Project, settings: CopyProblemsSettings): List<FileProblems> {
        val documentManager = FileDocumentManager.getInstance()
        val result = mutableListOf<FileProblems>()
        for (virtualFile in FileEditorManager.getInstance(project).openFiles) {
            if (!virtualFile.isValid || virtualFile.isDirectory) continue
            val document = documentManager.getDocument(virtualFile) ?: continue
            val problems = toRawProblems(document, readHighlights(project, document), settings)
            if (problems.isNotEmpty()) {
                result.add(FileProblems(displayPathFor(project, virtualFile, settings), problems))
            }
        }
        return result.sortedBy { it.displayPath }
    }

    /**
     * For files that may not be open (VCS-changed, directory walk) the daemon hasn't published any
     * highlights yet, so use [CodeSmellDetector] — the same engine the IDE's pre-commit "Analyze
     * code" uses. It runs analysis on demand under a cancelable modal progress and returns
     * errors/warnings (it does not surface pure INFORMATION-level annotations). Our severity filter
     * is still applied on top.
     */
    private fun collectViaCodeSmell(
        project: Project,
        files: List<VirtualFile>,
        settings: CopyProblemsSettings,
    ): List<FileProblems> {
        if (files.isEmpty()) return emptyList()
        val smells = CodeSmellDetector.getInstance(project).findCodeSmells(files)
        val documentManager = FileDocumentManager.getInstance()
        // Preserve discovery order per document; sorting within each file happens at output time.
        val byDocument = LinkedHashMap<Document, MutableList<RawProblem>>()
        for (info in smells) {
            val severityName = info.severity.name
            if (!settings.isSeverityEnabled(severityName)) continue
            byDocument.getOrPut(info.document) { mutableListOf() }.add(
                RawProblem(info.document, info.textRange.startOffset, severityName, info.severity.myVal, info.description),
            )
        }
        return byDocument.entries
            .map { (document, problems) ->
                FileProblems(displayPathFor(project, documentManager.getFile(document), settings), problems)
            }
            .sortedBy { it.displayPath }
    }

    /** Files under the VCS working tree, or null when the project has no active VCS. */
    private fun vcsChangedFiles(project: Project): List<VirtualFile>? {
        return try {
            if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) return null
            ChangeListManager.getInstance(project).affectedFiles
                .filter { it.isValid && !it.isDirectory }
                .distinct()
        } catch (_: Throwable) {
            // VCS API unavailable in this IDE / failed to query — treat as "no scannable VCS".
            null
        }
    }

    /** Project-content files under [dir], recursively (skips excluded, library, and binary files). */
    private fun directoryFiles(project: Project, dir: VirtualFile): List<VirtualFile> {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val result = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
            if (!file.isDirectory && fileIndex.isInContent(file) && !file.fileType.isBinary) {
                result.add(file)
            }
            true
        }
        return result
    }

    // --- Shared collection / formatting ------------------------------------------------------------

    private fun readHighlights(project: Project, document: Document): List<HighlightInfo> =
        ApplicationManager.getApplication().runReadAction(
            Computable<List<HighlightInfo>> {
                val collected = mutableListOf<HighlightInfo>()
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    null,
                    0,
                    document.textLength,
                    Processor { info ->
                        collected.add(info)
                        true
                    },
                )
                collected
            },
        )

    private fun toRawProblems(
        document: Document,
        infos: List<HighlightInfo>,
        settings: CopyProblemsSettings,
    ): List<RawProblem> = infos
        .filter { it.description != null }
        .filter { isRealProblem(it) }
        .filter { settings.isSeverityEnabled(it.severity.name) }
        .map { RawProblem(document, it.startOffset, it.severity.name, it.severity.myVal, it.description!!) }

    private fun sortProblems(problems: List<RawProblem>, settings: CopyProblemsSettings): List<RawProblem> =
        if (settings.state.sortBySeverityFirst) {
            problems.sortedWith(
                compareByDescending<RawProblem> { it.severityVal }.thenBy { it.startOffset },
            )
        } else {
            problems.sortedBy { it.startOffset }
        }

    private fun formatProblemLine(displayPath: String, p: RawProblem, settings: CopyProblemsSettings): String {
        val line = p.document.getLineNumber(p.startOffset) + 1
        val sb = StringBuilder()
        sb.append(displayPath).append(':').append(line)
        if (settings.state.includeColumn) {
            val col = (p.startOffset - p.document.getLineStartOffset(line - 1)) + 1
            sb.append(':').append(col)
        }
        if (settings.state.includeSeverityTag) {
            sb.append(" [").append(p.severityName).append(']')
        }
        sb.append(' ').append(p.description)
        return sb.toString()
    }

    /**
     * Produce the final clipboard string: the format-respecting problem list, plus the AI-prompt
     * instruction header in front when this is the [CopyProblemsAsAiPromptAction] variant.
     */
    private fun composeOutput(
        files: List<FileProblems>,
        grouped: Boolean,
        settings: CopyProblemsSettings,
    ): String {
        val body = renderOutput(files, grouped, settings)
        if (!asAiPrompt) return body
        val header = settings.state.aiPromptHeader.trim()
        return if (header.isEmpty()) body else "$header\n\n$body"
    }

    /**
     * Serialize the collected problems per the configured output format. [grouped] only affects the
     * PLAIN format (a `# path` header per file); MARKDOWN_TABLE and JSON always carry the path as a
     * column/field, so they ignore it.
     */
    private fun renderOutput(
        files: List<FileProblems>,
        grouped: Boolean,
        settings: CopyProblemsSettings,
    ): String = when (settings.state.outputFormat) {
        CopyProblemsSettings.OutputFormat.PLAIN -> renderPlain(files, grouped, settings)
        CopyProblemsSettings.OutputFormat.MARKDOWN_TABLE -> renderMarkdown(files, settings)
        CopyProblemsSettings.OutputFormat.JSON -> renderJson(files, settings)
    }

    private fun renderPlain(
        files: List<FileProblems>,
        grouped: Boolean,
        settings: CopyProblemsSettings,
    ): String = buildString {
        val includeCode = settings.state.includeCodeContext
        files.forEachIndexed { index, fp ->
            if (grouped) {
                if (index > 0) append('\n')
                append("# ").append(fp.displayPath).append('\n')
            }
            for (p in sortProblems(fp.problems, settings)) {
                append(formatProblemLine(fp.displayPath, p, settings)).append('\n')
                if (includeCode) {
                    val code = codeLineFor(p)
                    if (code.isNotEmpty()) append("    ").append(code).append('\n')
                }
            }
        }
    }

    /**
     * A single GitHub-flavored Markdown table across every file (File column carries the path), so
     * it pastes cleanly into a PR or issue. The Col column appears only when includeColumn is on;
     * Severity is always present. Cell contents are pipe/newline-escaped so descriptions can't break
     * the table.
     */
    private fun renderMarkdown(files: List<FileProblems>, settings: CopyProblemsSettings): String {
        val includeColumn = settings.state.includeColumn
        val includeCode = settings.state.includeCodeContext
        return buildString {
            append("| File | Line |")
            if (includeColumn) append(" Col |")
            append(" Severity | Description |")
            if (includeCode) append(" Code |")
            append('\n')
            append("| --- | --- |")
            if (includeColumn) append(" --- |")
            append(" --- | --- |")
            if (includeCode) append(" --- |")
            append('\n')
            for (fp in files) {
                for (p in sortProblems(fp.problems, settings)) {
                    val line = p.document.getLineNumber(p.startOffset) + 1
                    append("| ").append(mdCell(fp.displayPath)).append(" | ").append(line).append(" |")
                    if (includeColumn) {
                        val col = (p.startOffset - p.document.getLineStartOffset(line - 1)) + 1
                        append(' ').append(col).append(" |")
                    }
                    append(' ').append(mdCell(p.severityName)).append(" |")
                    append(' ').append(mdCell(p.description)).append(" |")
                    if (includeCode) {
                        val code = codeLineFor(p)
                        append(' ').append(if (code.isEmpty()) "" else "`${mdCell(code)}`").append(" |")
                    }
                    append('\n')
                }
            }
        }
    }

    /**
     * A flat JSON array of `{path, line, col?, severity, message}` objects — handy for piping into
     * a script or an AI assistant. The col field appears only when includeColumn is on; severity is
     * always present. Strings are escaped via StringUtil.escapeStringCharacters (handles quotes,
     * backslashes and control characters).
     */
    private fun renderJson(files: List<FileProblems>, settings: CopyProblemsSettings): String {
        val includeColumn = settings.state.includeColumn
        val includeCode = settings.state.includeCodeContext
        return buildString {
            append("[\n")
            var first = true
            for (fp in files) {
                for (p in sortProblems(fp.problems, settings)) {
                    val line = p.document.getLineNumber(p.startOffset) + 1
                    if (!first) append(",\n")
                    first = false
                    append("  {")
                    append("\"path\": ").append(jsonString(fp.displayPath))
                    append(", \"line\": ").append(line)
                    if (includeColumn) {
                        val col = (p.startOffset - p.document.getLineStartOffset(line - 1)) + 1
                        append(", \"col\": ").append(col)
                    }
                    append(", \"severity\": ").append(jsonString(p.severityName))
                    append(", \"message\": ").append(jsonString(p.description))
                    if (includeCode) {
                        append(", \"code\": ").append(jsonString(codeLineFor(p)))
                    }
                    append("}")
                }
            }
            append("\n]\n")
        }
    }

    /** The trimmed source text of the line the problem starts on, for "include code context". */
    private fun codeLineFor(p: RawProblem): String {
        val lineNumber = p.document.getLineNumber(p.startOffset)
        val start = p.document.getLineStartOffset(lineNumber)
        val end = p.document.getLineEndOffset(lineNumber)
        return p.document.getText(TextRange(start, end)).trim()
    }

    /** Escape a Markdown table cell: backslash-escape pipes and flatten newlines to spaces. */
    private fun mdCell(text: String): String =
        text.replace("\\", "\\\\").replace("|", "\\|").replace(Regex("[\\r\\n]+"), " ").trim()

    private fun jsonString(text: String): String = "\"" + StringUtil.escapeStringCharacters(text) + "\""

    private fun displayPathFor(
        project: Project,
        virtualFile: VirtualFile?,
        settings: CopyProblemsSettings,
    ): String {
        val baseDir = project.guessProjectDir()
        if (virtualFile != null && baseDir != null) {
            val relativePath = VfsUtilCore.getRelativePath(virtualFile, baseDir, '/')
            if (relativePath != null) return relativePath
        }
        if (settings.state.fullPathForExternalFiles && virtualFile != null) return virtualFile.path
        return virtualFile?.name ?: "<unknown>"
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.PSI_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    // Drop pure visual annotations (symbol coloring below INFORMATION, URL/identifier markers at INFORMATION) that the Problems tool window also hides.
    private fun isRealProblem(info: HighlightInfo): Boolean {
        val sev = info.severity.myVal
        if (sev < HighlightSeverity.INFORMATION.myVal) return false
        if (sev >= HighlightSeverity.WEAK_WARNING.myVal) return true
        return (info.inspectionToolId != null) || (info.problemGroup != null)
    }

    private fun showInfo(project: Project, editor: Editor, message: String, settings: CopyProblemsSettings) {
        when (settings.state.notificationStyle) {
            CopyProblemsSettings.NotificationStyle.MODAL ->
                Messages.showInfoMessage(project, message, TITLE)

            CopyProblemsSettings.NotificationStyle.BALLOON ->
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(project)

            CopyProblemsSettings.NotificationStyle.EDITOR_HINT ->
                HintManager.getInstance().showInformationHint(editor, message)

            CopyProblemsSettings.NotificationStyle.SILENT -> Unit
        }
    }

    private fun showError(project: Project, editor: Editor, message: String, settings: CopyProblemsSettings) {
        when (settings.state.notificationStyle) {
            CopyProblemsSettings.NotificationStyle.MODAL ->
                Messages.showErrorDialog(project, message, TITLE)

            CopyProblemsSettings.NotificationStyle.BALLOON ->
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(message, NotificationType.ERROR)
                    .notify(project)

            CopyProblemsSettings.NotificationStyle.EDITOR_HINT ->
                HintManager.getInstance().showErrorHint(editor, message)

            CopyProblemsSettings.NotificationStyle.SILENT -> Unit
        }
    }

    /** One diagnostic, normalized across the highlight and code-smell collection paths. */
    private class RawProblem(
        val document: Document,
        val startOffset: Int,
        val severityName: String,
        val severityVal: Int,
        val description: String,
    )

    /** All problems for a single file, plus the project-relative path used in the output header. */
    private class FileProblems(
        val displayPath: String,
        val problems: List<RawProblem>,
    )

    private companion object {
        const val TITLE = "Copy All Problems"
        const val NOTIFICATION_GROUP = "Copy All Problems"

        // Upper bound on how long we wait for the daemon to re-highlight after a reformat before
        // copying whatever is current. Re-analysis normally reports back well under this.
        const val REANALYSIS_TIMEOUT_MS = 5000
    }
}

/**
 * "Copy as AI Prompt" variant: same scope/format/code-context behavior as [CopyProblemsAction],
 * but prepends the configurable instruction header so the clipboard contents can be pasted straight
 * into an AI assistant. Registered as a separate action (own id + menu entries) in `plugin.xml`.
 */
class CopyProblemsAsAiPromptAction : CopyProblemsAction() {
    override val asAiPrompt: Boolean get() = true
}
