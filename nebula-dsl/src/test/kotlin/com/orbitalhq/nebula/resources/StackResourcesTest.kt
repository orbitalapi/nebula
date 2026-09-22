package com.orbitalhq.nebula.resources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The containment rules that keep a stack reading only the files that were
 * shipped with it.
 *
 * A Nebula server runs stacks submitted by any project connected to Orbital, and
 * its own filesystem holds credentials that have nothing to do with those
 * projects. Everything below is a way someone might try to walk out of the
 * bundle directory and reach one.
 */
class StackResourcesTest : DescribeSpec({

    /** A bundle directory with a nested file, plus a "secret" living outside it. */
    fun bundleWithSecretOutside(): Pair<StackResources, Path> {
        val tempDir = Files.createTempDirectory("stack-resources-test")
        val bundle = tempDir.resolve("bundle").createDirectories()
        bundle.resolve("sales.csv").writeText("Title,Tickets\nA New Hope,249\n")
        bundle.resolve("specs").createDirectories()
        bundle.resolve("specs/orders.yaml").writeText("openapi: 3.0.0")

        val secrets = tempDir.resolve("secrets").createDirectories()
        secrets.resolve("id_rsa").writeText("PRIVATE KEY")

        return StackResources.at(bundle) to tempDir
    }

    describe("reading resources that were shipped with the stack") {
        it("reads a file at the root of the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            resources.readText("sales.csv") shouldContain "A New Hope"
        }

        it("reads a file in a subdirectory") {
            val (resources, _) = bundleWithSecretOutside()
            resources.readText("specs/orders.yaml") shouldBe "openapi: 3.0.0"
        }

        it("lists what the stack shipped with") {
            val (resources, _) = bundleWithSecretOutside()
            resources.names.shouldContainExactly("sales.csv", "specs/orders.yaml")
        }

        it("normalises a path that stays inside the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            // specs/../sales.csv resolves back inside the bundle, but we reject it
            // anyway: allowing ".." at all means relying on normalisation being
            // right everywhere, and a legitimate stack never needs it.
            shouldThrow<StackResourceException> {
                resources.readText("specs/../sales.csv")
            }.message!!.shouldContain("'..' segment")
        }

        it("reports what is available when a resource is missing") {
            val (resources, _) = bundleWithSecretOutside()
            val error = shouldThrow<StackResourceException> { resources.readText("nope.csv") }
            error.message!!.shouldContain("No resource named 'nope.csv'")
            error.message!!.shouldContain("sales.csv")
        }
    }

    describe("refusing to read outside the bundle") {
        it("rejects an absolute path") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> {
                resources.readText("/etc/ssh/id_rsa")
            }.message!!.shouldContain("is absolute")
        }

        it("rejects a relative path that climbs out with ..") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> {
                resources.readText("../secrets/id_rsa")
            }.message!!.shouldContain("'..' segment")
        }

        it("rejects a deeply nested climb that ends outside the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> {
                resources.readText("specs/../../secrets/id_rsa")
            }.message!!.shouldContain("'..' segment")
        }

        it("rejects a Windows-style absolute path, even on a unix host") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> {
                resources.readText("C:\\Users\\admin\\id_rsa")
            }.message!!.shouldContain("is absolute")
        }

        it("rejects a blank path") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> { resources.readText("   ") }
        }

        it("refuses to follow a symlink planted inside the bundle") {
            val (resources, tempDir) = bundleWithSecretOutside()
            val root = resources.root!!
            // Nothing in the path string is suspicious - the escape is entirely in
            // the filesystem, which is why normalising the string is not enough.
            Files.createSymbolicLink(root.resolve("innocent.pem"), tempDir.resolve("secrets/id_rsa"))

            shouldThrow<StackResourceException> {
                resources.readText("innocent.pem")
            }.message!!.shouldContain("outside this stack's resource bundle")
        }

        it("refuses to follow a symlinked directory that leads out of the bundle") {
            val (resources, tempDir) = bundleWithSecretOutside()
            val root = resources.root!!
            Files.createSymbolicLink(root.resolve("elsewhere"), tempDir.resolve("secrets"))

            shouldThrow<StackResourceException> {
                resources.readText("elsewhere/id_rsa")
            }.message!!.shouldContain("outside this stack's resource bundle")
        }

        it("still reads a symlink that stays inside the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            val root = resources.root!!
            Files.createSymbolicLink(root.resolve("latest.csv"), root.resolve("sales.csv"))

            resources.readText("latest.csv") shouldContain "A New Hope"
        }
    }

    describe("a stack submitted without a bundle") {
        it("explains why there is nothing to read") {
            shouldThrow<StackResourceException> {
                StackResources.NONE.readText("sales.csv")
            }.message!!.shouldContain("submitted without a resource bundle")
        }

        it("leaves file paths given to providers exactly as they were") {
            // Stacks that predate bundles pass paths on the Nebula server's own
            // filesystem; without a bundle nothing changes for them.
            StackResources.NONE.resolveFilePath("/data/sales.csv") shouldBe "/data/sales.csv"
            StackResources.NONE.resolveFilePath("sales.csv") shouldBe "sales.csv"
        }
    }

    describe("resolving paths handed to providers") {
        it("resolves a relative path through the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            resources.resolveFilePath("specs/orders.yaml") shouldBe
                resources.root!!.resolve("specs/orders.yaml").toString()
        }

        it("leaves an absolute path pointing at the host, as it always has") {
            val (resources, _) = bundleWithSecretOutside()
            resources.resolveFilePath("/mnt/data/sales.csv") shouldBe "/mnt/data/sales.csv"
        }

        it("rejects a relative path that escapes the bundle") {
            val (resources, _) = bundleWithSecretOutside()
            shouldThrow<StackResourceException> { resources.resolveFilePath("../secrets/id_rsa") }
        }
    }

    describe("fingerprinting") {
        it("changes when a resource's content changes") {
            val dir = Files.createTempDirectory("fingerprint-test")
            dir.resolve("data.csv").writeText("a")
            val before = StackResources.at(dir).fingerprint

            dir.resolve("data.csv").writeText("b")
            StackResources.at(dir).fingerprint shouldBe StackResources.at(dir).fingerprint
            (StackResources.at(dir).fingerprint == before) shouldBe false
        }
    }
})
