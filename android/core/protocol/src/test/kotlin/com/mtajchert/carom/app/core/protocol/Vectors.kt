package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Loads protocol/vectors/ (every .json file), passed in by Gradle as the `carom.vectors.dir` system property. */
object Vectors {
    val dir: File by lazy {
        val path = System.getProperty("carom.vectors.dir")
            ?: error("carom.vectors.dir is not set; run the tests through Gradle")
        File(path).also { check(it.isDirectory) { "vectors directory not found: $it" } }
    }

    fun files(): Set<String> = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }.toSet()

    fun load(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject
}

// Small accessors that fail loudly, since a missing vector field is a test bug.
fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject
fun JsonElement.arr(key: String): JsonArray = jsonObject.getValue(key).jsonArray
fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
fun JsonElement.strOrNull(key: String): String? =
    jsonObject[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
fun JsonElement.num(key: String): Long = jsonObject.getValue(key).jsonPrimitive.long
fun JsonElement.numOrNull(key: String): Long? = jsonObject[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
fun JsonElement.hex(key: String): ByteArray = str(key).hexToBytes()
fun JsonElement.el(key: String): JsonElement = jsonObject.getValue(key)
val JsonElement.name: String get() = strOrNull("name") ?: strOrNull("reason") ?: toString().take(80)

fun JsonObject.without(key: String): JsonObject = JsonObject(this - key)

fun jsonString(value: String) = JsonPrimitive(value)

/** The vectors' deterministic byte stream: block i = SHA-256("carom/v1/test-vectors/" || label || "/" || u32be(i)). */
class SeededRng(label: String) : Rng {
    private val prefix = ("carom/v1/test-vectors/$label/").utf8()
    private var counter = 0L
    private var buffer = ByteArray(0)

    override fun bytes(length: Int): ByteArray {
        while (buffer.size < length) buffer = concat(buffer, Crypto.sha256(concat(prefix, u32be(counter++))))
        val out = buffer.copyOf(length)
        buffer = buffer.copyOfRange(length, buffer.size)
        return out
    }
}
