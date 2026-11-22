plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.relab.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.relab.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads its Android SDK jars directly; avoid repo1.maven.org
        // because it intermittently rate-limits GitHub-hosted CI runners (HTTP 429).
        unitTests.all { testTask ->
            testTask.systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven-central.storage-download.googleapis.com/maven2"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.code.gson:gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}


// A release APK can carry a checksum-verified historical chart dataset.
// Supply it with -PrelabPreloadedCsv=/absolute/path/to/source-observations.csv
// (or RELAB_PRELOADED_CSV). Debug/CI builds remain possible without private data.
val relabPreloadedCsv = providers.gradleProperty("relabPreloadedCsv")
    .orElse(providers.environmentVariable("RELAB_PRELOADED_CSV"))
val requirePreloadedLearning = providers.gradleProperty("requirePreloadedLearning")
    .map { it.toBoolean() }.orElse(false)
val generatedPreloadedAssets = layout.buildDirectory.dir("generated/preloadedLearningAssets")

android.sourceSets.getByName("main").assets.srcDir(generatedPreloadedAssets)

val preparePreloadedLearning by tasks.registering {
    inputs.file(relabPreloadedCsv).optional()
    outputs.dir(generatedPreloadedAssets)
    outputs.upToDateWhen { false }
    doLast {
        val inputPath = relabPreloadedCsv.orNull
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = true)
        }
        val outputDir = generatedPreloadedAssets.get().asFile
        outputDir.deleteRecursively()
        if (inputPath.isNullOrBlank()) {
            check(!requirePreloadedLearning.get() && !releaseRequested) {
                "A release APK requires historical learning data. Pass -PrelabPreloadedCsv=/path/to/source-observations.csv"
            }
            return@doLast
        }
        val input = file(inputPath)
        check(input.isFile) { "Preloaded learning CSV does not exist: $inputPath" }
        val bytes = input.readBytes()
        val csv = bytes.toString(Charsets.UTF_8)
        check(csv.isNotBlank() && csv.lineSequence().first().contains("market") &&
            csv.lineSequence().first().contains("session_id") &&
            csv.lineSequence().first().contains("date")) {
            "Preloaded CSV must be the RE-LAB source-observations export with market/session_id/chart_date columns"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val preloadDir = outputDir.resolve("preloaded")
        preloadDir.mkdirs()
        preloadDir.resolve("source-observations.csv").writeBytes(bytes)
        preloadDir.resolve("manifest.properties").writeText(
            "format_version=1\nsha256=$digest\n",
            Charsets.UTF_8
        )
        logger.lifecycle("Prepared RE-LAB historical preload: sha256=$digest, bytes=${bytes.size}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(preparePreloadedLearning)
}
