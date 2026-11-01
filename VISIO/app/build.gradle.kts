plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val motionOnly = providers.gradleProperty("motionOnly").orNull == "true"
val samsungAars = fileTree("libs") { include("*.aar") }
if (!motionOnly && samsungAars.files.size != 1) {
    throw GradleException("Coloca UN Samsung Health Sensor SDK >=1.4.1 .aar en app/libs. Para comprobar solo IMU: -PmotionOnly=true")
}

android {
    namespace = "org.pipalab.wear"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.pipalab.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0-capture"
        buildConfigField("boolean", "SAMSUNG_ENABLED", (!motionOnly).toString())
    }
    sourceSets.getByName("main").kotlin.srcDir(if (motionOnly) "src/motion/kotlin" else "src/samsung/kotlin")
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    if (!motionOnly) implementation(samsungAars)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
