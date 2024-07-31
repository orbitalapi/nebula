package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.InfraDsl
import org.jooq.SQLDialect
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

interface SqlDsl : InfraDsl {
    fun postgres(imageName: String = "postgres:13", dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor =
        database(PostgreSQLContainer(DockerImageName.parse(imageName)), SQLDialect.POSTGRES, dsl)

    fun mysql(imageName: String = "mysql:9", dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor =
        database(MySQLContainer(DockerImageName.parse(imageName)), SQLDialect.MYSQL, dsl)

    fun database(container: JdbcDatabaseContainer<*>, dialect: SQLDialect, dsl: DatabaseBuilder.() -> Unit): DatabaseExecutor {
        val builder = DatabaseBuilder(container, dialect)
        builder.dsl()
        return this.add(DatabaseExecutor(builder.build()))
    }
}


class DatabaseBuilder(private val container: JdbcDatabaseContainer<*>, private val dialect: SQLDialect) {
    private val tables = mutableListOf<TableConfig>()

    fun table(name: String, ddl: String, data: List<Map<String, Any>> = emptyList()) {
        tables.add(TableConfig(name, ddl, data))
    }

    fun table(name: String, ddl: String, vararg data: Map<String, Any>) {
        table(name, ddl, data.toList())
    }

    fun build(): DatabaseConfig = DatabaseConfig(container, dialect,  tables)
}

data class DatabaseConfig(
    val container: JdbcDatabaseContainer<*>,
    val dialect: SQLDialect,
    val tables: List<TableConfig>,
)

data class TableConfig(val name: String, val ddl: String, val data: List<Map<String, Any>>)