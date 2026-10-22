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

// Version : source unique dans gradle.properties (APP_VERSION_CODE / APP_VERSION_NAME).
val appVersionCode: Int = providers.gradleProperty("APP_VERSION_CODE").get().trim().toInt()
    .also { require(it > 0) { "APP_VERSION_CODE doit etre un entier > 0" } }
val appVersionName: String = providers.gradleProperty("APP_VERSION_NAME").get().trim()
    .also { require(Regex("""\d+\.\d+\.\d+""").matches(it)) { "APP_VERSION_NAME doit etre de la forme x.y.z" } }

// Signature release : fichier de proprietes HORS depot (jamais committe), cree par scripts/create_release_keystore.ps1.
// Chemin : variable d'environnement FOOTBALL_RAMA_SIGNING, sinon %USERPROFILE%\.football-rama\signing.properties.
// Cles : storeFile (absolu ou relatif au fichier), storePassword, keyAlias, keyPassword. Les valeurs ne sont jamais loggees.
val releaseSigning: Properties? = run {
    val path = providers.environmentVariable("FOOTBALL_RAMA_SIGNING").orNull?.trim()?.takeIf { it.isNotEmpty() }
    val file = path?.let(::File) ?: File(System.getProperty("user.home"), ".football-rama/signing.properties")
    if (!file.isFile) return@run null
    val props = Properties().apply { file.inputStream().use { load(it) } }
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        logger.warn("Football Rama : $file incomplet (manquant : ${missing.joinToString()}) - release NON signee.")
        return@run null
    }
    val store = File(props.getProperty("storeFile").trim()).let { if (it.isAbsolute) it else File(file.parentFile, it.path) }
    if (!store.isFile) {
        logger.warn("Football Rama : keystore introuvable ($store) - release NON signee.")
        return@run null
    }
    props.setProperty("storeFile", store.absolutePath)
    props
}
if (releaseSigning == null && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    logger.warn(
        "ATTENTION Football Rama : aucune configuration de signature release " +
            "(FOOTBALL_RAMA_SIGNING ou %USERPROFILE%\\.football-rama\\signing.properties). " +
            "L'APK release sera NON signe (app-release-unsigned.apk) et ne pourra pas etre distribue. " +
            "Voir scripts/create_release_keystore.ps1.",
    )
}

android {
    namespace = "fr.footballrama.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.footballrama.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

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

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = File(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias").trim()
                keyPassword = releaseSigning.getProperty("keyPassword")
                // minSdk 26 : schemas v2 + v3 (v3 permet une future rotation de cle) ; v1 (JAR) inutile.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Sans configuration de signature : APK release non signe (avertissement ci-dessus), jamais la cle debug.
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
            isDebuggable = false
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
