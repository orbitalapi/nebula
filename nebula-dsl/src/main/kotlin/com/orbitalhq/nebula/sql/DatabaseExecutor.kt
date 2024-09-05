package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.ComponentInfo
import com.orbitalhq.nebula.ContainerInfo
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackRunner
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.containers.JdbcDatabaseContainer

val StackRunner.database: List<DatabaseExecutor>
    get() = this.component<DatabaseExecutor>()

class DatabaseExecutor(private val config: DatabaseConfig) : InfrastructureComponent<DatabaseContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    override val type: String = config.type

    val databaseContainer: JdbcDatabaseContainer<*>
        get() = config.container
    lateinit var dataSource: HikariDataSource
        private set

    lateinit var dsl: DSLContext
        private set

    override fun start(): ComponentInfo<DatabaseContainerConfig> {
        databaseContainer.withDatabaseName(config.databaseName)
        databaseContainer.start()

        setupDataSource()
        setupJooq()
        createTablesAndLoadData()

        return ComponentInfo(
            ContainerInfo.from(databaseContainer),
            DatabaseContainerConfig(
                databaseContainer.databaseName,
                databaseContainer.jdbcUrl,
                databaseContainer.username,
                databaseContainer.password,
                databaseContainer.firstMappedPort.toString()
            )
        )
    }

    private fun setupJooq() {
        dsl = DSL.using(dataSource, config.dialect)
    }

    override fun stop() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
        databaseContainer.stop()
    }

    private fun setupDataSource() {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseContainer.jdbcUrl
            username = databaseContainer.username
            password = databaseContainer.password
            driverClassName = databaseContainer.driverClassName
        }
        dataSource = HikariDataSource(hikariConfig)
    }

    private fun createTablesAndLoadData() {
        config.tables.forEach { table ->
            createTable(table)
            if (table.data.isNotEmpty()) {
                insertData(table)
            }
        }
    }

    private fun createTable(table: TableConfig) {
        logger.info { "Creating table: ${table.name}" }
        dsl.execute(table.ddl)
    }

    private fun insertData(table: TableConfig) {
        if (table.data.isEmpty()) return

        val columns = table.data.first().keys
        val jooqTable = DSL.table(table.name)

        val insert = dsl.insertInto(jooqTable)
            .columns(columns.map { DSL.field(it) })

        table.data.forEach { row ->
            insert.values(row.values.map { convertValue(it) })
        }

        val insertedRows = insert.execute()
        logger.info { "Inserted $insertedRows rows into ${table.name}" }
    }

    private fun convertValue(value: Any?): Any? {
        return when (value) {
            is String -> DSL.inline(value)
            is Number -> DSL.inline(value)
            is Boolean -> DSL.inline(value)
            null -> null
            else -> DSL.inline(value.toString()) // Fallback to string representation
        }
    }

    val jdbcUrl: String
        get() = databaseContainer.jdbcUrl

    val username: String
        get() = databaseContainer.username

    val password: String
        get() = databaseContainer.password
}

// Placeholder until I know what to put here
data class DatabaseContainerConfig(
    val databaseName: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val port: String
)