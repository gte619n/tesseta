# Project-level ProGuard/R8 rules.
#
# R8 is enabled for the release build (isMinifyEnabled = true in
# app/build.gradle.kts). Most dependencies (Compose, Hilt, Retrofit, OkHttp,
# Room, Firebase, ML Kit, Coil) ship *consumer* ProGuard rules that AGP applies
# automatically — they are NOT repeated here. Only add keeps for reflective /
# codegen paths those rules don't fully cover, and only when a release smoke
# test surfaces a concrete ClassNotFound / missing-adapter / UnsatisfiedLinkError.
# Do not pre-emptively over-keep.

# --- Moshi -----------------------------------------------------------------
# This project serializes its wire/DTO/domain models with the *reflective*
# adapter (KotlinJsonAdapterFactory in core-data NetworkModule), NOT codegen —
# the models are plain Kotlin data classes / enums / sealed classes with NO
# @JsonClass annotation. Under R8 full-mode, a class that is only ever
# instantiated via reflection looks uninstantiated to R8, so its constructor is
# stripped and the class is marked abstract; Moshi then fails at runtime with
# "Cannot serialize abstract class ...". (First casualty observed: the dashboard
# RecentActivityCache adapter for List<RecentActivityEntry> in
# HealthFitnessApp.onCreate.) Keep the model namespaces below so R8 leaves their
# constructors/members intact. Kotlin @Metadata (which R8 rewrites consistently)
# is what KotlinJsonAdapterFactory reads for property names, so obfuscation is
# fine as long as the classes themselves survive.
#
# When you add a NEW package of Moshi-serialized DTOs/wire models, add it here.
#
# core-domain is the JSON wire contract — a pure model module, kept wholesale.
-keep class com.gte619n.healthfitness.domain.** { *; }
# core-data: request/response bodies, DTOs, and persisted cache docs (reflective).
-keep class com.gte619n.healthfitness.data.** { *; }
# core-chat: SSE stream payloads built via runtime moshi.adapter<...>().
-keep class com.gte619n.healthfitness.core.chat.** { *; }

# Codegen Moshi (should any class adopt @JsonClass(generateAdapter = true)) and
# hand-rolled adapters (e.g. EquipmentSpecJsonAdapter): keep generated adapters
# and the @Json field mapping.
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
# Kotlin reflection metadata (Moshi's KotlinJsonAdapter needs it).
-keep class kotlin.Metadata { *; }

# --- SQLCipher -------------------------------------------------------------
# JNI bridge classes are referenced from native code, invisible to R8's call
# graph; keep both the legacy net.sqlcipher and the net.zetetic namespaces.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# --- Retrofit / OkHttp -----------------------------------------------------
# Preserve generic signatures (suspend-fun return types), annotations, and
# inner/enclosing-class metadata that Retrofit reflects over. Belt-and-suspenders
# on top of the artifacts' own consumer rules.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
