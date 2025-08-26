package com.orbitalhq.nebula.utils

fun String.updateHostReferences(containerHost:String, publicHost: String):String {
    return this.replace(containerHost, publicHost)
}