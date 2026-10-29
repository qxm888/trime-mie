/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNode
import com.osfans.trime.util.int
import com.osfans.trime.util.string
import com.osfans.trime.util.yamlNode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads a list of strings, treating everything that is not a list as an empty
 * one: hand-written themes also write a single value or no value at all in
 * these places, and both used to be read as an empty list.
 *
 * The descriptor is contextual on purpose: kaml rejects an empty value for a
 * non-nullable descriptor of a structure kind before a serializer is asked,
 * while a contextual descriptor hands the node over as it is.
 */
@OptIn(InternalSerializationApi::class)
object LenientStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = buildSerialDescriptor("LenientStringList", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: List<String>) = encoder.encodeSerializableValue(ListSerializer(String.serializer()),value)

    override fun deserialize(decoder: Decoder): List<String> = if(decoder is JsonDecoder)
        (decoder.decodeJsonElement() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        else decoder.sequenceOrEmpty { it.string }
}

/** [LenientStringListSerializer] for the numeric lists a toolbar button declares. */
@OptIn(InternalSerializationApi::class)
object LenientIntListSerializer : KSerializer<List<Int>> {
    override val descriptor: SerialDescriptor = buildSerialDescriptor("LenientIntList", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: List<Int>) = encoder.encodeSerializableValue(ListSerializer(Int.serializer()),value)

    override fun deserialize(decoder: Decoder): List<Int> = if(decoder is JsonDecoder)
        (decoder.decodeJsonElement() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        else decoder.sequenceOrEmpty { it.int }
}

private inline fun <T> Decoder.sequenceOrEmpty(convert: (YamlNode) -> T?): List<T> = (yamlNode() as? YamlList)?.items?.mapNotNull(convert) ?: emptyList()
