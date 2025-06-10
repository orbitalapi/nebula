package com.orbitalhq.nebula.io.avro

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream

fun avroSchema(source: String): Schema {
    return Schema.Parser().parse(source)
}

fun serializeToBinaryAvro(record: GenericRecord, schema: Schema): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(outputStream, null)
    val writer = GenericDatumWriter<GenericRecord>(schema)

    writer.write(record, encoder)
    encoder.flush()

    return outputStream.toByteArray()
}

fun deserializeFromBinaryAvro(avroBytes: ByteArray, schema: Schema): GenericRecord {
    val decoder = DecoderFactory.get().binaryDecoder(avroBytes, null)
    val reader = GenericDatumReader<GenericRecord>(schema)
    return reader.read(null, decoder)
}

fun convertMapToAvroGenericRecord(map: Map<String, Any?>, schema: Schema): GenericRecord {
    // Create a new GenericRecord
    val record = GenericData.Record(schema)

    // Use Avro's built-in conversion from Object to Avro types
    schema.fields.forEach { field ->
        val fieldName = field.name()
        val value = map[fieldName]

        // Let Avro handle the type conversion automatically
        val convertedValue = convertToAvroType(value, field.schema())
        record.put(fieldName, convertedValue)
    }

    return record
}
/**
 * Convert a value to the appropriate Avro type
 * This handles the heavy lifting of type conversion
 */
private fun convertToAvroType(value: Any?, schema: Schema): Any? {
    return when {
        value == null -> null
        schema.isUnion -> {
            // For union types, find the appropriate non-null type
            val nonNullSchema = schema.types.find { it.type != Schema.Type.NULL }
            if (nonNullSchema != null) {
                convertToAvroType(value, nonNullSchema)
            } else {
                null
            }
        }
        schema.type == Schema.Type.LONG && value is Number -> value.toLong()
        schema.type == Schema.Type.INT && value is Number -> value.toInt()
        schema.type == Schema.Type.FLOAT && value is Number -> value.toFloat()
        schema.type == Schema.Type.DOUBLE && value is Number -> value.toDouble()
        schema.type == Schema.Type.STRING -> value.toString()
        schema.type == Schema.Type.BOOLEAN && value is Boolean -> value
        schema.type == Schema.Type.ARRAY && value is List<*> -> {
            // Convert list items to appropriate Avro types
            val itemSchema = schema.elementType
            value.map { convertToAvroType(it, itemSchema) }
        }
        schema.type == Schema.Type.ENUM -> {
            // Convert to GenericData.EnumSymbol
            val enumValue = value.toString()
            // Validate that the enum value is valid for this schema
            if (schema.enumSymbols.contains(enumValue)) {
                GenericData.EnumSymbol(schema, enumValue)
            } else {
                throw IllegalArgumentException("Invalid enum value '$enumValue' for enum ${schema.name}. Valid values: ${schema.enumSymbols}")
            }
        }
        schema.type == Schema.Type.RECORD  && value is Map<*, *> -> {
            // Convert map values to appropriate Avro types
            convertMapToAvroGenericRecord(value as Map<String,Any>, schema)
        }
        schema.type == Schema.Type.MAP && value is Map<*, *> -> {
            // Convert map values to appropriate Avro types
            val valueSchema = schema.valueType
            value.mapValues { (_, v) -> convertToAvroType(v, valueSchema) }
        }
        else -> value // Let Avro handle other types
    }
}