package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Hand-rolled polymorphic [JsonAdapter] for [EquipmentSpec].
 *
 * We can't use moshi-adapters' `PolymorphicJsonAdapterFactory` because that
 * artifact (`com.squareup.moshi:moshi-adapters`) is not on core-data's
 * classpath and we may not edit build files. Instead this factory reads the
 * `specSchema` discriminator manually and delegates each subtype to a
 * delegate adapter obtained from the same [Moshi] instance.
 *
 * Forward-compat: an unknown / missing discriminator decodes to
 * [EquipmentSpec.Bodyweight] (matches the spec's `withDefaultValue`). The
 * discriminator is written back out alongside each subtype's own fields.
 */
class EquipmentSpecJsonAdapter(moshi: Moshi) : JsonAdapter<EquipmentSpec>() {

    private val discriminator = "specSchema"

    private val selectorized = moshi.adapter(EquipmentSpec.Selectorized::class.java)
    private val plateLoaded = moshi.adapter(EquipmentSpec.PlateLoaded::class.java)
    private val cable = moshi.adapter(EquipmentSpec.Cable::class.java)
    private val cardio = moshi.adapter(EquipmentSpec.Cardio::class.java)
    private val weightSet = moshi.adapter(EquipmentSpec.WeightSet::class.java)
    private val mapAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    override fun fromJson(reader: JsonReader): EquipmentSpec? {
        if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull()
        // Read the whole object generically so we can peek the discriminator,
        // then re-decode the body with the right subtype adapter.
        @Suppress("UNCHECKED_CAST")
        val raw = reader.readJsonValue() as? Map<String, Any?>
            ?: return EquipmentSpec.Bodyweight
        return decode(raw)
    }

    /** Decode from an already-parsed map (used by both wire decode and override hydration helpers). */
    fun decode(raw: Map<String, Any?>): EquipmentSpec {
        val tag = (raw[discriminator] as? String)?.uppercase()?.let { name ->
            SpecSchemaTag.entries.firstOrNull { it.name == name }
        } ?: return EquipmentSpec.Bodyweight
        val body = raw.filterKeys { it != discriminator }
        return when (tag) {
            SpecSchemaTag.SELECTORIZED -> selectorized.fromJsonValue(body)
            SpecSchemaTag.PLATE_LOADED -> plateLoaded.fromJsonValue(body)
            SpecSchemaTag.BODYWEIGHT -> EquipmentSpec.Bodyweight
            SpecSchemaTag.CABLE -> cable.fromJsonValue(body)
            SpecSchemaTag.CARDIO -> cardio.fromJsonValue(body)
            SpecSchemaTag.WEIGHT_SET -> weightSet.fromJsonValue(body)
        } ?: EquipmentSpec.Bodyweight
    }

    override fun toJson(writer: JsonWriter, value: EquipmentSpec?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        val body: Map<String, Any?> = when (value) {
            is EquipmentSpec.Selectorized -> selectorized.toJsonValue(value).asMap()
            is EquipmentSpec.PlateLoaded -> plateLoaded.toJsonValue(value).asMap()
            EquipmentSpec.Bodyweight -> emptyMap()
            is EquipmentSpec.Cable -> cable.toJsonValue(value).asMap()
            is EquipmentSpec.Cardio -> cardio.toJsonValue(value).asMap()
            is EquipmentSpec.WeightSet -> weightSet.toJsonValue(value).asMap()
        }
        val out = LinkedHashMap<String, Any?>()
        out[discriminator] = value.tag.name
        out.putAll(body)
        mapAdapter.toJson(writer, out)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> =
        (this as? Map<String, Any?>) ?: emptyMap()

    companion object {
        /**
         * A [JsonAdapter.Factory] usable with `Moshi.Builder().add(...)`.
         * Only claims the exact [EquipmentSpec] base type so subtype adapters
         * resolve normally.
         */
        val FACTORY: Factory = Factory { type: Type, _, moshi: Moshi ->
            if (Types.getRawType(type) == EquipmentSpec::class.java) {
                EquipmentSpecJsonAdapter(moshi)
            } else {
                null
            }
        }
    }
}
