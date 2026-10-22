import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    // Repli EPG chaine par chaine (concurrence bornee, pauses, annulation). Deja embarque par :app.
    api(libs.kotlinx.coroutines.core)
    // Pur Java, fonctionne sur JVM et Android ; Ed25519 en temps constant (cf. FixturesVerifier).
    implementation(libs.tink.android)
    // API XmlPull : fournie par le framework sur Android (ne pas l'embarquer dans l'APK).
    compileOnly(libs.kxml2)

    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Le test d'integration lit ../../cache (donnees reelles locales, jamais committees).
    // Surchargeable (-Pfootballrama.cacheDir=...) pour un worktree git sans cache/.
    systemProperty(
        "footballrama.cacheDir",
        providers.gradleProperty("footballrama.cacheDir").orNull ?: rootProject.projectDir.resolve("../cache").absolutePath,
    )
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
