import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.barrrssss"
version = "0.1.2"

java {
    // Gradle 9 and the publishing plugin run on JDK 17 or newer.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Compile against the Java 11 API, not merely to Java 11 bytecode. This prevents
// accidental use of APIs introduced after Java 11 when the build runs on a newer JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("NOTICE")) {
        into("META-INF")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.github.barrrssss.ldpcstaircase",
            "Implementation-Title" to "LDPC Staircase",
            "Implementation-Version" to project.version,
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Test-only. The published library has no runtime dependencies.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }

    // ConformanceTest and ReferenceCorpusTest read the spec submodule at run time, so
    // Gradle must not call the task up to date after the vectors, the independent
    // reference checker, or its differential corpus change. Declared optional because a
    // fresh clone without submodules still has to configure.
    inputs.files(
        fileTree("external/ldpc-staircase-spec/vectors"),
        fileTree("external/ldpc-staircase-spec/reference"),
    ).withPropertyName("specSubmodule").optional().withPathSensitivity(PathSensitivity.RELATIVE)

    // All research harnesses are opt-in and excluded from ordinary build/check runs.
    listOf(
        "ldpc.bench",
        "ldpc.sim",
        "ldpc.calibrate",
        "ldpc.calibrate.seeds",
        "ldpc.calibrate.configs",
        "ldpc.calibrate.output",
        "ldpc.calibrate.maxResidualUnknowns",
        "ldpc.interleave",
        "ldpc.interleave.seeds",
        "ldpc.interleave.configs",
        "ldpc.interleave.output",
    ).forEach { flag ->
        providers.systemProperty(flag).orNull?.let { value ->
            systemProperty(flag, value)
            maxHeapSize = "2g"
        }
    }
}

// Regenerates the vectors inside the spec submodule. This repo is their single
// generator; ldpc-staircase-dotnet only verifies them. Run deliberately because
// the vectors are the cross-language oracle and any diff is a specification change.
tasks.register<JavaExec>("generateVectors") {
    group = "verification"
    description = "Regenerates the conformance vectors in the spec submodule; review any diff as a spec change."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.barrrssss.ldpcstaircase.VectorGenerator")
    args(file("external/ldpc-staircase-spec/vectors").absolutePath)
}

// Test-only diagnostic for flat primitive graph storage versus the previous object forest.
tasks.register<JavaExec>("runFlatGraphMeasurement") {
    group = "verification"
    description = "Measures flat primitive graph storage against the previous object forest."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.barrrssss.ldpcstaircase.FlatGraphMeasurement")
}

// Test-only margin study near the maximum supported total-symbol count.
tasks.register<JavaExec>("runFormatCeilingMarginStudy") {
    group = "verification"
    description = "Measures the received margin that the residual bound costs near the format ceiling."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.barrrssss.ldpcstaircase.FormatCeilingMarginStudy")
    providers.gradleProperty("studyArgs").orNull?.let { args(it.split(" ")) }
    maxHeapSize = "4g"
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("LDPC Staircase")
        description.set(
            "Systematic, fixed-rate LDPC-Staircase erasure coding over GF(2), following the RFC 5170 section 6 core."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/barrrssss/ldpc-staircase-java")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("barrrssss")
                name.set("barrrssss")
                url.set("https://github.com/barrrssss")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/barrrssss/ldpc-staircase-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/barrrssss/ldpc-staircase-java.git")
            url.set("https://github.com/barrrssss/ldpc-staircase-java")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/barrrssss/ldpc-staircase-java/issues")
        }
    }
}
