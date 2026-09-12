// Verifies the vendored prebuilt TDLib Android distribution (core + ktx AARs,
// tdlib-android project v0.1.0) against pinned SHA-256 digests, failing fast
// during configuration if anything is missing or tampered with. Wired into
// :app via app/build.gradle.kts.
//
// Runs at configuration time (not as tasks) so it is fully compatible with the
// Gradle configuration cache.

import java.io.File
import java.security.MessageDigest

val tdlibArtifacts = mapOf(
    "core-release.aar" to "5a5ad7fa346a29f3f09a31eaf4a742dbab864f25dc74f5373f7d2708e2a27638",
    "ktx-release.aar" to "3700bae6e00696cd4fd2d753298e1f86a4ee5fe60a7678f5d0c293c7dd7aa1c8"
)

val tdlibLibsDir = rootProject.file("app/libs")

val problems = mutableListOf<String>()

tdlibArtifacts.forEach { (fileName, expectedHex) ->
    val target = File(tdlibLibsDir, fileName)
    if (!target.exists()) {
        problems += "Missing $target. Vendor the tdlib-android v0.1.0 AARs into app/libs/ (see android/README.md)."
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(expectedHex, ignoreCase = true)) {
            problems += "SHA-256 verification failed for $fileName (expected $expectedHex, got $digest). Re-download the tdlib-android v0.1.0 release."
        }
    }
}

if (problems.isNotEmpty()) {
    throw GradleException(
        "TDLib artifact verification failed:\n" + problems.joinToString("\n") { "- $it" }
    )
}

logger.lifecycle("TDLib artifacts verified (tdlib-android v0.1.0)")
