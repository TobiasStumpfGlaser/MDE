// Read the custom OTA values verbatim. java.util.Properties would interpret
// sequences such as the domain separator in `DOMAIN\user` (`\t`, `\n`, ...)
// as control-character escapes and would therefore corrupt the username.
val mdeLocalProperties = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.useLines { lines ->
        lines.mapNotNull { line ->
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null

            val key = line.substring(0, separatorIndex).trim()
            if (!key.startsWith("MDE_OTA_")) return@mapNotNull null

            key to line.substring(separatorIndex + 1)
        }.toMap()
    }
    .orEmpty()

fun otaBuildProperty(name: String): String =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: mdeLocalProperties[name]
        ?: ""

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t") + "\""

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
        versionCode = 85
        versionName = "6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MDE_OTA_USERNAME and MDE_OTA_PASSWORD are read at build time from
        // local.properties, -P Gradle properties or environment variables.
        // local.properties is ignored by Git, but the resulting values are
        // necessarily embedded in the APK and must belong to a read-only user.
        buildConfigField("boolean", "OTA_ENABLE", "true")
        buildConfigField(
            "String",
            "OTA_USERNAME",
            buildConfigString(otaBuildProperty("MDE_OTA_USERNAME"))
        )
        buildConfigField(
            "String",
            "OTA_PASSWORD",
            buildConfigString(otaBuildProperty("MDE_OTA_PASSWORD"))
        )
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("com.github.datalogic:datalogic-android-sdk:1.34")
    // SMB2/3 support for the explicitly requested NTLM path and OTA updates.
    // True Kerberos on Android is provided by app/src/main/jniLibs.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
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

    //OTA
    implementation("org.glassfish.grizzly:grizzly-http-server:4.0.2")
    implementation("org.apache.kerby:kerby-kdc:2.0.3")
    implementation("org.apache.kerby:kerby-asn1:2.0.3")
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
