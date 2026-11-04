import com.android.build.api.dsl.Packaging
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// `checkReleaseBuilds` (a.k.a. the lintVital pass) drags a
// `lintVitalAnalyzeRelease` run into every library subproject plus the app's
// own lintVital — measured at ~3.5 min of a ~13 min pipeline. It is the
// fast-iteration CI builds (feat/** pushes) that opt out with -PskipLintVital;
// master pushes and pull requests keep it as the release gate. `./gradlew lint`
// runs the full report locally.
val skipLintVital = providers.gradleProperty("skipLintVital").isPresent

// Release signing material, resolved from two sources in precedence order:
//
//   1. Environment (CI)  -- NAZARICK_KEYSTORE / NAZARICK_STORE_PASSWORD /
//                            NAZARICK_KEY_ALIAS / NAZARICK_KEY_PASSWORD. The workflow
//                            decodes the base64 Actions secret into $RUNNER_TEMP and
//                            exports the rest, so nothing secret lives in the tree.
//   2. local.properties  -- storeFile / storePassword / keyAlias / keyPassword, the
//                            original scheme, for a developer machine.
//
// Signing is still never a hard requirement to *build*: with none of the above present
// the release variant comes out unsigned and `validateSigning<Flavor>Release` stays
// quiet, which is what a feature branch (or a fork without secrets) wants. When it IS
// present the artifact is `app-<flavor>-release.apk` -- signed here, by us, with no
// second pass on a VPS afterwards.
val localPropertiesForSigning = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localPropertiesForSigning.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseSigning: Map<String, String?> = listOf(
    "storeFile" to "NAZARICK_KEYSTORE",
    "storePassword" to "NAZARICK_STORE_PASSWORD",
    "keyAlias" to "NAZARICK_KEY_ALIAS",
    "keyPassword" to "NAZARICK_KEY_PASSWORD",
).associate { (propertyName, envName) -> propertyName to signingValue(envName, propertyName) }

val hasReleaseSigning: Boolean = releaseSigning.values.all { it != null }

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // This branch ships exactly one product: Moxw Agent. The `brand` dimension is
    // kept because the flavour source set (`app/src/moxw/res`) carries the name, the
    // icon and the localised strings — but with a single flavour it describes an
    // identity rather than a choice.
    //
    //  · moxw — Moxw Agent. A standalone brand, with its own applicationId and an
    //    independent version line (0.x), see cold-memory M21.
    //
    // The installation identity is `com.nazarick.agent` as of 2026-10-31: this project is
    // no longer a downstream of anything, so the package follows the project rather than
    // the old product brand. Two consequences, both accepted knowingly: an existing
    // `com.moxw.agent` install does NOT upgrade onto this — Android reads a package name
    // change as a different app, so the old one stays on the device until it is removed —
    // and the flavour is still *named* `moxw`, because renaming that would move source
    // sets, the CI matrix and every artifact name, which is a ticket of its own.
    // The Kotlin namespace stays `me.rerere.rikkahub` for the same reason: thousands of
    // files, plus exported intent actions that external automation addresses by name.
    flavorDimensions += "brand"
    productFlavors {
        create("moxw") {
            dimension = "brand"
            applicationId = "com.nazarick.agent"
            // Play Store listing (owner directive, 2026-11-04): the Play build ships
            // under `com.nazarick.agent.beta` so it coexists with direct installs on
            // the same device — same signing key, same versionCode/versionName, same
            // label; the package name is the only difference. Property unset = the
            // exact prior artifact (house rule: new knobs default to old behavior).
            // Authorities in the manifest are all `${applicationId}`-scoped, so the
            // two installs cannot collide on a provider.
            if (providers.gradleProperty("PLAY_BETA").isPresent) {
                applicationId = "com.nazarick.agent.beta"
            }
            versionCode = 11
            versionName = "0.0.4"
            // Renamed for v0.04 — the launcher still read "Moxw Agent" after the
            // Nazarick re-brand (owner bug, 2026-11-02). "RikkaHub Agent" survives
            // only as the upstream fallback in main's strings.xml.
            resValue("string", "app_name", "Nazarick Agent")
            buildConfigField("String", "BRAND_NAME", "\"Nazarick\"")
            buildConfigField("String", "BRAND_ID", "\"moxw\"")
            buildConfigField("String", "VERSION_NAME", "\"0.0.4\"")
            buildConfigField("String", "VERSION_CODE", "\"11\"")
            buildConfigField("String", "UPDATE_API_URL", "\"\"")
            // The version line now matches the tags (v0.03 == 0.0.3), which it did not: this
            // flavour used to carry the donor's 0.7.x, so an APK labelled 0.7.0 shipped out of a
            // release called v0.02. Update checks stay off regardless — comparing a "v0.03" tag
            // against a "0.0.3" name needs a stated rule, and that is a ticket of its own.
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            // Only consulted when hasReleaseSigning is true, so the !! below is a
            // statement about that check, not a hope.
            val storePath = releaseSigning.getValue("storeFile")!!
            storeFile = file(storePath)
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
            // AGP's default store type is JKS. Feeding it a PKCS12 container under that
            // name fails with the deeply misleading "Keystore was tampered with, or
            // password was incorrect". Derive the type from the file it was handed.
            storeType = if (storePath.endsWith(".jks")) "JKS" else "PKCS12"
            // AGP enables v3 only when minSdk >= 28; ours is 26, so the default build
            // ships v2-only (measured on-device as apkSigningVersion=2). v3 is what lets
            // Android 9+ accept a ROTATED signing key without every install being
            // uninstalled — free to turn on now, expensive to retrofit once the app is
            // on phones. v4 (incremental install) stays off: it needs the matching
            // `.apk.id.sig` shipped alongside, and nothing here installs incrementally.
            enableV3Signing = true
            logger.lifecycle("Signing release build with key '$keyAlias' from $storePath")
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // AGP 9 leaves resValues off by default; the brand flavors set app_name
        // through resValue(), so the feature has to be on.
        resValues = true
        // agent-keyboard IPC (IKeyboardApi.aidl + EditorInfoBundle.aidl) and the Shizuku
        // user service (IShizukuUserService.aidl) both live in src/main/aidl.
        aidl = true
    }
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
            // llamajni's OpenCL soname stub is link-time bait for libggml-opencl;
            // packaging it would shadow the vendor's real libOpenCL.so on device.
            excludes += "lib/*/libOpenCL.so"
        }
    }
    lint {
        // FullBackupContent insists every <exclude> path lives under a previously
        // <include>'d root. Our backup_rules.xml + data_extraction_rules.xml use
        // include="upload/" + explicit excludes for databases / sharedpref /
        // datastore/ / known_hosts / browser-profile/ / local-models/ as
        // belt-and-suspenders defence: if anyone later adds a broader <include>
        // (e.g. domain="root"), the excludes still keep credentials and
        // multi-GB local LLM weights off the cloud-backup path. Lint reads that
        // pattern as redundant; the runtime accepts it. Keep the rules; mute
        // the check.
        disable.add("FullBackupContent")
        // See skipLintVital above: the fast CI lane turns the release-build lint
        // pass off, everything else keeps it on.
        checkReleaseBuilds = !skipLintVital
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        // ExperimentalNavigation3Api was renamed/removed in newer navigation3 — opt-in is
        // no longer required and the marker class no longer exists in the runtime artifact.
    }
}

