package com.orbitalhq.nebula.io.protobuf

import com.squareup.wire.schema.Schema
import okio.Path.Companion.toPath

fun protobufSchema(source: String):Schema {
    return protobufSchema(mapOf("source.proto" to source))
}
fun protobufSchema(files: Map<String,String>): Schema {
    return buildSchema {
        files.forEach { (path, proto) ->
            add(path.toPath(), proto)
        }
    }
}
