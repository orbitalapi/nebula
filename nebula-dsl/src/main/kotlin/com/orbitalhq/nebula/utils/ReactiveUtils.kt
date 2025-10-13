package com.orbitalhq.nebula.utils

import mu.KLogger
import reactor.core.publisher.Sinks

fun Sinks.EmitResult.logIfFailed(message: String, logger: KLogger) {
    if (this.isFailure) {
        logger.warn { "$message - ${this.name}" }
    }
}
