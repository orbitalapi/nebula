package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.StackRunner
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

    lateinit var infra: StackRunner
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

    describe("Oracle database") {
        afterTest {
            infra.shutDownAll()
        }

        // Oracle folds unquoted identifiers to uppercase and jOOQ quotes them, so tables and
        // columns are declared in uppercase here. Oracle types differ from Postgres: no UUID
        // (stored as VARCHAR2), no BOOLEAN (NUMBER(1)), and identity columns instead of SERIAL.
        it("should create tables and insert data with various types") {
            infra = stack {
                oracle {
                    table(
                        "USERS", """
                        CREATE TABLE USERS (
                            ID VARCHAR2(36) PRIMARY KEY,
                            USERNAME VARCHAR2(100) NOT NULL,
                            IS_ACTIVE NUMBER(1),
                            LOGIN_COUNT NUMBER(10),
                            BALANCE NUMBER(10, 2)
                        )
                    """, data = listOf(
                            mapOf(
                                "ID" to UUID.randomUUID().toString(),
                                "USERNAME" to "john_doe",
                                "IS_ACTIVE" to 1,
                                "LOGIN_COUNT" to 5,
                                "BALANCE" to BigDecimal("100.50")
                            ),
                            mapOf(
                                "ID" to UUID.randomUUID().toString(),
                                "USERNAME" to "jane_smith",
                                "IS_ACTIVE" to 0,
                                "LOGIN_COUNT" to 2,
                                "BALANCE" to BigDecimal("75.25")
                            )
                        )
                    )

                    table(
                        "PRODUCTS", """
                        CREATE TABLE PRODUCTS (
                            ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            NAME VARCHAR2(100) NOT NULL,
                            DESCRIPTION VARCHAR2(255),
                            PRICE NUMBER(10, 2) NOT NULL
                        )
                    """, data = listOf(
                            mapOf(
                                "NAME" to "Widget",
                                "DESCRIPTION" to "A fantastic widget",
                                "PRICE" to BigDecimal("9.99")
                            ),
                            mapOf(
                                "NAME" to "Gadget",
                                "DESCRIPTION" to "An amazing gadget",
                                "PRICE" to BigDecimal("24.99")
                            )
                        )
                    )
                }
            }.start()

            // Set up jOOQ DSL context
            dsl = infra.database.single().dsl

            // Test USERS table
            val users = dsl.selectFrom("USERS").fetch()
            users.size shouldBe 2

            val johnDoe = users.first { it["USERNAME"] == "john_doe" }
            johnDoe["USERNAME"] shouldBe "john_doe"
            (johnDoe["IS_ACTIVE"] as Number).toInt() shouldBe 1
            (johnDoe["LOGIN_COUNT"] as Number).toInt() shouldBe 5
            (johnDoe["BALANCE"] as BigDecimal).toDouble() shouldBe 100.50

            // Test PRODUCTS table
            val products = dsl.selectFrom("PRODUCTS").fetch()
            products.size shouldBe 2

            val widget = products.first { it["NAME"] == "Widget" }
            widget["DESCRIPTION"] shouldBe "A fantastic widget"
            (widget["PRICE"] as BigDecimal).toDouble() shouldBe 9.99

            // Test auto-incrementing identity column
            (widget["ID"] as Number).toInt() shouldBe 1
        }
    }
})