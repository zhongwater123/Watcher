import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties
import groovy.json.JsonOutput
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(::load)
    }
}

val versionPropertiesFile = rootProject.file("version.properties")
fun loadVersionProperties(): Properties {
    return Properties().apply {
        if (versionPropertiesFile.exists()) {
            FileInputStream(versionPropertiesFile).use(::load)
        }
    }
}
val versionProperties = loadVersionProperties()

val versionNameBase = versionProperties.getProperty("VERSION_NAME_BASE", "1.0").trim()
val storedVersionCode = versionProperties.getProperty("VERSION_CODE", "1").toIntOrNull() ?: 1
val resolvedVersionCode = storedVersionCode
val resolvedVersionName = "$versionNameBase.$resolvedVersionCode"

data class ReleaseSigningMaterial(
    val keystoreFile: java.io.File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val signatureAlgorithm: String,
    val publicKeyPem: String
)

fun resolvedConfigValue(name: String, default: String = ""): String {
    val propertyKey = name.lowercase()
    val projectValue = providers.gradleProperty(propertyKey).orNull?.trim()
    if (!projectValue.isNullOrBlank()) {
        return projectValue
    }
    val envValue = providers.environmentVariable(name).orNull?.trim()
    if (!envValue.isNullOrBlank()) {
        return envValue
    }
    return localProperties.getProperty(name, default).trim()
}

fun normalizePemForComparison(value: String): String {
    return value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\s".toRegex(), "")
}

fun loadReleaseSigningMaterialOrNull(): ReleaseSigningMaterial? {
    val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")?.trim().orEmpty()
    val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")?.trim().orEmpty()
    val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")?.trim().orEmpty()
    val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")?.trim().orEmpty()

    if (
        releaseStoreFile.isBlank() ||
        releaseStorePassword.isBlank() ||
        releaseKeyAlias.isBlank() ||
        releaseKeyPassword.isBlank()
    ) {
        return null
    }

    val keystoreFile = rootProject.file(releaseStoreFile).let { candidate ->
        if (candidate.isAbsolute) candidate else rootProject.file(releaseStoreFile)
    }
    if (!keystoreFile.exists()) {
        return null
    }

    val storeType = when (keystoreFile.extension.lowercase()) {
        "p12", "pfx" -> "PKCS12"
        else -> "JKS"
    }
    val keyStore = KeyStore.getInstance(storeType)
    keystoreFile.inputStream().use { keyStore.load(it, releaseStorePassword.toCharArray()) }
    val privateKey = keyStore.getKey(releaseKeyAlias, releaseKeyPassword.toCharArray()) as? PrivateKey
        ?: return null
    val certificate = keyStore.getCertificate(releaseKeyAlias) ?: return null
    val signatureAlgorithm = when (certificate.publicKey.algorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> return null
    }
    val publicKeyPem = buildString {
        appendLine("-----BEGIN PUBLIC KEY-----")
        append(
            Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                .encodeToString(certificate.publicKey.encoded)
        )
        appendLine()
        appendLine("-----END PUBLIC KEY-----")
    }.trim()

    return ReleaseSigningMaterial(
        keystoreFile = keystoreFile,
        storePassword = releaseStorePassword,
        keyAlias = releaseKeyAlias,
        keyPassword = releaseKeyPassword,
        signatureAlgorithm = signatureAlgorithm,
        publicKeyPem = publicKeyPem
    )
}

fun readReleaseNotes(): String? {
    val notesFilePath = resolvedConfigValue("APP_UPDATE_RELEASE_NOTES_FILE")
    if (notesFilePath.isNotBlank()) {
        val notesFile = rootProject.file(notesFilePath).let { candidate ->
            if (candidate.isAbsolute) candidate else rootProject.file(notesFilePath)
        }
        if (notesFile.exists()) {
            return notesFile.readText(Charsets.UTF_8).trim().ifBlank { null }
        }
    }
    return resolvedConfigValue("APP_UPDATE_RELEASE_NOTES").trim().ifBlank { null }
}