// AGP 9 only creates a JVM unit-test task for the default tested build type
// (debug): `unitTestEnabled` / `enableUnitTest` are gone, and a non-default
// build type has to opt in through the host-tests API. CI ships the *release*
// variant of both flavors, so run the unit tests against that same variant —
// which also means the debug variant (and its own copy of the llama.cpp native
// libs) is never built in CI.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as? HasHostTestsBuilder)
            ?.hostTests
            ?.get(HostTestBuilder.UNIT_TEST_TYPE)
            ?.let { it.enable = true }
    }

    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            // AGP names every output `app-<flavour>-<abi>-<buildType>.apk`, so the file a
            // user downloads said "moxw" — the internal brand id and source-set directory
            // name — while everything they can actually see (package, repo, release) says
            // Nazarick.
            //
            // Rewrite the token rather than rebuild the name from filters, so AGP keeps
            // ownership of arm64 / x86_64 / universal. The catch, learned from run
            // 41433989045: `outputFileName.set(outputFileName.map { … })` compiles happily
            // and then dies during dependency resolution of :app:packageMoxwRelease with
            //   Circular evaluation detected: property(java.lang.String, map(…))
            // because a property's new value was derived from that same property. So read
            // AGP's default eagerly and assign a plain String — a value, not a mapping.
            //
            // Guarded, and deliberately not using output.filters to rebuild the name: AGP
            // 9.3 is young enough that the filter API is the one thing here not already
            // proven by this file. A failed read means the old filename ships and the log
            // says so, which beats a build that cannot configure itself.
            val default = runCatching { output.outputFileName.orNull }.getOrNull()
            if (default != null) {
                output.outputFileName.set(default.replaceFirst("app-moxw", "app-nazarick"))
            } else {
                logger.lifecycle(
                    "nazarick: could not read AGP's default outputFileName for ${variant.name}" +
                        " — its artifact keeps the flavour name; fix the rename, do not rename it here",
                )
            }
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleMoxwRelease", "bundleMoxwRelease")
    description = "Build the Moxw APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Vico — Compose-native charts for the statistics page.
    implementation(libs.vico.compose.m3)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)


    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    // ML Kit's bundled Chinese/Latin text recognizer: reads the text out of the images in the file
    // library. Bundled rather than the play-services variant, because the library has to work on
    // the first run without downloading a model.
    implementation(libs.text.recognition.chinese)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // Google Play Services Location (FusedLocationProvider)
    implementation(libs.play.services.location)
    // kotlinx.coroutines.tasks.await for Task<*> (was previously transitive via Firebase)
    implementation(libs.kotlinx.coroutines.play.services)

    // AndroidX Biometric (BiometricPrompt)
    implementation(libs.androidx.biometric)

    // AndroidX Media — MediaSessionCompat, MediaButtonReceiver, NotificationCompat.MediaStyle
    implementation(libs.androidx.media)

    // AndroidX DocumentFile — Phase 25 SAF tree traversal for the ExternalStorage tools
    // (USB / SD / Downloads / cloud DocumentsProvider access via persisted tree grants).
    implementation(libs.androidx.documentfile)

    // modules
    implementation(project(":ai"))
    implementation(project(":runtime"))
    implementation(project(":local-llm"))
    implementation(project(":llama-cpp"))
    implementation(project(":sd-cpp"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // SSH client (Mwiede fork — maintained, Android-friendly)
    implementation(libs.jsch)

    // Cron utilities (expression parsing & validation)
    implementation(libs.cron.utils)

    // Shizuku client — lets shizuku_exec run a shell command with the shell UID's
    // privileges without root. :api is the client SDK; :provider ships ShizukuProvider,
    // the ContentProvider that receives the binder from the Shizuku app.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // tests
    testImplementation(libs.junit)
    // Real org.json impl, for the same reason :llama-cpp has it: the stub android.jar throws
    // "not mocked" on every JSONObject call, and the embedding embedder parses the native
    // model facts with org.json. Without this, any test that drives LlamaCppEmbedder through
    // its real code path fails on the first JSONObject - which is a test of nothing.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
