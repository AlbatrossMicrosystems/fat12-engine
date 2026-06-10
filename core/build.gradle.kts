plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    // Open Q#3: the golden-image fixtures live in src/testFixtures and are shared
    // with :app via testFixtures(project(":core")). :core's own JUnit-5 tests must
    // see them too, so wire the fixtures onto the test classpath here.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.jqwik)
    testImplementation(libs.kotlinx.coroutines.test)
}
// Default suite: exclude the @Tag("fsck") emit test so `./gradlew :core:test` stays
// headless and dosfstools-free for reviewers (REPRO-01), and so the D-13 live count is
// deterministic (the 2 emit tests never inflate the headline number).
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("fsck") }
}

// Opt-in task: runs ONLY the @Tag("fsck") emit test, producing core/build/fsck/{fresh,ops}.img
// for the external dosfstools `fsck.fat -n` oracle in CI.
tasks.register<Test>("fsckEmit") {
    description = "Emits engine-written FAT12 images for the external fsck.fat oracle (CI)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("fsck") }
}
