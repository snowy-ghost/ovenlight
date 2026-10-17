import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.play.publisher)
}

fun intProperty(name: String) = providers.gradleProperty(name).get().toInt()

// The Go core, which scripts/build-android-core.sh builds into libs/ (cached) before every
// build: arm64-v8a only for local debug builds, the release set when a release or bundle
// task is asked for.
val allAbis = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true) }
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
// Acknowledgements (decision 17): every license the app ships, written at build time. The
// Android libraries come from their POMs, grouped by license, and one the build doesn't
// recognize fails it; the Go core's notices come from scripts/build-android-core.sh.
abstract class Acknowledgements : DefaultTask() {
    @get:Input
    abstract val modules: MapProperty<String, String>

    @get:InputFiles
    abstract val poms: ConfigurableFileCollection

    @get:InputFile
    abstract val apacheLicense: RegularFileProperty

    @get:InputFile
    abstract val coreNotices: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    private fun licenseOf(pom: File): String? {
        val text = pom.readText()
        val names = Regex("<license>.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1].trim() }.toList()
        val urls = Regex("<license>.*?<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1].trim() }.toList()
        val all = (names + urls).joinToString(" ").lowercase()
        return when {
            all.isEmpty() -> null
            "apache" in all -> "Apache License 2.0"
            "mit" in all.split(Regex("[^a-z]")) -> "MIT License"
            "bsd" in all -> "BSD License"
            else -> null
        }
    }

    // Modules whose POM leaves the license to a parent POM, each checked by hand.
    private val inherited = mapOf(
        "com.google.guava:listenablefuture" to "Apache License 2.0", // guava-parent
        "com.google.auto.value:auto-value-annotations" to "Apache License 2.0", // auto-parent
        "com.google.zxing:core" to "Apache License 2.0", // zxing-parent
    )

    @TaskAction
    fun write() {
        val byPom = poms.files.associateBy { it.name }
        val byLicense = sortedMapOf<String, MutableList<String>>()
        val unknown = mutableListOf<String>()
        for ((module, pomName) in modules.get().toSortedMap()) {
            val license = byPom[pomName]?.let(::licenseOf) ?: inherited[module.substringBeforeLast(':')]
            if (license == null) unknown += module else byLicense.getOrPut(license) { mutableListOf() } += module
        }
        if (unknown.isNotEmpty()) throw GradleException("No license Acknowledgements recognizes for: ${unknown.joinToString()}")
        val folder = assets.get().dir("licenses").asFile.apply { mkdirs() }
        folder.resolve("android-libraries.txt").writeText(buildString {
            append("Android libraries\n=================\n\n")
            for ((license, list) in byLicense) {
                append("$license:\n")
                list.forEach { append("  $it\n") }
                append("\n")
            }
            append("Material Icons (lock_open, error), Apache License 2.0\n\n")
            append("Apache License 2.0\n------------------\n\n")
            append(apacheLicense.get().asFile.readText())
        })
        coreNotices.get().asFile.copyTo(folder.resolve("go-core.txt"), overwrite = true)
    }
}
val acknowledgements = tasks.register<Acknowledgements>("acknowledgements") {
    dependsOn(buildCore)
    val runtime = configurations.named("releaseRuntimeClasspath")
    val ids = runtime.map { configuration ->
        configuration.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
    }
    modules = ids.map { list -> list.associate { "${it.group}:${it.module}:${it.version}" to "${it.module}-${it.version}.pom" } }
    poms.from(ids.map { list ->
        dependencies.createArtifactResolutionQuery().forComponents(list)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            .resolvedComponents.flatMap { component ->
                component.getArtifacts(MavenPomArtifact::class.java).mapNotNull { (it as? ResolvedArtifactResult)?.file }
            }
    })
    apacheLicense = rootProject.layout.projectDirectory.file("../LICENSE")
    coreNotices = layout.projectDirectory.file("libs/THIRD_PARTY_NOTICES.txt")
    assets = layout.buildDirectory.dir("generated/acknowledgements")
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(debugCa, DebugCa::resources)
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(acknowledgements, Acknowledgements::assets)
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
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Go core's ABIs. A library built for any other (AndroidX ships x86) would let
        // a phone install Ovenlight without its core.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // The upload key, from ship-play's environment. Without it a release build is unsigned,
    // as CI's are.
    val uploadKeystore = providers.environmentVariable("PLAY_UPLOAD_KEYSTORE").orNull
    if (uploadKeystore != null) {
        // The configuration cache would keep the password on disk, and a Kotlin daemon would
        // hold it in its environment for hours after the build.
        if (gradle.startParameter.isConfigurationCacheRequested) {
            throw GradleException("Sign release builds with --no-configuration-cache")
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerExecutionStrategy = org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy.IN_PROCESS
        }
        signingConfigs.create("upload") {
            storeFile = file(uploadKeystore)
            storeType = "pkcs12"
            storePassword = providers.environmentVariable("PLAY_UPLOAD_PASSWORD").get()
            keyAlias = "upload"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // -Pminify shrinks the debug build as release is shrunk, so the e2e harness can run
        // what R8 leaves with src/debug's launch extras and test CA.
        debug {
            if (providers.gradleProperty("minify").isPresent) {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
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

    // Robolectric runs the screens' Compose tests and Android's image decoding on the JVM.
    testOptions.unitTests.isIncludeAndroidResources = true

    // Fakes shared by the JVM and device tests.
    sourceSets["test"].kotlin.directories += "src/sharedTest/kotlin"
    sourceSets["androidTest"].kotlin.directories += "src/sharedTest/kotlin"

    lint {
        warningsAsErrors = true
        abortOnError = true
        // The pins move only with the plan, and these checks ask the network.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

// Internal testing uploads (decision 9), for ship-play: the service account's key file is
// named by PLAY_CREDENTIALS, and the versionCode comes from Play, one past the
// highest on any track. Without the variable the plugin's tasks stay off.
play {
    val credentials = providers.environmentVariable("PLAY_CREDENTIALS").orNull
    enabled = credentials != null
    credentials?.let { serviceAccountCredentials = file(it) }
    track = "internal"
    defaultToAppBundles = true
    resolutionStrategy = ResolutionStrategy.AUTO
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
    implementation(libs.androidx.browser)
    // The invite scanner: CameraX's picture, read with ZXing (decision 6).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// The connector's wire fixtures, which the wire test decodes, so a connector change that
// breaks Android fails the same push.
tasks.withType<Test>().configureEach {
    val wire = rootProject.layout.projectDirectory.dir("../connector/testdata/wire")
    inputs.dir(wire).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ovenlight.wireDir", wire.asFile.absolutePath)
    // Robolectric reaches into FileDescriptor to fake Android's shared memory.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
