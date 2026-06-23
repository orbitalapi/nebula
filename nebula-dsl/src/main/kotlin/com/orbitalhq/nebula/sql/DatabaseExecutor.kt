package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.endpointFor
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.logging.LogStream
import com.orbitalhq.nebula.logging.LoggerName
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.containers.JdbcDatabaseContainer
import reactor.core.publisher.Flux

val StackRunner.database: List<DatabaseExecutor>
    get() = this.component<DatabaseExecutor>()

class DatabaseExecutor(private val config: DatabaseConfig, loggers: List<LoggerName>) : InfrastructureComponent<DatabaseContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    override val type = config.type

    private lateinit var databaseContainer: JdbcDatabaseContainer<*>
    lateinit var dataSource: HikariDataSource
        private set

    lateinit var dsl: DSLContext
        private set

    override val name = config.componentName
    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(DatabaseExecutor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)


    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }



    override var componentInfo: ComponentInfo<DatabaseContainerConfig>? = null
        private set

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<DatabaseContainerConfig> {
        databaseContainer = config.container.withDatabaseName(config.databaseName)
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        eventSource.startContainerAndEmitEvents(databaseContainer, name)

        setupDataSource()
        setupJooq()
        createTablesAndLoadData()

        // The internal port the DB listens on inside the container (5432, 3306, ...).
        val internalPort = databaseContainer.exposedPorts.first()
        val endpoint = nebulaConfig.endpointFor(databaseContainer, config.componentName, internalPort)
        // The TestContainers jdbcUrl embeds the host-mapped coordinates; swap them
        // for the resolved endpoint so the emitted url matches the connectivity mode.
        val emittedJdbcUrl = databaseContainer.jdbcUrl.replace(
            "${databaseContainer.host}:${databaseContainer.getMappedPort(internalPort)}",
            endpoint.hostAndPort
        )

        componentInfo =  ComponentInfo(
            containerInfoFrom(databaseContainer, endpoint.host),
            DatabaseContainerConfig(
                databaseContainer.databaseName,
                emittedJdbcUrl,
                databaseContainer.username,
                databaseContainer.password,
                endpoint.host,
                endpoint.port.toString()
            ),
            type = type,
            name = name,
            id = id

        )
        eventSource.running()
        return componentInfo!!
    }

    private fun setupJooq() {
        dsl = DSL.using(dataSource, config.dialect)
    }

    override fun stop() {
        eventSource.stopping()
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
        eventSource.stopContainerAndEmitEvents(databaseContainer)
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
    val host: String,
    val port: String
)