import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

fun loadPropertiesFrom(filePath: String): Properties {
    return Properties().apply {
        val file = rootProject.file(filePath)
        if (file.exists()) {
            FileInputStream(file).use(::load)
        }
    }
}

val rootLocalProperties = loadPropertiesFrom("local.properties")
val appLocalProperties = Properties().apply {
    val appLocal = project.file("local.properties")
    if (appLocal.exists()) {
        FileInputStream(appLocal).use(::load)
    }
}

fun normalizeProp(value: String?): String? {
    val trimmed = value?.trim()?.trim('"', '\'')
    return trimmed?.takeIf { it.isNotBlank() }
}

fun pickConfig(vararg keys: String, default: String = ""): String {
    for (key in keys) {
        val value = normalizeProp(
            providers.gradleProperty(key).orNull
                ?: rootLocalProperties.getProperty(key)
                ?: appLocalProperties.getProperty(key)
                ?: System.getenv(key)
        )
        if (!value.isNullOrBlank()) return value
    }
    return default
}

fun esc(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.brunocodex.kotlinproject"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brunocodex.kotlinproject"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${esc(pickConfig("SUPABASE_URL", "SUPABASE_PROJECT_URL", "supabase.url"))}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${esc(pickConfig("SUPABASE_ANON_KEY", "SUPABASE_KEY", "supabase.anon.key"))}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_BUCKET",
            "\"${esc(pickConfig("SUPABASE_BUCKET", "supabase.bucket", default = "vehicle-media"))}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activityKtx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("io.github.jan-tennert.supabase:bom:2.2.3"))
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-android:2.3.12")
}
