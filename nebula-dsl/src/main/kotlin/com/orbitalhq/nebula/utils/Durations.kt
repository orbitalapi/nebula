package com.orbitalhq.nebula.utils

import kotlin.time.Duration

fun String.duration():Duration {
    return Duration.parse(this)
}