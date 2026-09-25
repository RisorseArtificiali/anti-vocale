plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties
import java.io.FileInputStream

// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.antivocale.app"
    compileSdk = 36

    // Pinned to the NDK the fdroiddata builds actually resolve to: r27
    // (27.0.12077973), which is also AGP 8.10's default and the toolchain
    // behind the strip/compile bytes we must match reproducibly. AGP strips
    // the packaged .so with the NDK's llvm-strip; an unpinned (or absent)
    // NDK made release APKs differ from the F-Droid buildserver
    // byte-for-byte (fdroiddata MR !46215). Keep in sync with the recipe.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.antivocale.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 45
        versionName = "1.14.0-SNAPSHOT"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // AppAuth redirect scheme for HuggingFace OAuth
        manifestPlaceholders["appAuthRedirectScheme"] = "com.antivocale.app"

        // TASK-643: version-scoped catalog index. Keyed by versionName
        // (flavor/ABI-independent; versionCode derives per-ABI on F-Droid),
        // SNAPSHOT stripped so dev builds point at the not-yet-published
        // release index and fall back to the bundled asset: an entry cannot
        // reach installed apps earlier than the release that supports it.
        val catalogIndexVersion = versionName.toString().removeSuffix("-SNAPSHOT")
        buildConfigField(
            "String",
            "CATALOG_INDEX_URL",
            "\"https://raw.githubusercontent.com/RisorseArtificiali/anti-vocale/main/app/src/main/assets/external-catalog/index-$catalogIndexVersion.json\""
        )
        // The bundled asset name, emitted from the SAME version variable so the
        // URL layout and the asset path cannot drift apart (no string surgery).
        buildConfigField(
            "String",
            "CATALOG_INDEX_ASSET",
            "\"external-catalog/index-$catalogIndexVersion.json\""
        )

        // Speculative-decoding (MTP) "model update available" prompt gate.
        // Off until the LiteRT-LM runtime can actually engage the Gemma MTP drafter
        // (TASK-221 bumps litertlm-android to 0.13.1+ and flips this to true). The
        // version-stamp plumbing in ModelDownloader ships now (TASK-236); this flag
        // only controls whether the re-download prompt is surfaced to users.
        buildConfigField("boolean", "MTP_SPECULATIVE_DECODING_ENABLED", "false")
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties["storeFile"] != null) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("playStore") {
            dimension = "store"
        }
        create("fdroid") {
            dimension = "store"
        }
    }

    buildTypes {
        release {
            // Only apply the release signing config when keystore.properties exists
            // (Play Store CI). Without it, the APK is unsigned — correct for F-Droid.
            if (keystoreProperties["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Suffix the applicationId so a debug APK installs ALONGSIDE the release /
            // Play Store build (different package) instead of conflicting on signature.
            applicationIdSuffix = ".debug"
        }
    }

    // Per-ABI APK splits: produces separate APKs for each architecture instead of one
    // 259MB universal APK. Each ABI gets a distinct versionCode so F-Droid can serve
    // the correct one per device.
    //
    // DISABLED for bundle (AAB) builds: an App Bundle already embeds all ABIs and
    // Google Play performs the split server-side, so splits.abi is redundant there
    // and makes the bundle task fail ("Sequence contains more than one matching
    // element" in build<Variant>PreBundle). Keeping it for assemble* preserves the
    // per-ABI APK output F-Droid relies on, byte-for-byte.
    val buildingBundle = gradle.startParameter.taskNames.any { it.contains("bundle", true) }
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Map ABI to versionCode suffix: base * 10 + arch (1=armeabi-v7a, 2=arm64-v8a, 4=x86_64)
    androidComponents.onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
            val abiCode = when (abi?.identifier) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86_64" -> 4
                else -> 0
            }
            if (abiCode > 0) {
                (output as com.android.build.api.variant.impl.VariantOutputImpl).versionCode
                    .set((defaultConfig.versionCode ?: 44) * 10 + abiCode)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // TASK-251: errors are fatal (the CI gate); the 250 pre-existing warnings
        // are pinned in lint-baseline.xml and reviewed at release time. New
        // warnings surface (not baseline-hidden) only if their file+line changes.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

// Disable AGP's ArtProfile task. It generates assets/dexopt/baseline.prof and
// baseline.profm from the dex with non-deterministic content (per-build ordering
// variation), which breaks byte-for-byte reproducibility: F-Droid's rebuild and
// our reference differ in these files, so the signature integrity check fails
// after apksigcopier copies our signature onto F-Droid's APK. Documented by
// F-Droid as "Bug: baseline.prof not deterministic" in the Reproducible Builds
// guide. Disabling drops both profile files from the APK; baseline profiles are
// a runtime optimization only, not functional.
//
// Use whenTaskAdded (not afterEvaluate): ArtProfile tasks are created lazily
// AFTER project evaluation, so afterEvaluate matches zero tasks (verified by
// probe). whenTaskAdded fires as each task is created and catches all of them.
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// TASK-387: Byteman race-injection profile. Resolved but NOT on any classpath:
// the agent jar is only referenced as a -javaagent path when -Pbyteman is set,
// so the standard suite stays byte-identical (guide: byteman-guide-for-agents).
val bytemanAgent = configurations.create("bytemanAgent")

dependencies {
    "bytemanAgent"("org.jboss.byteman:byteman:4.0.27")

    // Firebase Crashlytics only — playStore flavor (F-Droid build is Firebase-free).
    // firebase-analytics deliberately omitted: it transitively pulls play-services-measurement
    // + ads-adservices, which inject AD_ID / ACCESS_ADSERVICES_* permissions that contradict the
    // app's "no tracking, no ads" promise. Crashlytics needs none of those. Install/country stats
    // come from the Play Console, not Firebase, so Analytics is unused here.
    "playStoreImplementation"(platform(libs.firebase.bom))
    "playStoreImplementation"(libs.firebase.crashlytics)

    // LiteRT-LM for multimodal inference (text + audio). v0.13.1 adds MTP speculative-
    // decoding runtime support (TASK-221); pairs with the version-stamp prompt in TASK-236.
    // https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android
    implementation(libs.litertlm)

    // MediaPipe GenAI - kept as fallback for text-only inference
    implementation(libs.mediapipe.genai)

    // Jetpack Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)

    // TASK-491: coach-mark overlays for the first-install welcome tour.
    // v3.2.x is the line built against OUR compose-bom (2025.01.00); v3.3+
    // needs a BOM bump (see the task notes before upgrading).
    implementation(libs.reveal.core)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Material Components (for XML Material3 theme)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)

    // OkHttp for model downloads
    implementation(libs.okhttp)

    // Security for encrypted shared preferences
    implementation(libs.androidx.security.crypto)

    // AppAuth for OAuth authentication (HuggingFace)
    implementation(libs.appauth)

    // sherpa-onnx v1.13.8 for ONNX-based ASR (Parakeet TDT, Whisper, Qwen3-ASR, Nemotron).
    // v1.13.8: ORT 1.28.2, Qwen3 mel-frontend fix (PR #3873) + PRNG data-race fix
    // (PR #3912), Canary empty-transcript-on-eos fix (PR #3920). A/B on the
    // Italian set: qwen3 14.55->15.17 WER (neutral-at-noise), Parakeet byte-identical.
    // SRCLIB PIN: k2-fsa/sherpa-onnx v1.13.8 = commit 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
    // (for the F-Droid recipe; keep in sync with scripts/fetch-sherpa-aar.sh).
    // Stock prebuilt AAR (all 4 ABIs).
    implementation(files("libs/sherpa-onnx.aar"))

    // Apache Commons Compress for tar.bz2 extraction
    implementation(libs.commons.compress)

    // Hilt dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // WorkManager + hilt-work: powers the subtitle-choice timeout worker (Task 9).
    // hilt-work pinned to 1.2.0 to match androidx.hilt:hilt-navigation-compose above.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room database for log persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)

    // Hilt testing
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    // Hilt 2.58 (last AGP-8 line) ships kotlin-metadata-jvm 2.2.20, which cannot
    // read the Kotlin 2.4 metadata our classes now carry. Forcing the matching
    // version on the KSP classpath; drop this when Hilt requires AGP 9 and we
    // follow (2.59+ embeds a new enough metadata reader).
    "ksp"(libs.kotlin.metadata.jvm)
    "kspTest"(libs.kotlin.metadata.jvm)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Apply Firebase plugins only for playStore builds (and IDE syncs, which run no
// assemble/bundle task). The fdroid flavor builds without google-services.json.
val buildingFdroidOnly = gradle.startParameter.taskNames.any { it.contains("Fdroid", true) } &&
    !gradle.startParameter.taskNames.any { it.contains("PlayStore", true) }
if (!buildingFdroidOnly) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Environmental isolation: test workers default java.io.tmpdir to the machine
// tmpfs, whose per-USER quota is shared with every other session on this
// workstation (a neighbor's 12G burst makes the whole suite fail with Disk
// quota exceeded). -PtestTmpDir=<path> redirects ONLY the test JVMs' tmpdir
// (TemporaryFolder, native extractions); the daemon and build caches are
// unaffected. Used by the overnight runs; nothing changes without the flag.
if (project.hasProperty("testTmpDir")) {
    tasks.withType<Test>().configureEach {
        systemProperty("java.io.tmpdir", project.property("testTmpDir").toString())
    }
}

// TASK-387: -Pbyteman wires the agent into every Test JVM. Tests opt in by
// assuming the byteman.agent system property (JUnit4 Assume; JUnit5 has
// @EnabledIfSystemProperty); without the property the suite is identical
// (no agent, no rules, gated tests skipped).
if (project.hasProperty("byteman")) {
    tasks.withType<Test>().configureEach {
        val agentJar = bytemanAgent.singleFile
        jvmArgs(
            // Single canonical rules file (Byteman 4.0.27 rejects a directory in script:);
            // race targets append their RULE blocks to rules.btm, no build change needed.
            "-javaagent:$agentJar=script:${rootDir}/app/src/test/resources/byteman/rules.btm",
            "-Dorg.jboss.byteman.verbose=true",
        )
        systemProperty("byteman.agent", "true")
    }
}
