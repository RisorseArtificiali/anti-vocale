plugins {
    alias(libs.plugins.android.application)
    // TASK-710: org.jetbrains.kotlin.android is REMOVED under AGP 9's
    // built-in Kotlin (applying it is a hard configuration error). The
    // compose compiler plugin stays: built-in Kotlin still runs it.
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

// The base application id, single-sourced for the OAuth redirect scheme
// placeholders (review 2026-10-15): defaultConfig and the suffixed build
// types all derive from this one literal.
val APP_ID_BASE = "com.antivocale.app"

android {
    namespace = "com.antivocale.app"
    compileSdk = 36

    // Pinned to the NDK the fdroiddata builds actually resolve to: r27
    // (27.0.12077973), the toolchain behind the strip/compile bytes we
    // must match reproducibly. AGP strips the packaged .so with the NDK's
    // llvm-strip; an unpinned (or absent) NDK made release APKs differ
    // from the F-Droid buildserver byte-for-byte (fdroiddata MR !46215).
    // TASK-710 note: AGP 9's DEFAULT NDK is 28.2.13676358, so this pin is
    // now the ONLY thing holding r27 - never read "AGP default" into it.
    // Keep in sync with the recipe.
    ndkVersion = "27.0.12077973"

    // TASK-853: the JNI bridge over the Cactus Needle speech engine
    // (libneedle.a, fetched by scripts/fetch-whistle-engine.sh into
    // app/libs/whistle/<abi>/). Upstream ships no android-x86_64 build, so
    // the x86_64 APK carries a STUB whistle_jni whose every call fails;
    // WhistleNative.available only means the bridge linked, and callers
    // gate on load()/isSpeechLoaded() (false on the stub). Missing arm
    // libs (fresh clone without the fetch script) fail the build loudly
    // here rather than at first use - run the script after cloning.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ONE read of the property feeds both consumers (the CMake stub
    // switch below and the BuildConfig mirror in defaultConfig): the
    // name literal exists exactly once, the catalogIndexVersion pattern.
    // PRESENCE-keyed by design (the F-Droid recipe passes it bare):
    // -PwhistleEngineDisabled=false does NOT re-enable the engine.
    val whistleEngineDisabled = project.hasProperty("whistleEngineDisabled")

    // TASK-854: -PwhistleEngineDisabled (the F-Droid recipe sets it) makes
    // every ABI build the stub bridge: the needle engine ships only as a
    // prebuilt .a with no buildable source upstream, which F-Droid's
    // native-binary policy rejects. Play/nightly builds leave it unset.
    if (whistleEngineDisabled) {
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += "-DWHISTLE_ENGINE_DISABLED=ON"
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.antivocale.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 49
        versionName = "1.16.0-SNAPSHOT"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // AppAuth redirect scheme for HuggingFace OAuth: DERIVED from the
        // applicationId expression (review 2026-10-15: four hand-kept
        // literals could drift; redirectUri() must match the placeholder).
        // The buildType suffixes below append to the same literal.
        manifestPlaceholders["appAuthRedirectScheme"] = APP_ID_BASE

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

        // TASK-865/C (the F-Droid UX gate): mirrors the SAME
        // whistleEngineDisabled property that stubs the native bridge, so a
        // stub build also hides the Whistle catalog card instead of letting
        // users download 17 MB of weights for an engine that cannot load.
        // One gradle property, two consumers (CMake + BuildConfig): they
        // cannot drift apart because both read this exact name.
        buildConfigField(
            "boolean",
            "WHISTLE_ENGINE_DISABLED",
            if (whistleEngineDisabled) "true" else "false"
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
            // Own OAuth redirect scheme (TASK-838 review F1): two installed
            // builds sharing one scheme fight over the implicit redirect;
            // HuggingFaceOAuthConfig.redirectUri() derives from applicationId,
            // and this placeholder feeds the AppAuth library's redirect
            // activity to match. Needs the URI registered on the HF OAuth app.
            manifestPlaceholders["appAuthRedirectScheme"] = APP_ID_BASE + applicationIdSuffix
        }
        // GH #159: the nightly build type, cloned from release so the nightly
        // APK is a true release artifact (R8, resource shrink) but carries its
        // OWN applicationId and label: it installs side-by-side with the
        // store build instead of replacing it, and the launcher says which
        // is which. initWith copies the release block's conditional signing
        // assignment only if keystore.properties existed when that block ran;
        // re-assert it explicitly so the nightly never depends on block order.
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            // Own OAuth redirect scheme, same rationale as the debug block.
            manifestPlaceholders["appAuthRedirectScheme"] = APP_ID_BASE + applicationIdSuffix
            if (keystoreProperties["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
                // No ?: N fallback: the literal sat at 44 from the v1.13.0 bump while the base reached 46 (TASK-683.2).
                val baseVersionCode = requireNotNull(defaultConfig.versionCode) {
                    "Per-ABI versionCode derivation needs defaultConfig.versionCode set"
                }
                (output as com.android.build.api.variant.impl.VariantOutputImpl).versionCode
                    .set(baseVersionCode * 10 + abiCode)
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
        // AGP 9 ships AIDL off by default; the Open Transcribe contract
        // (TASK-785) is the first AIDL surface in the app.
        aidl = true
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
    // v3.2.x pulls jetbrains-compose 1.8.1 transitively: with the BOM BELOW
    // that line, compile and runtime resolved DIFFERENT foundation versions
    // and FlowRow's experimental-signature break crashed the icon page
    // (TASK-753). The BOM now sits above it (2025.06.01 -> foundation 1.8.3,
    // both classpaths converge). The split CAN return via any dependency
    // pulling jetbrains-compose above the BOM: scripts/check-compose-parity.sh
    // guards it (run it in the release preflight and after any toml edit).
    implementation(libs.reveal.core)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Material Components (for XML Material3 theme)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

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

    // sherpa-onnx v2.0.1 for ONNX-based ASR (Parakeet TDT, Whisper, Qwen3-ASR, Nemotron).
    // v2.0.0 (TASK-668): JNI C++ exceptions in recognizer/punctuation
    // constructors now surface as Java exceptions (our #3988; re-audit the
    // TranscriptionException flow), whisper byte-level-BPE character-loss fix
    // (#3965, ours), Canary lang2id-from-vocab + warn (#3962, ours), moonshine
    // v2 decoder mask fix (ours), VAD int64 positions (long-stream SIGSEGV,
    // #4079), NeMo mel log-zero guard (#3999), omnilingual short-input abort
    // (#4043), JNI BMP strings (#4064), espeak-ng/piper-phonemize removed
    // (#4055; AAR contents change).
    // SRCLIB PIN: k2-fsa/sherpa-onnx v2.0.1 = commit af2d8d81dbfa61c4cea4c568da78f3982edca005
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
    // TASK-764: the round-trip symmetry test enumerates the spec's
    // constructor by reflection; it sat on the classpath transitively via
    // robolectric/mockk, but a direct pin keeps that from being an accident.
    testImplementation(kotlin("reflect"))
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
    // TASK-710 review F3: the kotlin-metadata-jvm force-pins added for Hilt
    // 2.58 (its 2.2.20 reader could not read Kotlin 2.4 metadata) are
    // REMOVED - their own drop condition (Hilt 2.59+ on AGP 9) is met by
    // hilt 2.60.1; keeping them would downgrade Hilt's own newer reader.
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
