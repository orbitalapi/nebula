package com.orbitalhq.nebula

interface InfrastructureComponent {
    fun start()
    fun stop()
}
data class ContainerInfo(
    val containerId: String,
    val imageName: String,
    val containerName: String
)

data class NetworkInfo(
    val host: String,
    val ports: Map<String, Int>
)