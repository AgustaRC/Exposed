package org.jetbrains.exposed.sql.statements

import org.jetbrains.exposed.dao.EntityCache
import org.jetbrains.exposed.sql.*
import java.sql.PreparedStatement

class DeleteStatement(val table: Table, val where: Op<Boolean>? = null, val isIgnore: Boolean = false): Statement<Int>(StatementType.DELETE, listOf(table)) {

    override fun PreparedStatement.executeInternal(transaction: Transaction): Int {
        if (where == null) {
            transaction.flushCache()
        } else {
            EntityCache.getOrCreate(transaction).run {
                val dependencies = data.filter {
                    table in addDependencies(listOf(it.key)) && it.value.values.any { it.writeValues.keys.any { it.referee?.table == table } }
                }.keys
                flush(dependencies)
                removeTablesReferrers(listOf(table))
            }
        }
        return executeUpdate()
    }

    override fun prepareSQL(transaction: Transaction): String = transaction.db.dialect.delete(isIgnore, table, where?.toSQL(QueryBuilder(true)), transaction)

    override fun arguments(): Iterable<Iterable<Pair<ColumnType, Any?>>> = QueryBuilder(true).run {
        where?.toSQL(this)
        listOf(args)
    }

    companion object {

        fun where(transaction: Transaction, table: Table, op: Op<Boolean>, isIgnore: Boolean = false): Int
            = DeleteStatement(table, op, isIgnore).execute(transaction).first ?: 0

        fun all(transaction: Transaction, table: Table): Int = DeleteStatement(table).execute(transaction).first ?: 0
    }
}