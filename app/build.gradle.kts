plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
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
        versionCode = 38
        versionName = "1.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // AppAuth redirect scheme for HuggingFace OAuth
        manifestPlaceholders["appAuthRedirectScheme"] = "com.antivocale.app"

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
                    .set((defaultConfig.versionCode ?: 37) * 10 + abiCode)
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
    "playStoreImplementation"(platform("com.google.firebase:firebase-bom:34.0.0"))
    "playStoreImplementation"("com.google.firebase:firebase-crashlytics")

    // LiteRT-LM for multimodal inference (text + audio). v0.13.1 adds MTP speculative-
    // decoding runtime support (TASK-221); pairs with the version-stamp prompt in TASK-236.
    // https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // MediaPipe GenAI - kept as fallback for text-only inference
    implementation("com.google.mediapipe:tasks-genai:0.10.33")

    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Components (for XML Material3 theme)
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp for model downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Security for encrypted shared preferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // AppAuth for OAuth authentication (HuggingFace)
    implementation("net.openid:appauth:0.11.1")

    // sherpa-onnx v1.13.5 for ONNX-based ASR (Parakeet TDT, Whisper, Qwen3-ASR, Nemotron).
    // v1.13.5: ORT 1.27.1 (fixes ARM64 quantized-path failure, k2-fsa/sherpa-onnx#3850).
    // SRCLIB PIN: k2-fsa/sherpa-onnx v1.13.5 = commit 3dc7c569f31ca2cd4a20ed6f7db780327e6714c5
    // (for the F-Droid recipe; keep in sync with scripts/fetch-sherpa-aar.sh).
    // Stock prebuilt AAR (all 4 ABIs).
    implementation(files("libs/sherpa-onnx.aar"))

    // GGUF/llama-bro: disabled until llama-bro supports Gemma 4 architecture
    // Re-enable: ./gradlew -Penable.gguf installDebug
    if (project.hasProperty("enable.gguf")) {
        implementation("com.github.paoloantinori:llama-bro:v1.3.0-gemma4")
    }

    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.26.1")

    // Hilt dependency injection
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // WorkManager + hilt-work: powers the subtitle-choice timeout worker (Task 9).
    // hilt-work pinned to 1.2.0 to match androidx.hilt:hilt-navigation-compose above.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Room database for log persistence
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    // Hilt testing
    testImplementation("com.google.dagger:hilt-android-testing:2.58")
    kspTest("com.google.dagger:hilt-compiler:2.58")
    // Hilt 2.58 (last AGP-8 line) ships kotlin-metadata-jvm 2.2.20, which cannot
    // read the Kotlin 2.4 metadata our classes now carry. Forcing the matching
    // version on the KSP classpath; drop this when Hilt requires AGP 9 and we
    // follow (2.59+ embeds a new enough metadata reader).
    "ksp"("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    "kspTest"("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Apply Firebase plugins only for playStore builds (and IDE syncs, which run no
// assemble/bundle task). The fdroid flavor builds without google-services.json.
val buildingFdroidOnly = gradle.startParameter.taskNames.any { it.contains("Fdroid", true) } &&
    !gradle.startParameter.taskNames.any { it.contains("PlayStore", true) }
if (!buildingFdroidOnly) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
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
