package dev.alexananev.reposcopesearch.ui

import dev.alexananev.reposcopesearch.model.SearchResult
import javax.swing.table.AbstractTableModel

class ResultTableModel : AbstractTableModel() {
    private val columns = arrayOf("Репозиторий", "Ветка", "Файл", "Строка", "Контекст", "Коммит", "Режим")
    val rows = mutableListOf<SearchResult>()
    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) { 0 -> r.project; 1 -> r.branch; 2 -> r.path; 3 -> r.line; 4 -> r.snippet; 5 -> r.commit.take(10); else -> r.backend }
    }
    fun clear() { val n = rows.size; rows.clear(); if (n > 0) fireTableRowsDeleted(0, n - 1) }
    fun add(r: SearchResult) { val i = rows.size; rows += r; fireTableRowsInserted(i, i) }
}
