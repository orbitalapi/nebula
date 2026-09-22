package com.orbitalhq.nebula.resources

import com.orbitalhq.nebula.core.ResourceContent
import com.orbitalhq.nebula.core.StackBundle
import com.orbitalhq.nebula.core.StackBundleLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * Unpacking is where a hostile bundle would land on disk, so the containment
 * checks run here too - before a single file is written, not only when a running
 * stack reads one back.
 */
class StackBundleUnpackerTest : DescribeSpec({

    fun unpackerIn(): Pair<StackBundleUnpacker, Path> {
        val base = Files.createTempDirectory("unpacker-test").toRealPath()
        return StackBundleUnpacker(base) to base
    }

    describe("unpacking a bundle") {
        it("writes text resources, including nested ones") {
            val (unpacker, _) = unpackerIn()
            val resources = unpacker.unpack(
                "films", StackBundle(
                    script = "stack {}",
                    resources = mapOf(
                        "sales.csv" to ResourceContent.text("Title,Tickets"),
                        "specs/orders.yaml" to ResourceContent.text("openapi: 3.0.0")
                    )
                )
            )

            resources.names.shouldContainExactly("sales.csv", "specs/orders.yaml")
            resources.readText("specs/orders.yaml") shouldBe "openapi: 3.0.0"
        }

        it("writes binary resources from their base64 encoding") {
            val (unpacker, _) = unpackerIn()
            val bytes = byteArrayOf(0, 1, 2, -1, -2)
            val resources = unpacker.unpack(
                "films", StackBundle("stack {}", mapOf("blob.bin" to ResourceContent.binary(bytes)))
            )

            resources.readBytes("blob.bin").toList() shouldBe bytes.toList()
        }

        it("gives each submission a directory of its own") {
            val (unpacker, base) = unpackerIn()
            val bundle = StackBundle("stack {}", mapOf("a.txt" to ResourceContent.text("a")))

            val first = unpacker.unpack("films", bundle)
            val second = unpacker.unpack("films", bundle)

            (first.root == second.root) shouldBe false
            base.listDirectoryEntries().size shouldBe 2
        }

        it("needs no directory for a bundle with no resources") {
            val (unpacker, base) = unpackerIn()
            val resources = unpacker.unpack("films", StackBundle.scriptOnly("stack {}"))

            resources.hasBundle shouldBe false
            base.listDirectoryEntries().shouldBeEmpty()
        }

        it("deletes the unpacked directory when the stack goes away") {
            val (unpacker, _) = unpackerIn()
            val resources = unpacker.unpack("films", StackBundle("stack {}", mapOf("a.txt" to ResourceContent.text("a"))))
            val root = resources.root!!

            resources.delete()

            root.exists() shouldBe false
        }
    }

    describe("refusing a bundle that would write outside its directory") {
        // This is zip-slip: the attack is in the entry name, and the file lands
        // wherever that name points unless the server checks first.
        listOf(
            "../escaped.txt",
            "nested/../../escaped.txt",
            "/etc/cron.d/evil",
            "C:\\Windows\\evil.txt"
        ).forEach { hostilePath ->
            it("rejects the entry '$hostilePath'") {
                val (unpacker, base) = unpackerIn()
                shouldThrow<InvalidStackBundleException> {
                    unpacker.unpack("films", StackBundle("stack {}", mapOf(hostilePath to ResourceContent.text("pwned"))))
                }
                // Nothing at all was written - not the hostile entry, and not the
                // directory that would have held it.
                base.listDirectoryEntries().shouldBeEmpty()
                base.parent.resolve("escaped.txt").exists() shouldBe false
            }
        }

        it("rejects a bundle where one entry is hostile, leaving none of it on disk") {
            val (unpacker, base) = unpackerIn()
            shouldThrow<InvalidStackBundleException> {
                unpacker.unpack(
                    "films", StackBundle(
                        "stack {}", mapOf(
                            "fine.csv" to ResourceContent.text("ok"),
                            "../escaped.txt" to ResourceContent.text("pwned")
                        )
                    )
                )
            }
            base.listDirectoryEntries().shouldBeEmpty()
        }
    }

    describe("size limits") {
        it("rejects a resource over the per-file limit, naming the file and the limit") {
            val (unpacker, base) = unpackerIn()
            val tooBig = "x".repeat((StackBundleLimits.MAX_RESOURCE_BYTES + 1).toInt())

            val error = shouldThrow<InvalidStackBundleException> {
                unpacker.unpack("films", StackBundle("stack {}", mapOf("huge.csv" to ResourceContent.text(tooBig))))
            }

            error.message!!.shouldContain("huge.csv")
            error.message!!.shouldContain(StackBundleLimits.MAX_RESOURCE_BYTES.toString())
            base.listDirectoryEntries().shouldBeEmpty()
        }

        it("rejects a bundle with too many files") {
            val (unpacker, _) = unpackerIn()
            val resources = (0..StackBundleLimits.MAX_RESOURCE_COUNT)
                .associate { "file-$it.txt" to ResourceContent.text("x") }

            shouldThrow<InvalidStackBundleException> {
                unpacker.unpack("films", StackBundle("stack {}", resources))
            }.message!!.shouldContain(StackBundleLimits.MAX_RESOURCE_COUNT.toString())
        }

        it("rejects content flagged as base64 that isn't") {
            val (unpacker, _) = unpackerIn()
            shouldThrow<InvalidStackBundleException> {
                unpacker.unpack(
                    "films",
                    StackBundle("stack {}", mapOf("blob.bin" to ResourceContent("not ** base64", com.orbitalhq.nebula.core.ResourceEncoding.BASE64)))
                )
            }.message!!.shouldContain("blob.bin")
        }
    }

    describe("fingerprints") {
        it("is the same for the same files whatever order they arrive in") {
            val a = mapOf("a.txt" to ResourceContent.text("1"), "b.txt" to ResourceContent.text("2"))
            val b = mapOf("b.txt" to ResourceContent.text("2"), "a.txt" to ResourceContent.text("1"))

            StackBundleUnpacker.fingerprintOf(a) shouldBe StackBundleUnpacker.fingerprintOf(b)
        }

        it("differs when a file's content changes") {
            val before = StackBundleUnpacker.fingerprintOf(mapOf("a.txt" to ResourceContent.text("1")))
            val after = StackBundleUnpacker.fingerprintOf(mapOf("a.txt" to ResourceContent.text("2")))

            (before == after) shouldBe false
        }

        it("is empty when there are no resources, so script-only submissions dedupe as before") {
            StackBundleUnpacker.fingerprintOf(emptyMap()) shouldBe ""
        }
    }
})
