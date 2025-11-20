package com.orbitalhq.nebula.hazelcast

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import mu.KLogger
import mu.KotlinLogging
import javax.management.monitor.StringMonitor

private val logger = KotlinLogging.logger {}

interface HazelcastDsl : InfraDsl {
    fun hazelcast(imageName: String = "hazelcast/hazelcast:5",
                  componentName:ComponentName = "hazelcast",
                  dsl: HazelcastBuilder.(KLogger) -> Unit): HazelcastExecutor {
        val builder = HazelcastBuilder(imageName, componentName)
        builder.dsl(logger)
        return this.add(HazelcastExecutor(builder.build(), listOf(logger.name)))
    }
}

class HazelcastBuilder(
    private val imageName: String,
    private val componentName: ComponentName,
) {
    fun build():HazelcastConfig = HazelcastConfig(imageName, componentName)
}

data class HazelcastConfig(
    val imageName: String,
    val componentName: ComponentName,
)