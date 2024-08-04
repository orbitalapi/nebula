package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.InfrastructureExecutor
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*

class SqlExecutorTest : DescribeSpec({

    lateinit var infra: InfrastructureExecutor
    lateinit var dsl: DSLContext

    describe("PostgreSQL database") {
        afterTest {
            infra.shutDownAll()
        }

        it("should create tables and insert data with various types") {
            val now = Instant.now()

            infra = stack {
                postgres {
                    table(
                        "users", """
                        CREATE TABLE users (
                            id UUID PRIMARY KEY,
                            username VARCHAR(100) NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE,
                            is_active BOOLEAN,
                            login_count INTEGER,
                            balance DECIMAL(10, 2)
                        )
                    """, data = listOf(
                            mapOf(
                                "id" to UUID.randomUUID(),
                                "username" to "john_doe",
                                "created_at" to now,
                                "is_active" to true,
                                "login_count" to 5,
                                "balance" to BigDecimal("100.50")
                            ),
                            mapOf(
                                "id" to UUID.randomUUID(),
                                "username" to "jane_smith",
                                "created_at" to now.minusSeconds(3600),
                                "is_active" to false,
                                "login_count" to 2,
                                "balance" to BigDecimal("75.25")
                            )
                        )
                    )

                    table(
                        "products", """
                        CREATE TABLE products (
                            id SERIAL PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            description TEXT,
                            price DECIMAL(10, 2) NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                        )
                    """, data = listOf(

                            mapOf(
                                "name" to "Widget",
                                "description" to "A fantastic widget",
                                "price" to BigDecimal("9.99")
                            ),
                            mapOf(
                                "name" to "Gadget",
                                "description" to "An amazing gadget",
                                "price" to BigDecimal("24.99")
                            )
                        )
                    )
                }
            }.start()

            // Set up jOOQ DSL context
            dsl = infra.database.single().dsl

            // Test users table
            val users = dsl.selectFrom("users").fetch()
            users.size shouldBe 2

            val johnDoe = users.first()
            johnDoe["username"] shouldBe "john_doe"
            johnDoe["is_active"].shouldBeInstanceOf<Boolean>()
            johnDoe["is_active"] as Boolean shouldBe true
            johnDoe["login_count"].shouldBeInstanceOf<Int>()
            johnDoe["login_count"] as Int shouldBe 5
            johnDoe["balance"].shouldBeInstanceOf<BigDecimal>()
            (johnDoe["balance"] as BigDecimal).toDouble() shouldBe 100.50
            johnDoe["created_at"].shouldBeInstanceOf<OffsetDateTime>()

            // Test products table
            val products = dsl.selectFrom("products").fetch()
            products.size shouldBe 2

            val widget = products.first()
            widget["name"] shouldBe "Widget"
            widget["description"] shouldBe "A fantastic widget"
            widget["price"].shouldBeInstanceOf<BigDecimal>()
            (widget["price"] as BigDecimal).toDouble() shouldBe 9.99
            widget["created_at"].shouldBeInstanceOf<OffsetDateTime>()

            // Test auto-incrementing ID
            widget["id"].shouldBeInstanceOf<Int>()
            (widget["id"] as Int) shouldBe 1
        }
    }
})