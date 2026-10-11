import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

configurations.matching { it.name.contains("AndroidTest", ignoreCase = true) }.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
    )
}

android {
    namespace = "com.guanyi.mirra"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.guanyi.mirra"
        minSdk = 23
        targetSdk = 37
        versionCode = 5
        versionName = "1.1.2-band-readonly"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val wearableSdkFile = rootProject.file(".local/vendor/xms-wearable-lib_1.4_release.aar")
val verifyWearableSdk = tasks.register("verifyWearableSdk") {
    inputs.file(wearableSdkFile)
    doLast {
        check(wearableSdkFile.isFile) {
            "Official wearable SDK missing: run scripts/fetch-wearable-sdk.ps1 (no stub fallback)."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(wearableSdkFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195") {
            "Official wearable SDK checksum changed; inspect it before building."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyWearableSdk) }

dependencies {
    // Official Interconnect appendix AAR; fetched/checksummed locally, never a community stub.
    // See scripts/fetch-wearable-sdk.ps1 before building on a fresh checkout.
    implementation(files(wearableSdkFile))
    // Reuse the existing serialization runtime version for the strict portable backup format.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
