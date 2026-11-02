import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
}

group = "io.github.matejpopovski"
version = "0.1.0"

java {
    // 22 is what setup-java installs in CI and what the development machine has.
    // No toolchain auto-provisioning: a missing JDK should fail, not download one.
    toolchain { languageVersion = JavaLanguageVersion.of(22) }
}

repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

val bench = sourceSets.create("bench") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("benchImplementation") { extendsFrom(configurations.implementation.get()) }
configurations.named("benchRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    errorprone(libs.errorprone.core)

    implementation(libs.starter.webmvc)
    implementation(libs.starter.jdbc)
    implementation(libs.starter.flyway)
    implementation(libs.starter.validation)
    implementation(libs.starter.actuator)
    implementation(libs.flyway.postgresql)
    implementation(libs.springdoc.webmvc.api)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.starter.test)
    testImplementation(libs.jqwik)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "benchImplementation"(libs.hdrhistogram)

    // The stock embedded-postgres artifact drags in Windows and Alpine binaries, so its
    // transitive binaries are dropped and only the platforms this repo runs on are added.
    listOf("test", "bench").forEach { sourceSet ->
        "${sourceSet}Implementation"(libs.embedded.postgres) {
            exclude(group = "io.zonky.test.postgres")
        }
        "${sourceSet}RuntimeOnly"(libs.embedded.postgres.darwin.arm64)
        "${sourceSet}RuntimeOnly"(libs.embedded.postgres.linux.amd64)
        "${sourceSet}RuntimeOnly"(libs.embedded.postgres.linux.arm64)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 22
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
    options.errorprone {
        // Generated-only noise; everything else stays at Error Prone's defaults.
        disableWarningsInGeneratedCode = true
    }
}

tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    maxHeapSize = "1g"
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("failed", "skipped")
    }
    // Race tests are only meaningful when they really ran, so a cached pass is not evidence.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("ledgerline.writeOpenApi", providers.gradleProperty("writeOpenApi").isPresent)
}

tasks.bootTestRun {
    description = "Runs the service on embedded Postgres; no database install needed."
    mainClass = "io.github.matejpopovski.ledgerline.TestLedgerlineApplication"
}

tasks.register<JavaExec>("bench") {
    description = "Runs the lock-strategy throughput comparison on embedded Postgres."
    group = "verification"
    classpath = bench.runtimeClasspath
    mainClass = "io.github.matejpopovski.ledgerline.bench.LockStrategyBenchmark"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    maxHeapSize = "1g"
    args = providers.gradleProperty("benchArgs").orNull?.split(" ") ?: emptyList()
}

tasks.register("resolveAndLockAll") {
    description = "Rewrites gradle.lockfile; run with --write-locks."
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "$path needs --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
        targetExclude("build/**")
        removeUnusedImports()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("sql") {
        target("src/main/resources/db/migration/*.sql")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
