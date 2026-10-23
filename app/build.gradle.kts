plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "bot.tab.edgepanel"
    compileSdk = 34

    defaultConfig {
        applicationId = "bot.tab.edgepanel"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        resourceConfigurations += listOf("en", "hi")
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("previewDir", System.getProperty("previewDir") ?: "build/previews")
            System.getProperty("epDebug")?.let { v -> it.systemProperty("epDebug", v) }
            it.testLogging { showStandardStreams = true }
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}
