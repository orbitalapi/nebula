package com.orbitalhq.nebula.taxi

import com.fasterxml.jackson.annotation.JsonIgnore
import com.orbitalhq.PackageIdentifier
import com.orbitalhq.PackageMetadata
import com.orbitalhq.SourcePackage
import com.orbitalhq.VersionedSource
import com.orbitalhq.nebula.InfraDsl
import lang.taxi.packages.SourcesType

interface TaxiPublisherDsl : InfraDsl {
    fun taxiPublisher(url: String, packageUri: String, dsl: TaxiPackageBuilder.() -> Unit): TaxiPublishingExecutor {
        val builder = TaxiPackageBuilder(url, packageUri)
        builder.dsl()
        return this.add(TaxiPublishingExecutor(builder.build()))
    }
}

class TaxiPackageBuilder(private val url: String, private val packageId: String) {

    private val taxiSources = mutableMapOf<String, String>()
    private val additionalSources = mutableMapOf<SourcesType, MutableMap<String, String>>()

    fun build(): TaxiPublisherConfig {
        val sourcePackage = SourcePackage(
            packageMetadata = PackageMetadata.from(PackageIdentifier.fromId(packageId)),
            sources = taxiSources.map { (path, content) -> VersionedSource.unversioned(path, content) },
            additionalSources = additionalSources.map { (sourcesType, sources) ->
                sourcesType to sources.map { (path, content) -> VersionedSource.unversioned(path, content) }
            }.toMap(),
        )
        return TaxiPublisherConfig(
            url, sourcePackage
        )
    }

    fun taxi(path: String, source: TaxiPackageBuilder.() -> String) {
        val taxiSrc = source()
        taxiSources[path] = taxiSrc
    }

    fun additionalSource(sourceKind: String, pathAndSource: Pair<String,String>) {
        val sources = additionalSources.getOrPut(sourceKind) { mutableMapOf() }
        val(path,source) = pathAndSource
        sources[path] = source
    }
    fun additionalSource(sourceKind: String, path: String, source: TaxiPackageBuilder.() -> String) {
        val sources = additionalSources.getOrPut(sourceKind) { mutableMapOf() }
        sources[path] = source()
    }
}

data class TaxiPublisherConfig(
    val url: String,
    // Don't include the entire source package when serializing the config.
    // The config returned from Nebula gets loaded into Orbital as a HOCON file, to then publish into the env
    // as variables. Including the entire source is overkill, but also breaks HOCON parsing on Orbital.
    @JsonIgnore
    val sourcePackage: SourcePackage
) {

}