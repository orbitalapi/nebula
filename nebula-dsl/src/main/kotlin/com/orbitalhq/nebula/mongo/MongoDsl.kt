package com.orbitalhq.nebula.mongo

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName

interface MongoDsl : InfraDsl {
    fun mongo(imageName: String = "mongo:7.0.16", databaseName: String, componentName: ComponentName = "mongo", dsl: MongoBuilder.() -> Unit): MongoExecutor {
        val builder = MongoBuilder(imageName, componentName, databaseName)
        builder.dsl()
        return this.add(MongoExecutor(builder.build()))
    }
}

class MongoBuilder(private val imageName: String, private val componentName: ComponentName, private val databaseName: String)  {
    private val collections = mutableListOf<CollectionConfig>()

    fun collection(name: String, data: List<Map<String,Any>> = emptyList()) {
        collections.add(CollectionConfig(name, data))
    }

    fun build(): MongoConfig = MongoConfig(
        imageName,
        componentName,
        databaseName,
        collections
    )
}

data class CollectionConfig(
    val name: String,
    val data: List<Map<String,Any>> = emptyList()
)

data class MongoConfig(
    val imageName: String,
    val componentName: ComponentName,
    val databaseName: String,
    val collections: List<CollectionConfig>,
)