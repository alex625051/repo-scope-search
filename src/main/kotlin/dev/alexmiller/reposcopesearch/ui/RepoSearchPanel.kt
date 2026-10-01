package dev.alexananev.reposcopesearch.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.alexananev.reposcopesearch.api.GitLabApiClient
import dev.alexananev.reposcopesearch.git.RepositoryCache
import dev.alexananev.reposcopesearch.model.*
import dev.alexananev.reposcopesearch.search.SearchEngine
import dev.alexananev.reposcopesearch.settings.RepoSearchSettings
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLEncoder
import java.nio.file.Path
import javax.swing.*

class RepoSearchPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val settings = RepoSearchSettings.instance()
    private val url = JBTextField(settings.data.gitLabUrl, 22)
    private val token = JBPasswordField().apply { text = settings.token() }
    private val query = JBTextArea(3, 70)
    private val fileMask = JBTextField(settings.data.fileMask, 22)
    private val mode = JComboBox(QueryMode.entries.toTypedArray())
    private val backend = JComboBox(SearchBackend.entries.toTypedArray())
    private val caseSensitive = JBCheckBox("Регистр")
    private val multiline = JBCheckBox("Multiline")
    private val fuzzy = JBCheckBox("Неточный")
    private val threshold = JSpinner(SpinnerNumberModel(0.80, 0.10, 1.0, 0.05))
    private val defaultOnly = JBCheckBox("Только основная ветка", settings.data.defaultBranchOnly)
    private val tableModel = ResultTableModel()
    private val table = JBTable(tableModel)
    private val status = JBLabel("Готово")
    private val warnings = JBTextArea(4, 80).apply { isEditable = false }
    private var currentIndicator: ProgressIndicator? = null

    init {
        border = JBUI.Borders.empty(6)
        add(buildControls(), BorderLayout.NORTH)
        add(buildResults(), BorderLayout.CENTER)
        add(JBScrollPane(warnings).apply { preferredSize = Dimension(100, 90) }, BorderLayout.SOUTH)
    }

    private fun buildControls(): JComponent = JPanel(BorderLayout(4, 4)).apply {
        val auth = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("GitLab:")); add(url); add(JBLabel("Токен:")); add(token)
            add(JButton("Создать токен").apply { addActionListener { openTokenPage() } })
            add(JButton("Проверить").apply { addActionListener { testConnection() } })
        }
        val options = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Режим:")); add(mode); add(JBLabel("Backend:")); add(backend)
            add(caseSensitive); add(multiline); add(fuzzy); add(JBLabel("Порог:")); add(threshold); add(defaultOnly)
            add(JBLabel("Regex пути:")); add(fileMask)
            add(JButton("Параметры").apply { addActionListener { showSettingsDialog() } })
            add(JButton("О кэше").apply { addActionListener { showCacheInfo() } })
            add(JButton("Очистить кэш").apply { addActionListener { clearCache() } })
            add(JButton("Искать").apply { addActionListener { startSearch() } })
            add(JButton("Стоп").apply { addActionListener { currentIndicator?.cancel() } })
        }
        val q = JPanel(BorderLayout()).apply { add(JBLabel("Запрос:"), BorderLayout.WEST); add(JBScrollPane(query), BorderLayout.CENTER) }
        add(auth, BorderLayout.NORTH); add(q, BorderLayout.CENTER); add(JPanel(BorderLayout()).apply { add(options, BorderLayout.CENTER); add(status, BorderLayout.SOUTH) }, BorderLayout.SOUTH)
    }

    private fun buildResults(): JComponent {
        table.autoCreateRowSorter = true
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.columnModel.getColumn(0).preferredWidth = 200; table.columnModel.getColumn(1).preferredWidth = 110
        table.columnModel.getColumn(2).preferredWidth = 260; table.columnModel.getColumn(4).preferredWidth = 420
        table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openSelected() } })
        return JBScrollPane(table)
    }

    private fun startSearch() {
        if (query.text.isBlank()) { Messages.showWarningDialog(project, "Введите запрос", "Repo Scope Search"); return }
        saveAuth(); tableModel.clear(); warnings.text = ""
        val o = try { options() } catch (e: Exception) { Messages.showErrorDialog(project, e.message ?: "Ошибка параметров", "Repo Scope Search"); return }
        val client = GitLabApiClient(RepoSearchSettings.normalizedBaseUrl(url.text), String(token.password))
        val engine = SearchEngine(client, String(token.password), Path.of(settings.data.cacheDirectory))
        val backgroundTask = object : Task.Backgroundable(project, "Repo Scope Search", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try { engine.search(o, indicator) { event -> ApplicationManager.getApplication().invokeLater { onEvent(event) } } }
                finally { currentIndicator = null }
            }
        }
        ProgressManager.getInstance().run(backgroundTask)
    }

    private fun options() = SearchOptions(query.text, mode.selectedItem as QueryMode, backend.selectedItem as SearchBackend,
        caseSensitive.isSelected, multiline.isSelected, fuzzy.isSelected, (threshold.value as Number).toDouble(),
        fileMask.text.trim().ifBlank { null }, defaultOnly.isSelected, settings.data.contextLines,
        settings.data.maxFileMb.toLong() * 1024 * 1024, settings.data.maxResults, settings.data.parallelism)

    private fun onEvent(e: SearchEvent) = when (e) {
        is SearchEvent.Status -> status.text = e.text
        is SearchEvent.Warning -> warnings.append("⚠ ${e.text}\n")
        is SearchEvent.Match -> tableModel.add(e.result)
        is SearchEvent.Finished -> status.text = "Готово: ${e.matches} совпадений, ${e.repositories} репозиториев${if (e.cancelled) " (отменено)" else ""}"
    }

    private fun testConnection() { saveAuth(); object : Task.Backgroundable(project, "Проверка GitLab", true) {
        override fun run(indicator: ProgressIndicator) { try { val api = GitLabApiClient(RepoSearchSettings.normalizedBaseUrl(url.text), String(token.password)); val u = api.currentUser(); val n = api.projects().size; ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "${u.username}: доступно проектов $n", "GitLab") } } catch (e: Exception) { ApplicationManager.getApplication().invokeLater { Messages.showErrorDialog(project, e.message ?: "Ошибка", "GitLab") } } }
    }.also { ProgressManager.getInstance().run(it) } }

    private fun showSettingsDialog() {
        val s = settings.data
        val context = JSpinner(SpinnerNumberModel(s.contextLines, 0, 20, 1)); val maxMb = JSpinner(SpinnerNumberModel(s.maxFileMb, 1, 1024, 1))
        val maxResults = JSpinner(SpinnerNumberModel(s.maxResults, 1, 1_000_000, 100)); val parallel = JSpinner(SpinnerNumberModel(s.parallelism, 1, 16, 1))
        val cache = JBTextField(s.cacheDirectory, 42)
        val panel = JPanel(GridLayout(0, 2, 8, 6)).apply { border = JBUI.Borders.empty(12); add(JBLabel("Строк контекста:")); add(context); add(JBLabel("Макс. файл, МБ:")); add(maxMb); add(JBLabel("Макс. результатов:")); add(maxResults); add(JBLabel("Параллельных репозиториев:")); add(parallel); add(JBLabel("Каталог кэша:")); add(cache) }
        val dialog = object : DialogWrapper(project) { init { title = "Параметры поиска"; setOKButtonText("Сохранить"); init() }; override fun createCenterPanel(): JComponent = panel }
        if (dialog.showAndGet()) { s.contextLines = (context.value as Number).toInt(); s.maxFileMb = (maxMb.value as Number).toInt(); s.maxResults = (maxResults.value as Number).toInt(); s.parallelism = (parallel.value as Number).toInt(); s.cacheDirectory = cache.text.trim() }
    }

    private fun showCacheInfo() { val p = Path.of(settings.data.cacheDirectory); val size = if (java.nio.file.Files.exists(p)) java.nio.file.Files.walk(p).use { it.filter(java.nio.file.Files::isRegularFile).mapToLong { file -> java.nio.file.Files.size(file) }.sum() } else 0; Messages.showInfoMessage(project, "Каталог: $p\nРазмер: %.2f ГБ\n\nЛокальный режим хранит shallow bare-кэш. Первый поиск скачивает вершины веток и blobs до лимита; следующие делают fetch изменений. Серверный режим кэш не использует.".format(size / 1073741824.0), "Кэш") }
    private fun clearCache() { if (Messages.showYesNoDialog(project, "Удалить весь локальный кэш?", "Repo Scope Search", Messages.getQuestionIcon()) == Messages.YES) { RepositoryCache(Path.of(settings.data.cacheDirectory), String(token.password)).deleteAll(); Messages.showInfoMessage(project, "Кэш удалён", "Repo Scope Search") } }
    private fun openSelected() { val view = table.selectedRow; if (view < 0) return; val r = tableModel.rows[table.convertRowIndexToModel(view)]; BrowserUtil.browse(r.fileAtCommitUrl) }
    private fun openTokenPage() { val base = RepoSearchSettings.normalizedBaseUrl(url.text); val name = URLEncoder.encode("Repo Scope Search", Charsets.UTF_8); BrowserUtil.browse("$base/-/user_settings/personal_access_tokens?name=$name&scopes=read_api,read_repository") }
    private fun saveAuth() { settings.data.gitLabUrl = RepoSearchSettings.normalizedBaseUrl(url.text); settings.data.fileMask = fileMask.text; settings.data.defaultBranchOnly = defaultOnly.isSelected; settings.setToken(String(token.password)) }
}
