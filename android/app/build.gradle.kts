import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Valeurs de build : local.properties (non committe) > -P / gradle.properties (placeholders).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun buildValue(name: String): String =
    (localProps.getProperty(name) ?: providers.gradleProperty(name).orNull ?: "").trim()

fun String.asJavaStringLiteral(): String {
    // Les valeurs attendues sont une URL et du base64 : on refuse tout caractere hors liste.
    require(all { it.isLetterOrDigit() || it in ":/._-+=~%," }) { "Caractere interdit dans une valeur BuildConfig" }
    return "\"$this\""
}

android {
    namespace = "fr.footballrama.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.footballrama.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "FIXTURES_BASE_URL", buildValue("FIXTURES_BASE_URL").asJavaStringLiteral())
        buildConfigField("String", "FIXTURES_PUBLIC_KEYS", buildValue("FIXTURES_PUBLIC_KEYS").asJavaStringLiteral())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    // UI Compose for TV
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // QR code de la connexion depuis le telephone (encodeur pur Java, aucune permission).
    implementation(libs.zxing.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
