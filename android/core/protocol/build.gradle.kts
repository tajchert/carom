// =============================================================================
// CORE:PROTOCOL MODULE
// =============================================================================
// The Carom wire protocol (../protocol/SPEC.md) as pure Kotlin/JVM: encodings,
// crypto (SPEC §2), envelopes, payloads, records, box, pairing, rekey, tokens,
// QR URIs, collapse, padding, UUIDv7 and the SPEC §3.3 receiver rules.
//
// No Android dependency on purpose: the same code runs on the device and in
// plain JVM unit tests, and the macOS/TypeScript ports can be compared case by
// case through the shared vectors.
//
// ## Crypto provider
//
// Tink (tink-android, a plain jar) supplies Ed25519, X25519, HKDF-SHA256 and
// ChaCha20-Poly1305 with an explicit nonce. Each has a pure-Java fallback, so
// identical code paths run on the JVM test runner and on API 29+ devices, with
// no native libraries and no dependence on the device's (stripped) BouncyCastle
// provider. SHA-256 and HMAC-SHA256 come from the JCA, which is always present.
//
// ## Vectors
//
// The unit tests load every ../protocol/vectors/*.json file. The directory is
// passed in as the `carom.vectors.dir` system property and declared as a test
// input, so a vector change re-runs the tests.
// =============================================================================

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

/** Passes the vectors directory to the tests as a relocatable, tracked input. */
abstract class VectorsDirArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dcarom.vectors.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(
        objects.newInstance<VectorsDirArgument>().apply {
            directory.set(rootProject.layout.projectDirectory.dir("../protocol/vectors"))
        },
    )
}