fun sha256Hex(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val derivedReleaseSigningMaterial = runCatching { loadReleaseSigningMaterialOrNull() }.getOrNull()
val effectiveAppUpdatePublicKeyPem = derivedReleaseSigningMaterial?.publicKeyPem
    ?: resolvedConfigValue("APP_UPDATE_PUBLIC_KEY_PEM")

android {
    namespace = "com.example.watcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.watcher"
        minSdk = 29
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_KEY", buildConfigString(""))
        buildConfigField("String", "VOLCENGINE_ASR_APP_KEY", buildConfigString(""))
        buildConfigField("String", "VOLCENGINE_ASR_ACCESS_KEY", buildConfigString(""))
        buildConfigField("String", "VOLCENGINE_ASR_RESOURCE_ID", buildConfigString(""))
        buildConfigField("String", "APP_UPDATE_PUBLIC_KEY_PEM", buildConfigString(""))
    }

    signingConfigs {
        val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")?.trim().orEmpty()
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")?.trim().orEmpty()
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")?.trim().orEmpty()
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")?.trim().orEmpty()

        if (
            releaseStoreFile.isNotBlank() &&
            releaseStorePassword.isNotBlank() &&
            releaseKeyAlias.isNotBlank() &&
            releaseKeyPassword.isNotBlank()
        ) {
            create("release") {
                val keystoreFile = rootProject.file(releaseStoreFile).let { candidate ->
                    if (candidate.isAbsolute) candidate else rootProject.file(releaseStoreFile)
                }
                storeFile = keystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            val apiKey = resolvedConfigValue("API_KEY")
            val volcengineAsrAppKey = resolvedConfigValue("VOLCENGINE_ASR_APP_KEY")
            val volcengineAsrAccessKey = resolvedConfigValue("VOLCENGINE_ASR_ACCESS_KEY")
            val volcengineAsrResourceId = resolvedConfigValue("VOLCENGINE_ASR_RESOURCE_ID")
            val appUpdatePublicKeyPem = effectiveAppUpdatePublicKeyPem

            buildConfigField("String", "API_KEY", buildConfigString(apiKey))
            buildConfigField("String", "VOLCENGINE_ASR_APP_KEY", buildConfigString(volcengineAsrAppKey))
            buildConfigField("String", "VOLCENGINE_ASR_ACCESS_KEY", buildConfigString(volcengineAsrAccessKey))
            buildConfigField("String", "VOLCENGINE_ASR_RESOURCE_ID", buildConfigString(volcengineAsrResourceId))
            buildConfigField("String", "APP_UPDATE_PUBLIC_KEY_PEM", buildConfigString(appUpdatePublicKeyPem))
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            val appUpdatePublicKeyPem = effectiveAppUpdatePublicKeyPem
            buildConfigField("String", "API_KEY", buildConfigString(""))
            buildConfigField("String", "VOLCENGINE_ASR_APP_KEY", buildConfigString(""))
            buildConfigField("String", "VOLCENGINE_ASR_ACCESS_KEY", buildConfigString(""))
            buildConfigField("String", "VOLCENGINE_ASR_RESOURCE_ID", buildConfigString(""))
            buildConfigField("String", "APP_UPDATE_PUBLIC_KEY_PEM", buildConfigString(appUpdatePublicKeyPem))
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "litertlm"
        noCompress += "mp4"
    }
}

val releaseDir = rootProject.file("app/release")
val releaseDownloadBaseUrl = "http://www.shokz-watcher.cn/app"

val renameReleaseApk by tasks.registering {
    doLast {
        val renamedApkName = "watcher-v${resolvedVersionName}-${resolvedVersionCode}-release.apk"
        val candidateApkFiles = listOf(
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile,
            rootProject.file("app/release/app-release.apk")
        )

        candidateApkFiles
            .filter { it.exists() }
            .forEach { sourceApk ->
                val renamedApk = sourceApk.parentFile.resolve(renamedApkName)
                sourceApk.copyTo(renamedApk, overwrite = true)
            }
    }
}

val generateReleaseUpdateMetadata by tasks.registering {
    group = "release"
    description = "Generate signed latest.json metadata beside the Android Studio release APK."
    mustRunAfter(renameReleaseApk)
    doLast {
        val signingMaterial = derivedReleaseSigningMaterial
            ?: error("Release signing material is unavailable. Check local.properties release keystore settings.")
        val configuredPem = resolvedConfigValue("APP_UPDATE_PUBLIC_KEY_PEM")
        if (configuredPem.isNotBlank() &&
            normalizePemForComparison(configuredPem) != normalizePemForComparison(signingMaterial.publicKeyPem)
        ) {
            error("APP_UPDATE_PUBLIC_KEY_PEM does not match the configured release keystore public key.")
        }

        releaseDir.mkdirs()
        val versionCode = resolvedVersionCode.toLong()
        val versionName = resolvedVersionName
        val versionedApkName = "watcher-v$versionName-$versionCode-release.apk"
        val apkFile = listOf(
            releaseDir.resolve(versionedApkName),
            releaseDir.resolve("app-release.apk"),
            layout.buildDirectory.file("outputs/apk/release/$versionedApkName").get().asFile,
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        ).firstOrNull { it.exists() } ?: error("Release APK not found for metadata generation.")

        val payload = linkedMapOf<String, Any?>(
            "versionName" to versionName,
            "versionCode" to versionCode,
            "apkUrl" to "$releaseDownloadBaseUrl/$versionedApkName",
            "apkSha256" to sha256Hex(apkFile),
            "publishedAt" to OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "minSupportedVersionCode" to (resolvedConfigValue("APP_UPDATE_MIN_SUPPORTED_VERSION_CODE", "1").toLongOrNull() ?: 1L),
            "releaseNotes" to readReleaseNotes()
        )
        val payloadJson = JsonOutput.toJson(payload)
        val signature = Signature.getInstance(signingMaterial.signatureAlgorithm).apply {
            initSign(
                KeyStore.getInstance(
                    when (signingMaterial.keystoreFile.extension.lowercase()) {
                        "p12", "pfx" -> "PKCS12"
                        else -> "JKS"
                    }
                ).also { keyStore ->
                    signingMaterial.keystoreFile.inputStream().use {
                        keyStore.load(it, signingMaterial.storePassword.toCharArray())
                    }
                }.getKey(signingMaterial.keyAlias, signingMaterial.keyPassword.toCharArray()) as PrivateKey
            )
            update(payloadJson.toByteArray(Charsets.UTF_8))
        }.sign()
        val envelopeJson = JsonOutput.prettyPrint(
            JsonOutput.toJson(
                linkedMapOf(
                    "payload" to payloadJson,
                    "signature" to Base64.getEncoder().encodeToString(signature),
                    "algorithm" to signingMaterial.signatureAlgorithm
                )
            )
        )

        releaseDir.resolve("latest-payload.json").writeText(
            JsonOutput.prettyPrint(payloadJson),
            Charsets.UTF_8
        )
        releaseDir.resolve("latest.json").writeText(envelopeJson, Charsets.UTF_8)
    }
}

val publishReleaseArtifacts by tasks.registering {
    group = "release"
    description = "Build a release APK and generate latest.json in app/release."
    dependsOn("packageRelease")
}

val bumpReleaseVersion by tasks.registering {
    group = "release"
    description = "Increment VERSION_CODE in version.properties for the next release build."
    doLast {
        val latestProperties = loadVersionProperties()
        val latestBase = latestProperties.getProperty("VERSION_NAME_BASE", "1.0").trim()
        val latestCode = latestProperties.getProperty("VERSION_CODE", "1").toIntOrNull() ?: 1
        latestProperties["VERSION_NAME_BASE"] = latestBase
        latestProperties["VERSION_CODE"] = (latestCode + 1).toString()
        FileOutputStream(versionPropertiesFile).use { output ->
            latestProperties.store(output, "Auto-generated release version state")
        }
        println("Bumped release version to $latestBase.${latestCode + 1} (${latestCode + 1})")
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    finalizedBy(renameReleaseApk, generateReleaseUpdateMetadata, bumpReleaseVersion)
}

generateReleaseUpdateMetadata.configure {
    dependsOn(renameReleaseApk)
}

bumpReleaseVersion.configure {
    mustRunAfter(generateReleaseUpdateMetadata)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.location)

    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Gson
    implementation(libs.gson)

    // MJPEG frame sequence to MP4 encoding
    implementation(libs.jcodec.android)

    // CameraX fallback for local front camera preview
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Embedded HTTP server for gateway API
    implementation(libs.nanohttpd)

    // LiteRT-LM on-device inference
    implementation(libs.litertlm.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // MediaPipe pose estimation
    implementation(libs.mediapipe.tasks.vision)

    // TarsosDSP audio beat detection
    implementation(libs.tarsosdsp.core)
    implementation(libs.tarsosdsp.jvm)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
