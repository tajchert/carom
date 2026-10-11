package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `action` of `notification.action@1` (SPEC V2 §3.4): the string `"tap"`, `"answer"` or
 * `"decline"`, or the object `{index, title, reply_text?}`. Anything else fails to decode.
 */
object ActionRefSerializer : KSerializer<MessageBodies.ActionRef> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.mtajchert.carom.app.core.protocol.ActionRef")

    override fun serialize(encoder: Encoder, value: MessageBodies.ActionRef) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ActionRef is JSON only")
        json.encodeJsonElement(
            when (value) {
                MessageBodies.ActionRef.Tap -> JsonPrimitive("tap")
                MessageBodies.ActionRef.Answer -> JsonPrimitive("answer")
                MessageBodies.ActionRef.Decline -> JsonPrimitive("decline")
                is MessageBodies.ActionRef.Button -> buildJsonObject {
                    put("index", value.index)
                    put("title", value.title)
                    value.replyText?.let { put("reply_text", it) }
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): MessageBodies.ActionRef {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ActionRef is JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> when (element.stringOrNull()) {
                "tap" -> MessageBodies.ActionRef.Tap
                "answer" -> MessageBodies.ActionRef.Answer
                "decline" -> MessageBodies.ActionRef.Decline
                else -> throw SerializationException("unknown action")
            }
            is JsonObject -> {
                val index = element["index"].integerOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
                    ?: throw SerializationException("bad action index")
                val title = element["title"].stringOrNull() ?: throw SerializationException("bad action title")
                val replyText = if ("reply_text" in element) {
                    element["reply_text"].stringOrNull() ?: throw SerializationException("bad reply_text")
                } else {
                    null
                }
                MessageBodies.ActionRef.Button(index.toInt(), title, replyText)
            }
            else -> throw SerializationException("bad action")
        }
    }
}
