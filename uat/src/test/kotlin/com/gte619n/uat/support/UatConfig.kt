package com.gte619n.uat.support

/** Central place for the run's endpoints + toggles, injected as -D system
 *  properties by uat/build.gradle.kts (which uat.sh parameterizes). */
object UatConfig {
    val webBaseUrl: String = prop("uat.webBaseUrl", "http://localhost:3000")
    val backendBaseUrl: String = prop("uat.backendBaseUrl", "http://localhost:8080")
    val firestoreEmulatorHost: String = prop("uat.firestoreEmulatorHost", "127.0.0.1:8081")
    val firestoreProjectId: String = prop("uat.firestoreProjectId", "demo-uat")
    val headless: Boolean = prop("uat.headless", "true").toBoolean()

    private fun prop(key: String, default: String): String =
        System.getProperty(key)?.takeIf { it.isNotBlank() } ?: default
}
