// Pure Kotlin/JVM. NO Android dependencies — this module must stay testable on a
// plain JVM and reusable outside Android (see CLAUDE_CODE_KICKOFF.md, "Architecture").
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    // Compile against the JDK that is running Gradle (avoids toolchain auto-download).
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    // Only used for the Flow extension that adapts a byte stream into parsed messages.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnit() }
