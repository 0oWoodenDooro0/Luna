package luna.rpg.repository

import luna.core.JsonLogger
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs

class SqlJsonLogger : SqlLogger {
    override fun log(context: StatementContext, transaction: Transaction) {
        val query = context.expandArgs(transaction)

        JsonLogger.log(
            layer = "DATABASE",
            component = "Exposed",
            operation = "query",
            data = mapOf(
                "sql" to query
            )
        )
    }
}
