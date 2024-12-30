package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.utils.NameGenerator
import org.jooq.SQLDialect
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

interface SqlDsl : InfraDsl {
    fun postgres(imageName: String = "postgres:13", databaseName: String = "testDb", componentName: ComponentName = "postgres", dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor =
        database(PostgreSQLContainer(DockerImageName.parse(imageName)), SQLDialect.POSTGRES, "postgres", databaseName, componentName, dsl)

    fun mysql(imageName: String = "mysql:9", databaseName: String = "testDb", componentName: ComponentName = "mysql", dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor =
        database(MySQLContainer(DockerImageName.parse(imageName)), SQLDialect.MYSQL, "mysql", databaseName, componentName, dsl)

    fun database(container: JdbcDatabaseContainer<*>, dialect: SQLDialect, type: String, databaseName: String, componentName: ComponentName, dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor {
        val builder = DatabaseBuilder(container, dialect, type, databaseName, componentName)
        builder.dsl()
        return this.add(DatabaseExecutor(builder.build()))
    }
}


class DatabaseBuilder(private val container: JdbcDatabaseContainer<*>, private val dialect: SQLDialect, private val type: String, private val databaseName: String, private val componentName: ComponentName) {
    private val tables = mutableListOf<TableConfig>()

    fun table(name: String, ddl: String, data: List<Map<String, Any>> = emptyList()) {
        tables.add(TableConfig(name, ddl, data))
    }

    fun table(name: String, ddl: String, vararg data: Map<String, Any>) {
        table(name, ddl, data.toList())
    }

    fun build(): DatabaseConfig = DatabaseConfig(container, dialect,  tables, type, databaseName, componentName)
}

data class DatabaseConfig(
    /**
     * The base configuration of the container.
     * The actual container used at runtime is configured in the executor, to configure
     * things like networking and network aliases
     */
    val container: JdbcDatabaseContainer<*>,
    val dialect: SQLDialect,
    val tables: List<TableConfig>,
    val type: String,
    val databaseName: String,
    val componentName: String
)

data class TableConfig(val name: String, val ddl: String, val data: List<Map<String, Any>>)