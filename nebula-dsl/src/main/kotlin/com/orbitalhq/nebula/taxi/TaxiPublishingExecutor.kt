package com.orbitalhq.nebula.taxi

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.schema.publisher.SchemaPublisherService
import com.orbitalhq.schema.publisher.cli.SimpleHttpPublisher
import com.orbitalhq.schema.publisher.http.HttpSchemaPublisher
import reactor.core.publisher.Flux
import java.net.URI
import java.time.Duration
import kotlin.concurrent.thread

class TaxiPublishingExecutor(private val config: TaxiPublisherConfig) : InfrastructureComponent<TaxiPublisherConfig> {
    override val name: ComponentName = "Taxi publisher"
    override val type: ComponentType = "taxi-publisher"
    private val eventSource = ComponentLifecycleEventSource()
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }

    override var componentInfo: ComponentInfo<TaxiPublisherConfig>? = null
        private set


    override fun start(nebulaConfig: NebulaConfig): ComponentInfo<TaxiPublisherConfig> {
        eventSource.starting()

        // Publish
        val publisher = SchemaPublisherService(
            "nebula",
            HttpSchemaPublisher(
                SimpleHttpPublisher(
                    URI.create(config.url)
                ),
                Duration.ofSeconds(5)
            )
        )
        thread {
            publisher.publish(config.sourcePackage).subscribe()
        }



        componentInfo = ComponentInfo(
            container = null,
            componentConfig = config,
            type = type,
            name = name,
            id = id
        )
        eventSource.running()

        return componentInfo!!
    }

    override fun stop() {
        eventSource.stopped()
    }

}