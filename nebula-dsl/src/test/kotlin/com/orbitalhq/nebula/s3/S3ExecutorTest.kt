package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class S3ExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner
    describe("Test S3Executor") {
        afterTest {
            infra?.shutDownAll()
        }

        it("should create a bucket with an inline file resource") {
            infra = stack {
                s3 {
                    bucket("test-bucket") {
                        file("hello.txt", "Hello, world")
                    }
                }
            }.start()

            val content = infra.s3.single().getObjectContent("test-bucket", "hello.txt")
            content.shouldBe("Hello, world")
        }

        it("should create a bucket with a file resource") {
            // Create a temporary file
            val tempFile = Files.createTempFile("test", ".csv")
            val fileContent = "column1,column2\nvalue1,value2"
            tempFile.toFile().writeText(fileContent)

            infra = stack {
                s3 {
                    bucket("test-bucket") {
                        file(tempFile.toString())
                    }
                }
            }.start()

            // Verify the file content
            val content = infra.s3.single().getObjectContent("test-bucket", tempFile.fileName.toString())
            content.shouldBe(fileContent)

            // Clean up
            Files.delete(tempFile)
        }
    }
})
