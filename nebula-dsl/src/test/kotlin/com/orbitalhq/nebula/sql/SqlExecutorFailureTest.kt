package com.orbitalhq.nebula.sql

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.LifecycleEventWithMessage
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Invalid DDL (or bad seed data) must surface as a Failed component state carrying
 * the database's error message — not as an exception thrown out of the stack's
 * start thread, which previously left the component stuck reporting a healthy
 * state with nothing reported to the user.
 */
class SqlExecutorFailureTest : DescribeSpec({

    lateinit var infra: StackRunner

    describe("a postgres stack with invalid DDL") {
        afterTest {
            infra.shutDownAll()
        }

        it("reports Failed with the database error instead of throwing out of start") {
            infra = stack {
                postgres {
                    // Deliberately broken: references a type that doesn't exist.
                    table(
                        "users", """
                        CREATE TABLE users (
                            id NOT_A_REAL_TYPE PRIMARY KEY
                        )
                    """
                    )
                }
            }.start()

            val database = infra.database.single()
            val state = database.currentState
            state.state shouldBe ComponentState.Failed
            state.shouldBeInstanceOf<LifecycleEventWithMessage>()
            state.message shouldContain "not_a_real_type"

            // The stack snapshot (what the /stream/stacks contract sends) reflects the failure too
            val snapshot = infra.snapshot().single()
            val componentState = snapshot.stackState.values.single().single().state
            componentState.state shouldBe ComponentState.Failed
        }
    }
})
