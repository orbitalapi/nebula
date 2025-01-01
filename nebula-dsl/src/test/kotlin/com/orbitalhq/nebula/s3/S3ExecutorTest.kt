package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import java.nio.file.Files
import kotlin.random.Random

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

        it("should create a bucket using a sequence resource") {

            // This sequence generates a file approx 14MB:
            // 100k rows * 147 bytes a row.
            var generatedSize = 0L
            val sequence = sequence<String> {
                var generatedRows = 0
                val rowCount = 100_000

                // Back of the napkin - each row is cira 147 bytes
                while (generatedRows < rowCount) {
                    val randomRow = (0..20).map {
                        Random.nextInt(100_000, 999_999)
                    }.joinToString(",", postfix = "\n")
                    generatedRows++
                    yield(randomRow)
                    val sizeInBytes = randomRow.toByteArray().size
                    generatedSize += sizeInBytes
                }
            }
            val tempFile = Files.createTempFile("test", ".csv")
            infra = stack {
                s3 {
                    bucket("test-bucket") {
                        file(tempFile.fileName.toString(), sequence)
                    }
                }
            }.start()
            val s3client = infra.s3.single()
                .s3Client
            val headObjectResponse = s3client.headObject(
                HeadObjectRequest.builder().bucket("test-bucket").key(tempFile.fileName.toString()).build()
            )
            headObjectResponse.contentLength() shouldBe generatedSize
        }
    }
})
