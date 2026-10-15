plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun intProperty(name: String) = providers.gradleProperty(name).get().toInt()

android {
    namespace = "com.snowyghost.ovenlight"
    compileSdk = intProperty("ovenlight.compileSdk")
    ndkVersion = providers.gradleProperty("ovenlight.ndkVersion").get()

    defaultConfig {
        applicationId = "com.snowyghost.ovenlight"
        minSdk = intProperty("ovenlight.minSdk")
        targetSdk = providers.gradleProperty("targetSdk").orNull?.toInt() ?: intProperty("ovenlight.targetSdk")
        // Play gives every upload its versionCode (the highest on any track plus one).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "1.0.0"
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

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // The pins move only with the plan, and these checks ask the network.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
