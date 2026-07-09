plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("jacoco")
}

android {
    namespace = "com.example.mde"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.mde"
        minSdk = 24
        targetSdk = 36
        versionCode = 84
        versionName = "6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "OTA_ENABLE", "false")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("com.github.datalogic:datalogic-android-sdk:1.34")
    implementation("jcifs:jcifs:1.3.17")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // CameraX
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    // ML Kit Barcode (offline!)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(false)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
    }

    val fileFilter = listOf(
        // Android-generierte Klassen
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/databinding/**",
        "**/*Binding.*",

        // Test-Klassen
        "**/*Test*.*",

        // Android-Aktivitäten (benötigen Android-Laufzeit)
        "**/*Activity*.*",
        "**/*Activity\$*.*",

        // Android-Adapter (benötigen Android-Laufzeit)
        "**/*Adapter*.*",
        "**/*Adapter\$*.*",

        // Netzwerk (nicht ohne echten Server testbar)
        "**/TcpClient*.*",

        // UI-Dialog-Helfer (benötigt Activity)
        "**/UiLoadingHelper*.*",

        // Datenmodelle (keine Logik)
        "**/model/**",
        "**/ui/**",

        // Einfache Datenhüllen
        "**/ListDetail*.*",
        "**/ListItem*.*",
        "**/UserCache*.*",
        "**/PickList*.*",
        "**/DropList*.*"
    )

    // AGP 8.x Kotlin-Klassenpfad
    val kotlinDebugTree = fileTree(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    ) {
        exclude(fileFilter)
    }

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(kotlinDebugTree))

    // Alle .exec-Dateien einsammeln (AGP-generiert + Robolectric-generiert)
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include(
            "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            "tmp/jacoco/testDebugUnitTest.exec"
        )
    })
}
