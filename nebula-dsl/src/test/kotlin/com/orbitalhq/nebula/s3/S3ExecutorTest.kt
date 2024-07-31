package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.InfrastructureExecutor
import com.orbitalhq.nebula.services
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class S3ExecutorTest : DescribeSpec({
    lateinit var infra: InfrastructureExecutor
    describe("Test S3Executor") {
        afterTest {
            infra?.shutDown()
        }

        it("should create a bucket with an inline file resource") {
            infra = services {
                s3 {
                    bucket("test-bucket") {
                        file("hello.txt", "Hello, world")
                    }
                }
            }.start()

            val content = infra.s3.getObjectContent("test-bucket", "hello.txt")
            content.shouldBe("Hello, world")
        }

        it("should create a bucket with a file resource") {
            // Create a temporary file
            val tempFile = Files.createTempFile("test", ".csv")
            val fileContent = "column1,column2\nvalue1,value2"
            tempFile.toFile().writeText(fileContent)

            infra = services {
                s3 {
                    bucket("test-bucket") {
                        file(tempFile.toString())
                    }
                }
            }.start()

            // Verify the file content
            val content = infra.s3.getObjectContent("test-bucket", tempFile.fileName.toString())
            content.shouldBe(fileContent)

            // Clean up
            Files.delete(tempFile)
        }
    }
})
