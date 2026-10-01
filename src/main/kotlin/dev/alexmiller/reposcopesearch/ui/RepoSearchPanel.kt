package dev.alexmiller.reposcopesearch.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.alexmiller.reposcopesearch.api.GitLabApiClient
import dev.alexmiller.reposcopesearch.model.*
import dev.alexmiller.reposcopesearch.search.ContentMatcher
import dev.alexmiller.reposcopesearch.search.SearchEngine
import dev.alexmiller.reposcopesearch.settings.RepoSearchSettings
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import com.intellij.openapi.ui.DialogWrapper


class RepoSearchPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val settings = RepoSearchSettings.get(project)
    private val query = JBTextArea(3, 80)
    private val gitLabUrl = JBTextField(settings.data.gitLabUrl, 28)
    private val token = JBPasswordField().apply {
        columns = 24
        settings.token()?.let { text = it }
    }
    private val mode = JComboBox(SearchMode.entries.toTypedArray())
    private val caseSensitive = JCheckBox("Учитывать регистр")
    private val multiline = JCheckBox("Многострочный режим")
    private val fuzzy = JCheckBox("Неточный поиск")
    private val threshold = JSpinner(SpinnerNumberModel(settings.data.fuzzyThreshold, 0.50, 1.0, 0.01))
    private val searchButton = JButton("Искать")
    private val stopButton = JButton("Остановить").apply { isEnabled = false }
    private val status = JBLabel("Готово")
    private val tableModel = ResultTableModel()
    private val table = JTable(tableModel)
    private val details = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(6)
    }
    private val foundFileButton = JButton("Файл в найденном коммите")
    private val mainFileButton = JButton("Файл в основной ветке")
    private val commitButton = JButton("Коммит")
    private val settingsButton = JButton("Параметры")
    @Volatile private var currentIndicator: ProgressIndicator? = null
    private var warnings = 0

    init {
        border = JBUI.Borders.empty(6)
        add(buildSearchForm(), BorderLayout.NORTH)
        add(buildResults(), BorderLayout.CENTER)
        add(buildStatus(), BorderLayout.SOUTH)
        wireActions()
        updateLinkButtons(null)
    }

    private fun buildSearchForm(): JComponent {
        query.lineWrap = true
        query.wrapStyleWord = true
        query.toolTipText = "Текст или регулярное выражение; поле поддерживает переносы строк"

        val connection = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("GitLab:")); add(gitLabUrl)
            add(JBLabel("Токен:")); add(token)
            add(JButton("Создать токен").apply { addActionListener { openTokenPage() } })
            add(JButton("Проверить").apply { addActionListener { testConnection() } })
        }
        val options = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Режим:")); add(mode)
            add(caseSensitive); add(multiline); add(fuzzy)
            add(JBLabel("Порог:")); add(threshold)
            add(settingsButton); add(searchButton); add(stopButton)
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("Поиск по всем доступным репозиториям GitLab")
            add(connection, BorderLayout.NORTH)
            add(JBScrollPane(query), BorderLayout.CENTER)
            add(options, BorderLayout.SOUTH)
        }
    }

    private fun buildResults(): JComponent {
        table.autoCreateRowSorter = true
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.columnModel.getColumn(0).preferredWidth = 200
        table.columnModel.getColumn(1).preferredWidth = 110
        table.columnModel.getColumn(2).preferredWidth = 260
        table.columnModel.getColumn(4).preferredWidth = 320

        val links = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(foundFileButton); add(mainFileButton); add(commitButton)
        }
        val detailPanel = JPanel(BorderLayout()).apply {
            add(links, BorderLayout.NORTH)
            add(JBScrollPane(details), BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), detailPanel).apply {
            resizeWeight = 0.62
            dividerLocation = 360
        }
    }

    private fun buildStatus(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(4)
        add(status, BorderLayout.CENTER)
    }

    private fun wireActions() {
        fuzzy.addActionListener {
            threshold.isEnabled = fuzzy.isSelected
            if (fuzzy.isSelected) mode.selectedItem = SearchMode.TEXT
        }
        mode.addActionListener {
            if (mode.selectedItem == SearchMode.REGEXP) fuzzy.isSelected = false
            fuzzy.isEnabled = mode.selectedItem == SearchMode.TEXT
            threshold.isEnabled = fuzzy.isSelected
        }
        threshold.isEnabled = false
        searchButton.addActionListener { startSearch() }
        stopButton.addActionListener { currentIndicator?.cancel() }
        settingsButton.addActionListener { showSettingsDialog() }
        table.selectionModel.addListSelectionListener(this::selectionChanged)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedResult()?.let { BrowserUtil.browse(it.foundFileUrl) }
            }
        })
        foundFileButton.addActionListener { selectedResult()?.let { BrowserUtil.browse(it.foundFileUrl) } }
        mainFileButton.addActionListener { selectedResult()?.let { BrowserUtil.browse(it.mainFileUrl) } }
        commitButton.addActionListener { selectedResult()?.let { BrowserUtil.browse(it.commitUrl) } }
    }

    private fun startSearch() {
        val tokenValue = String(token.password).trim()
        val baseUrl = RepoSearchSettings.normalizedBaseUrl(gitLabUrl.text)
        if (tokenValue.isBlank()) {
            Messages.showErrorDialog(project, "Введите персональный токен GitLab.", "Repo Scope Search")
            return
        }
        val state = settings.data
        val options = SearchOptions(
            query = query.text,
            mode = mode.selectedItem as SearchMode,
            caseSensitive = caseSensitive.isSelected,
            multiline = multiline.isSelected,
            fuzzy = fuzzy.isSelected,
            fuzzyThreshold = (threshold.value as Number).toDouble(),
            contextLines = state.contextLines,
            maxFileBytes = state.maxFileMb.toLong() * 1024 * 1024,
            maxResults = state.maxResults
        )
        ContentMatcher.validate(options)?.let {
            Messages.showErrorDialog(project, it, "Ошибка шаблона")
            return
        }
        state.gitLabUrl = baseUrl
        settings.setToken(tokenValue)
        tableModel.clear()
        details.text = ""
        warnings = 0
        setRunning(true)

        object : Task.Backgroundable(project, "Repo Scope Search", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try {
                    SearchEngine(baseUrl, tokenValue, Path.of(state.cacheDirectory)).search(options, indicator) { event ->
                        ApplicationManager.getApplication().invokeLater { handleEvent(event) }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        status.foreground = JBColor.RED
                        status.text = "Ошибка: ${e.message}"
                        setRunning(false)
                    }
                }
            }
        }.queue()
    }

    private fun handleEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.Status -> status.text = event.text
            is SearchEvent.Found -> {
                tableModel.add(event.result)
                status.text = "Найдено: ${tableModel.rowCount}. ${event.result.project.pathWithNamespace} / ${event.result.branch}"
                if (tableModel.rowCount == 1) table.setRowSelectionInterval(0, 0)
            }
            is SearchEvent.Warning -> {
                warnings++
                status.foreground = JBColor.ORANGE
                status.text = "Предупреждений: $warnings. ${event.text.take(220)}"
            }
            is SearchEvent.Finished -> {
                status.foreground = UIManager.getColor("Label.foreground")
                status.text = buildString {
                    append(if (event.cancelled) "Остановлено" else "Завершено")
                    append(": ${event.results} результатов, ${event.repositories} репозиториев")
                    if (warnings > 0) append(", предупреждений: $warnings")
                }
                setRunning(false)
            }
        }
    }

    private fun testConnection() {
        val value = String(token.password).trim()
        if (value.isBlank()) return
        object : Task.Backgroundable(project, "Проверка GitLab", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = GitLabApiClient(RepoSearchSettings.normalizedBaseUrl(gitLabUrl.text), value)
                    val user = client.currentUser()
                    val count = client.listAccessibleProjects().size
                    ApplicationManager.getApplication().invokeLater {
                        settings.data.gitLabUrl = RepoSearchSettings.normalizedBaseUrl(gitLabUrl.text)
                        settings.setToken(value)
                        Messages.showInfoMessage(project, "Пользователь: ${user.username}\nДоступных непустых репозиториев: $count", "GitLab подключён")
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, e.message ?: "Ошибка подключения", "GitLab")
                    }
                }
            }
        }.queue()
    }

    private fun openTokenPage() {
        val base = RepoSearchSettings.normalizedBaseUrl(gitLabUrl.text)
        val name = java.net.URLEncoder.encode("Repo Scope Search", Charsets.UTF_8)
        val description = java.net.URLEncoder.encode("Read-only access for PyCharm cross-repository search", Charsets.UTF_8)
        BrowserUtil.browse("$base/-/user_settings/personal_access_tokens?name=$name&description=$description&scopes=read_api,read_repository")
    }

    private fun showSettingsDialog() {
        val state = settings.data

        val context = JSpinner(
            SpinnerNumberModel(
                state.contextLines,
                0,
                20,
                1
            )
        )

        val maxMb = JSpinner(
            SpinnerNumberModel(
                state.maxFileMb,
                1,
                1024,
                1
            )
        )

        val maxResults = JSpinner(
            SpinnerNumberModel(
                state.maxResults,
                1,
                1_000_000,
                100
            )
        )

        val cache = JBTextField(
            state.cacheDirectory,
            42
        )

        val panel = JPanel(GridLayout(0, 2, 8, 6)).apply {
            border = JBUI.Borders.empty(12)

            add(JBLabel("Строк контекста сверху/снизу:"))
            add(context)

            add(JBLabel("Максимальный файл, МБ:"))
            add(maxMb)

            add(JBLabel("Максимум результатов:"))
            add(maxResults)

            add(JBLabel("Каталог bare-кэша:"))
            add(cache)
        }

        val dialog = object : DialogWrapper(project) {
            init {
                title = "Параметры поиска"
                setOKButtonText("Сохранить")
                setCancelButtonText("Отмена")
                init()
            }

            override fun createCenterPanel(): JComponent = panel

            override fun getPreferredFocusedComponent(): JComponent = context
        }

        if (dialog.showAndGet()) {
            state.contextLines =
                (context.value as Number).toInt()

            state.maxFileMb =
                (maxMb.value as Number).toInt()

            state.maxResults =
                (maxResults.value as Number).toInt()

            state.cacheDirectory =
                cache.text.trim()
        }
    }

    private fun selectionChanged(event: ListSelectionEvent) {
        if (event.valueIsAdjusting) return
        val result = selectedResult()
        updateLinkButtons(result)
        details.text = if (result == null) "" else buildString {
            appendLine("${result.project.pathWithNamespace}  [${result.branch}]")
            appendLine("${result.path}:${result.match.line}  commit ${result.commit}")
            result.match.similarity?.let { appendLine("Сходство: ${"%.1f".format(it * 100)}%") }
            appendLine()
            append(result.match.snippet)
        }
        details.caretPosition = 0
    }

    private fun selectedResult(): SearchResult? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return tableModel.get(table.convertRowIndexToModel(viewRow))
    }

    private fun updateLinkButtons(result: SearchResult?) {
        foundFileButton.isEnabled = result != null
        mainFileButton.isEnabled = result != null
        commitButton.isEnabled = result != null
    }

    private fun setRunning(value: Boolean) {
        searchButton.isEnabled = !value
        stopButton.isEnabled = value
        if (!value) currentIndicator = null
    }
}
