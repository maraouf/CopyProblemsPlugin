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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.Alarm
import com.intellij.util.Processor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean

class CopyProblemsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val document = editor.document
        val fileName = psiFile.name
        val settings = CopyProblemsSettings.getInstance()
        // Path relative to the project root for the clipboard output — disambiguates files that share
        // a name across directories. '/' keeps it stable across OSes. Files outside the project root
        // (library sources, scratch files) have no relative path; for those fall back to either the
        // full absolute path or the bare name, per the fullPathForExternalFiles setting.
        val displayPath = run {
            val virtualFile = psiFile.virtualFile
            val baseDir = project.guessProjectDir()
            val relativePath = if (virtualFile != null && baseDir != null) {
                VfsUtilCore.getRelativePath(virtualFile, baseDir, '/')
            } else {
                null
            }
            relativePath
                ?: if (settings.state.fullPathForExternalFiles && virtualFile != null) virtualFile.path else fileName
        }

        // Optionally reformat the file first so whitespace/formatting warnings are fixed and
        // therefore don't show up in the copied list. Reformatting edits the document, but the
        // diagnostics the daemon has *already published* still describe the pre-reformat text — the
        // whitespace/formatting warnings the reformat just resolved keep sitting in the markup model
        // until the daemon re-analyzes. So we can't read highlights right after reformatting; we
        // restart the daemon and copy once it reports back (see scheduleCopyAfterReanalysis).
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
        val highlights = try {
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
        } catch (t: Throwable) {
            showError(project, editor, "Could not read diagnostics: ${t.message}", settings)
            return
        }

        val filtered: List<HighlightInfo> = highlights
            .filter { it.description != null }
            .filter { isRealProblem(it) }
            .filter { settings.isSeverityEnabled(it.severity.name) }
            .let { list ->
                if (settings.state.sortBySeverityFirst) {
                    list.sortedWith(
                        compareByDescending<HighlightInfo> { it.severity.myVal }
                            .thenBy { it.startOffset },
                    )
                } else {
                    list.sortedBy { it.startOffset }
                }
            }

        if (filtered.isEmpty()) {
            showInfo(
                project,
                editor,
                "No problems found in $fileName (after applying severity filters).",
                settings,
            )
            return
        }

        val includeColumn = settings.state.includeColumn
        val includeSeverityTag = settings.state.includeSeverityTag

        val output = buildString {
            for (info in filtered) {
                val offset = info.startOffset
                val line = document.getLineNumber(offset) + 1
                append(displayPath).append(':').append(line)
                if (includeColumn) {
                    val col = (offset - document.getLineStartOffset(line - 1)) + 1
                    append(':').append(col)
                }
                if (includeSeverityTag) {
                    append(" [").append(info.severity.name).append(']')
                }
                append(' ').append(info.description).append('\n')
            }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(output))
        showInfo(
            project,
            editor,
            "Copied ${filtered.size} problem(s) from $fileName to clipboard.",
            settings,
        )
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

    private companion object {
        const val TITLE = "Copy All Problems"
        const val NOTIFICATION_GROUP = "Copy All Problems"

        // Upper bound on how long we wait for the daemon to re-highlight after a reformat before
        // copying whatever is current. Re-analysis normally reports back well under this.
        const val REANALYSIS_TIMEOUT_MS = 5000
    }
}
