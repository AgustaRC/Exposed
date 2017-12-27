package org.jetbrains.exposed.sql.tests

import com.mysql.management.MysqldResource
import com.mysql.management.driverlaunched.MysqldResourceNotFoundException
import com.mysql.management.driverlaunched.ServerLauncherSocketFactory
import com.mysql.management.util.Files
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import org.h2.engine.Mode
import org.jetbrains.exposed.extensions.dataTypes.joda.JodaDateSPI
import org.jetbrains.exposed.extensions.dataTypes.joda.date as jodaDate
import org.jetbrains.exposed.extensions.dataTypes.joda.datetime as jodaDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.util.*
import kotlin.concurrent.thread


enum class TestDB(val connection: String, val driver: String, val user: String = "root", val pass: String = "",
                  val beforeConnection: () -> Unit = {}, val afterTestFinished: () -> Unit = {}) {
    H2("jdbc:h2:mem:regular", "org.h2.Driver"),
    H2_MYSQL("jdbc:h2:mem:test;MODE=MySQL", "org.h2.Driver", beforeConnection = {
        Mode.getInstance("MySQL").convertInsertNullToZero = false
    }),
    SQLITE("jdbc:sqlite:file:test?mode=memory&cache=shared", "org.sqlite.JDBC"),
    MYSQL("jdbc:mysql:mxj://localhost:12345/testdb1?createDatabaseIfNotExist=true&server.initialize-user=false&user=root&password=", "com.mysql.jdbc.Driver",
            beforeConnection = { System.setProperty(Files.USE_TEST_DIR, java.lang.Boolean.TRUE!!.toString()); Files().cleanTestDir(); Unit },
            afterTestFinished = {
                try {
                    val baseDir = Files().tmp(MysqldResource.MYSQL_C_MXJ)
                    ServerLauncherSocketFactory.shutdown(baseDir, null)
                } catch (e: MysqldResourceNotFoundException) {
                    exposedLogger.warn(e.message, e)
                } finally {
                    Files().cleanTestDir()
                }
            }),
    POSTGRESQL("jdbc:postgresql://localhost:12346/template1?user=postgres&password=&lc_messages=en_US.UTF-8", "org.postgresql.Driver",
            beforeConnection = { postgresSQLProcess }, afterTestFinished = { postgresSQLProcess.close() }),
    ORACLE(driver = "oracle.jdbc.OracleDriver", user = "ExposedTest", pass = "12345",
            connection = ("jdbc:oracle:thin:@//${System.getProperty("exposed.test.oracle.host", "localhost")}" +
                        ":${System.getProperty("exposed.test.oracle.port", "1521")}/xe"),
            beforeConnection = {
                Locale.setDefault(Locale.ENGLISH)
                Database.connect(ORACLE.connection, user = "sys as sysdba", password = "oracle", driver = ORACLE.driver)
                transaction(java.sql.Connection.TRANSACTION_READ_COMMITTED, 1) {
                    try {
                        exec("DROP USER ExposedTest CASCADE")
                    } catch (e: Exception) { // ignore
                        exposedLogger.warn("Exception on deleting ExposedTest user", e)
                    }

                    exec("CREATE USER ExposedTest IDENTIFIED BY 12345 DEFAULT TABLESPACE system QUOTA UNLIMITED ON system")
                    exec("grant all privileges to ExposedTest IDENTIFIED BY 12345")
                    exec("grant dba to ExposedTest IDENTIFIED BY 12345")
                }
                Unit
            }),
    SQLSERVER("jdbc:sqlserver://${System.getProperty("exposed.test.sqlserver.host", "localhost")}" +
            ":${System.getProperty("exposed.test.sqlserver.port", "1433")}",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "SA", "yourStrong(!)Password");

    companion object {
        fun enabledInTests(): List<TestDB> {
            val embeddedTests = (TestDB.values().toList() - ORACLE - SQLSERVER).joinToString()
            val concreteDialects = System.getProperty("exposed.test.dialects", embeddedTests).let {
                if (it == "") emptyList()
                else it.split(',').map { it.trim().toUpperCase() }
            }
            return values().filter { concreteDialects.isEmpty() || it.name in concreteDialects }
        }
    }
}

private val registeredOnShutdown = HashSet<TestDB>()

private val postgresSQLProcess by lazy {
//    val locale = if (PlatformUtil.isWindows()) "american_usa" else "en_US.UTF-8"
    EmbeddedPostgres.builder()
        .setPgBinaryResolver{ system, _ ->
            EmbeddedPostgres::class.java.getResourceAsStream("/postgresql-$system-x86_64.txz")
        }/*.setLocaleConfig("locale", locale)*/
        .setPort(12346).start()
}

abstract class DatabaseTestsBase {
    fun withDb(dbSettings: TestDB, statement: Transaction.() -> Unit) {
        if (dbSettings !in TestDB.enabledInTests())  {
            exposedLogger.warn("$dbSettings is not enabled for being used in tests", RuntimeException())
            return
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        DateTimeZone.setDefault(DateTimeZone.UTC)

        if (dbSettings !in registeredOnShutdown) {
            dbSettings.beforeConnection()
            Runtime.getRuntime().addShutdownHook(thread(false ){ dbSettings.afterTestFinished() })
            registeredOnShutdown += dbSettings
        }

        val database = Database.connect(dbSettings.connection, user = dbSettings.user, password = dbSettings.pass, driver = dbSettings.driver)

        val connection = database.connector()
        val transactionIsolation = connection.metaData.defaultTransactionIsolation
        connection.close()
        transaction(transactionIsolation, 1) {
            statement()
        }
    }

    fun withDb(excludeSettings: List<TestDB> = emptyList(), statement: Transaction.() -> Unit) {
        (TestDB.enabledInTests() - excludeSettings).forEach {
            withDb(it, statement)
        }
    }

    fun withTables (excludeSettings: List<TestDB>, vararg tables: Table, statement: Transaction.() -> Unit) {
        (TestDB.enabledInTests() - excludeSettings).forEach {
            withDb(it) {
                SchemaUtils.create(*tables)
                try {
                    statement()
                    commit() // Need commit to persist data before drop tables
                } finally {
                    SchemaUtils.drop(*tables)
                    commit()
                }
            }
        }
    }

    fun withTables (vararg tables: Table, statement: Transaction.() -> Unit) = withTables(excludeSettings = emptyList(), tables = *tables, statement = statement)

    fun <T>Transaction.assertEquals(exp: T, act: T) = kotlin.test.assertEquals(exp, act, "Failed on ${currentDialect.name}")
    fun <T>Transaction.assertEquals(exp: T, act: List<T>) = kotlin.test.assertEquals(exp, act.single(), "Failed on ${currentDialect.name}")
}

inline fun <reified DATE> Table.date(name: String) = when {
    DATE::class == DateTime::class -> jodaDate(name)
    else -> error("")
}

inline fun <reified DATE> Table.datetime(name: String) = when {
    DATE::class == DateTime::class -> jodaDateTime(name)
    else -> error("")
}

inline fun <reified DATE:Any> dateProvider() = when {
    DATE::class == DateTime::class -> JodaDateSPI
    else -> error("")
} as DateApi<DATE>