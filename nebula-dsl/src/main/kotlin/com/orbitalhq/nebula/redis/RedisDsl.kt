package com.orbitalhq.nebula.redis

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import mu.KLogger
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

interface RedisDsl : InfraDsl {
    fun redis(imageName: String = "redis:7-alpine",
              componentName: ComponentName = "redis",
              dsl: RedisBuilder.(KLogger) -> Unit): RedisExecutor {
        val builder = RedisBuilder(imageName, componentName)
        builder.dsl(logger)
        return this.add(RedisExecutor(builder.build(), listOf(logger.name)))
    }
}

class RedisBuilder(
    private val imageName: String,
    private val componentName: ComponentName,
) {
    fun build(): RedisConfig = RedisConfig(imageName, componentName)
}

data class RedisConfig(
    val imageName: String,
    val componentName: ComponentName,
)
