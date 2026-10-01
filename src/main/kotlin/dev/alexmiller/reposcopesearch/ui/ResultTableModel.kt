package dev.alexmiller.reposcopesearch.ui

import dev.alexmiller.reposcopesearch.model.SearchResult
import javax.swing.table.AbstractTableModel

class ResultTableModel : AbstractTableModel() {
    private val rows = ArrayList<SearchResult>()
    private val columns = arrayOf("Репозиторий", "Ветка", "Файл", "Строка", "Совпадение", "Коммит")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.project.pathWithNamespace
            1 -> row.branch
            2 -> row.path
            3 -> if (row.match.line == row.match.endLine) row.match.line else "${row.match.line}-${row.match.endLine}"
            4 -> row.match.matchedText.replace('\n', ' ').take(160)
            else -> row.commit.take(10)
        }
    }

    fun add(result: SearchResult) {
        val index = rows.size
        rows += result
        fireTableRowsInserted(index, index)
    }

    fun clear() {
        val last = rows.lastIndex
        rows.clear()
        if (last >= 0) fireTableRowsDeleted(0, last)
    }

    fun get(index: Int): SearchResult? = rows.getOrNull(index)
}
