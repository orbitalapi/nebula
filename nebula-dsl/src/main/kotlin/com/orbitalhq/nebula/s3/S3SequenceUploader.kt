package com.orbitalhq.nebula.s3

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import java.util.concurrent.atomic.AtomicInteger

class S3SequenceUploader(
    private val bucketName: String,
    private val resource: SequenceResource,
    private val s3Client: S3Client,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    fun upload() {
        val createMultipartResponse = s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(resource.name)
                .contentType(resource.contentType)
                .build()
        )
        logger.info { "Initiating multipart upload of $bucketName / ${resource.name}" }
        val uploadId =
            createMultipartResponse.uploadId() ?: throw IllegalStateException("Failed to create s3 multipart upload id")
        val uploadedChunks = bufferAndUpload(uploadId)
            .toList()
        s3Client.completeMultipartUpload(
            CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(resource.name)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                    .parts(uploadedChunks)
                    .build())
                .build()
        )
        logger.info { "Completed upload of $bucketName / ${resource.name}" }
    }

    private fun bufferAndUpload(uploadId: String): Sequence<CompletedPart> {
        val buffer = StringBuilder()
        val chunks = sequence<String> {

            for (line in resource.sequence) {
                buffer.append(line)

                if (buffer.length >= resource.bufferSizeInBytes) {
                    yield(buffer.toString())
                    buffer.clear()
                }
            }

            // Upload any remaining data
            if (buffer.isNotEmpty()) {
                yield(buffer.toString())
            }
        }

        val partNumber = AtomicInteger(1)
        return chunks.map { chunk ->
            uploadPart(uploadId, chunk, partNumber)
        }
    }

    private fun uploadPart(uploadId: String, chunk: String, partNumber: AtomicInteger): CompletedPart {
        val thisPartNumber = partNumber.getAndIncrement()
        val bytes = chunk.toByteArray()
        logger.info { "Uploading part $thisPartNumber of $bucketName / ${resource.name} (${bytes.size} bytes)" }
        val completedUpload = s3Client.uploadPart(
            UploadPartRequest.builder()
                .uploadId(uploadId)
                .bucket(bucketName)
                .key(resource.name)
                .partNumber(thisPartNumber)
                .build(),
            RequestBody.fromBytes(bytes)
        )
        return CompletedPart.builder()
            .partNumber(thisPartNumber)
            .eTag(completedUpload.eTag())
            .build()
    }
}