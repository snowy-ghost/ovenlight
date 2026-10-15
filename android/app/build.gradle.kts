plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun intProperty(name: String) = providers.gradleProperty(name).get().toInt()

// The Go core, which scripts/build-android-core.sh builds into libs/ (cached) before every
// build: arm64-v8a only for local debug builds, the release set when a release or bundle
// task is asked for, or with -Povenlight.allAbis.
val allAbis = providers.gradleProperty("ovenlight.allAbis").isPresent ||
    gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true) }
val buildCore = tasks.register<Exec>("buildCore") {
    workingDir = rootDir.parentFile
    commandLine(listOf("scripts/build-android-core.sh") + if (allAbis) emptyList() else listOf("--dev"))
    environment("JAVA_HOME", System.getProperty("java.home"))
    outputs.upToDateWhen { false }
}
tasks.named("preBuild") { dependsOn(buildCore) }

// The e2e harness's test CA (android/e2e/e2e.sh makes it once, in test-ca/ beside the SDK),
// which only debug builds trust. Without it a throwaway one stands in, so a clean checkout
// builds.
abstract class DebugCa : DefaultTask() {
    @get:InputFiles
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val resources: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun write() {
        val pem = resources.get().dir("raw").file("debug_ca.pem").asFile
        pem.parentFile.mkdirs()
        val ca = source.files.firstOrNull { it.isFile }
        if (ca != null) {
            ca.copyTo(pem, overwrite = true)
            return
        }
        val keystore = temporaryDir.resolve("throwaway.p12").apply { delete() }
        val keytool = "${System.getProperty("java.home")}/bin/keytool"
        exec.exec {
            commandLine(keytool, "-genkeypair", "-alias", "throwaway", "-keyalg", "EC", "-dname", "CN=Ovenlight throwaway test CA",
                "-validity", "1", "-keystore", keystore.path, "-storepass", "throwaway")
        }
        exec.exec { commandLine(keytool, "-exportcert", "-rfc", "-alias", "throwaway", "-keystore", keystore.path, "-storepass", "throwaway", "-file", pem.path) }
    }
}
val debugCa = tasks.register<DebugCa>("debugCa") {
    providers.environmentVariable("ANDROID_HOME").orNull?.let { source.from(File(it).resolveSibling("test-ca/ca.pem")) }
    resources = layout.buildDirectory.dir("generated/debugCa")
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(debugCa, DebugCa::resources)
    }
}

android {
    namespace = "com.snowyghost.ovenlight"
    compileSdk = intProperty("ovenlight.compileSdk")
    buildToolsVersion = providers.gradleProperty("ovenlight.buildTools").get()
    ndkVersion = providers.gradleProperty("ovenlight.ndkVersion").get()

    defaultConfig {
        applicationId = "com.snowyghost.ovenlight"
        minSdk = intProperty("ovenlight.minSdk")
        targetSdk = providers.gradleProperty("targetSdk").orNull?.toInt() ?: intProperty("ovenlight.targetSdk")
        // Play gives every upload its versionCode (the highest on any track plus one).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "1.0.0"
        // The Go core's ABIs. A library built for any other (AndroidX ships x86) would let
        // a phone install Ovenlight without its core.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
        buildConfig = true
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
    implementation(files("libs/ovenlight-core.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
