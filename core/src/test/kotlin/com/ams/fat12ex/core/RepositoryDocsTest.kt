package com.ams.fat12ex.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the community-health / contribution documentation added in this PR:
 * `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/ISSUE_TEMPLATE/feature_request.md`,
 * `.github/PULL_REQUEST_TEMPLATE.md`, and `CONTRIBUTING.md`.
 *
 * These are plain-text/YAML-frontmatter files with no runtime behaviour, so these tests
 * are structural: they assert the required frontmatter fields and sections are present,
 * and that every file/path the documentation points readers at actually exists in the
 * repository. This catches accidental drift (renamed paths, removed sections, broken
 * cross-references) the same way a normal unit test catches a behavioural regression.
 */
class RepositoryDocsTest {

    companion object {
        private lateinit var repoRoot: File

        @JvmStatic
        @BeforeAll
        fun locateRepoRoot() {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile
                    ?: error("could not locate repo root (settings.gradle.kts not found)")
            }
            repoRoot = dir
        }

        private fun repoFile(relativePath: String): File = File(repoRoot, relativePath)
    }

    private fun readRepoFile(relativePath: String): String {
        val file = repoFile(relativePath)
        assertTrue(file.isFile, "expected file to exist: $relativePath")
        return file.readText()
    }

    // ---- bug_report.md ------------------------------------------------------------

    @Test
    fun bugReportTemplate_hasExpectedFrontMatter() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/bug_report.md")
        val frontMatter = extractFrontMatter(text)
        assertTrue(frontMatter.contains("name: Bug report"), "missing 'name: Bug report'")
        assertTrue(
            frontMatter.contains("about: Report incorrect behaviour in the FAT12 engine"),
            "missing expected 'about' line",
        )
        assertTrue(frontMatter.contains("labels: bug"), "missing 'labels: bug'")
        assertTrue(frontMatter.contains("title: ''"), "missing empty 'title' field")
        assertTrue(frontMatter.contains("assignees: ''"), "missing empty 'assignees' field")
    }

    @Test
    fun bugReportTemplate_hasAllRequiredSections() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/bug_report.md")
        val requiredSections = listOf(
            "## Summary",
            "## To reproduce",
            "## Expected behaviour",
            "## Actual behaviour",
            "## Environment",
            "## Correctness-contract impact",
            "## Additional context",
        )
        requiredSections.forEach { section ->
            assertTrue(text.contains(section), "bug_report.md is missing section: $section")
        }
    }

    @Test
    fun bugReportTemplate_referencesRealInMemoryDeviceFixture() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/bug_report.md")
        assertTrue(
            text.contains("InMemoryBlockDevice"),
            "bug_report.md should point reporters at the InMemoryBlockDevice fixture",
        )
        val fixtureExists = repoFile(
            "core/src/testFixtures/kotlin/com/ams/fat12ex/core/testutil/InMemoryBlockDevice.kt",
        ).isFile
        assertTrue(fixtureExists, "InMemoryBlockDevice fixture referenced by the template must exist")
    }

    @Test
    fun bugReportTemplate_includesRunnableKotlinCodeFence() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/bug_report.md")
        assertTrue(text.contains("```kotlin"), "expected a ```kotlin code fence for the repro snippet")
    }

    // ---- feature_request.md --------------------------------------------------------

    @Test
    fun featureRequestTemplate_hasExpectedFrontMatter() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/feature_request.md")
        val frontMatter = extractFrontMatter(text)
        assertTrue(frontMatter.contains("name: Feature request"), "missing 'name: Feature request'")
        assertTrue(
            frontMatter.contains("about: Suggest an addition or improvement to the FAT12 engine"),
            "missing expected 'about' line",
        )
        assertTrue(frontMatter.contains("labels: enhancement"), "missing 'labels: enhancement'")
        assertTrue(frontMatter.contains("title: ''"), "missing empty 'title' field")
        assertTrue(frontMatter.contains("assignees: ''"), "missing empty 'assignees' field")
    }

    @Test
    fun featureRequestTemplate_hasAllRequiredSections() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/feature_request.md")
        val requiredSections = listOf(
            "## Summary",
            "## Motivation",
            "## Proposed API / change",
            "## Scope check",
            "## Correctness-contract impact",
            "## Alternatives considered",
            "## Additional context",
        )
        requiredSections.forEach { section ->
            assertTrue(text.contains(section), "feature_request.md is missing section: $section")
        }
    }

    @Test
    fun featureRequestTemplate_statesCoreOnlyScope() {
        val text = readRepoFile(".github/ISSUE_TEMPLATE/feature_request.md")
        assertTrue(
            text.contains(":core"),
            "feature_request.md should clarify the request must be scoped to :core",
        )
        assertTrue(
            text.contains("Android demonstrator is separate"),
            "feature_request.md should note the Android app is a separate demonstrator",
        )
    }

    // ---- PULL_REQUEST_TEMPLATE.md ---------------------------------------------------

    @Test
    fun pullRequestTemplate_hasExpectedSections() {
        val text = readRepoFile(".github/PULL_REQUEST_TEMPLATE.md")
        val requiredSections = listOf("## What", "## How", "## Correctness contract", "## Checklist")
        requiredSections.forEach { section ->
            assertTrue(text.contains(section), "PULL_REQUEST_TEMPLATE.md is missing section: $section")
        }
    }

    @Test
    fun pullRequestTemplate_correctnessContractChecklistMentionsAllWritePaths() {
        val text = readRepoFile(".github/PULL_REQUEST_TEMPLATE.md")
        val writePaths = listOf("write", "`mkdir`", "`rename`", "`delete`", "set-label", "set-attributes")
        writePaths.forEach { path ->
            assertTrue(text.contains(path), "correctness-contract checklist item should mention $path")
        }
        assertTrue(
            text.contains("verify-after-write + rollback contract"),
            "checklist item should name the verify-after-write + rollback contract",
        )
    }

    @Test
    fun pullRequestTemplate_checklistReferencesRealPathsAndCommand() {
        val text = readRepoFile(".github/PULL_REQUEST_TEMPLATE.md")
        assertTrue(text.contains("./gradlew :core:test"), "checklist should reference the real test command")
        assertTrue(
            text.contains("core/src/testFixtures/resources/golden/"),
            "checklist should reference the golden images directory",
        )
        assertTrue(text.contains("testdata/README.md"), "checklist should reference testdata/README.md")

        assertTrue(repoFile("core/src/testFixtures/resources/golden/").isDirectory, "golden images dir must exist")
        assertTrue(repoFile("testdata/README.md").isFile, "testdata/README.md must exist")
    }

    @Test
    fun pullRequestTemplate_pointsToContributingGuide() {
        val text = readRepoFile(".github/PULL_REQUEST_TEMPLATE.md")
        assertTrue(text.contains("CONTRIBUTING.md"), "template should point contributors at CONTRIBUTING.md")
        assertTrue(repoFile("CONTRIBUTING.md").isFile, "CONTRIBUTING.md referenced by the PR template must exist")
    }

    // ---- CONTRIBUTING.md -------------------------------------------------------------

    @Test
    fun contributing_hasAllRequiredSections() {
        val text = readRepoFile("CONTRIBUTING.md")
        val requiredSections = listOf(
            "## Build and test",
            "## Scope",
            "## The correctness contract",
            "## CI",
            "## Tests",
            "## Golden images",
            "## Commit and PR style",
            "## License",
        )
        requiredSections.forEach { section ->
            assertTrue(text.contains(section), "CONTRIBUTING.md is missing section: $section")
        }
    }

    @Test
    fun contributing_describesBuildAndTestCommand() {
        val text = readRepoFile("CONTRIBUTING.md")
        assertTrue(text.contains("./gradlew :core:test"), "CONTRIBUTING.md must document the test command")
        assertTrue(text.contains("JDK 17"), "CONTRIBUTING.md must document the required JDK version")
        assertTrue(
            text.contains("foojay-resolver"),
            "CONTRIBUTING.md should mention the auto-provisioning toolchain plugin",
        )
    }

    @Test
    fun contributing_describesCorrectnessContractInvariants() {
        val text = readRepoFile("CONTRIBUTING.md")
        val invariantKeywords = listOf(
            "verify-after-write",
            "rollback",
            "undo log",
            "mkdir",
            "rename",
            "delete",
            "set-label",
            "set-attributes",
        )
        invariantKeywords.forEach { keyword ->
            assertTrue(text.contains(keyword), "correctness contract section should mention '$keyword'")
        }
    }

    @Test
    fun contributing_ciSectionMatchesActualWorkflow() {
        val text = readRepoFile("CONTRIBUTING.md")
        assertTrue(text.contains("core-test"), "CI section should name the core-test job")
        assertTrue(text.contains("fsck-oracle"), "CI section should name the fsck-oracle job")
        assertTrue(text.contains("fsck.fat -n"), "CI section should describe the fsck.fat -n check")

        val workflow = readRepoFile(".github/workflows/ci.yml")
        assertTrue(workflow.contains("core-test:"), "referenced core-test job must exist in ci.yml")
        assertTrue(workflow.contains("fsck-oracle:"), "referenced fsck-oracle job must exist in ci.yml")
    }

    @Test
    fun contributing_referencesRealFilesAndPaths() {
        val text = readRepoFile("CONTRIBUTING.md")
        val referencedPaths = listOf(
            ".github/workflows/ci.yml",
            "core/src/test/kotlin/com/ams/fat12ex/core/",
            "core/src/testFixtures/resources/golden/",
            "testdata/README.md",
            "LICENSE",
            "NOTICE",
            "PROVENANCE.md",
        )
        referencedPaths.forEach { path ->
            assertTrue(text.contains(path), "CONTRIBUTING.md should reference $path")
            val target = repoFile(path)
            assertTrue(target.exists(), "path referenced by CONTRIBUTING.md must exist on disk: $path")
        }
    }

    @Test
    fun contributing_declaresApacheLicense() {
        val text = readRepoFile("CONTRIBUTING.md")
        assertTrue(text.contains("Apache-2.0"), "CONTRIBUTING.md should name the Apache-2.0 license")

        val license = readRepoFile("LICENSE")
        assertTrue(
            license.contains("Apache License"),
            "LICENSE file must actually be the Apache License to match CONTRIBUTING.md's claim",
        )
    }

    @Test
    fun contributing_testsSectionReferencesRealFixtures() {
        val text = readRepoFile("CONTRIBUTING.md")
        assertTrue(text.contains("InMemoryBlockDevice"), "Tests section should mention InMemoryBlockDevice")
        assertTrue(text.contains("Fat12ImageBuilder"), "Tests section should mention Fat12ImageBuilder")

        assertTrue(
            repoFile("core/src/testFixtures/kotlin/com/ams/fat12ex/core/testutil/InMemoryBlockDevice.kt").isFile,
            "InMemoryBlockDevice fixture referenced by CONTRIBUTING.md must exist",
        )
    }

    // ---- shared frontmatter validity (both issue templates) -------------------------

    @Test
    fun issueTemplates_haveWellFormedYamlFrontMatterDelimiters() {
        listOf(
            ".github/ISSUE_TEMPLATE/bug_report.md",
            ".github/ISSUE_TEMPLATE/feature_request.md",
        ).forEach { path ->
            val text = readRepoFile(path)
            val lines = text.lines()
            assertTrue(lines.isNotEmpty() && lines[0] == "---", "$path must start with a '---' frontmatter delimiter")
            val closingIndex = lines.drop(1).indexOfFirst { it == "---" } + 1
            assertTrue(closingIndex > 0, "$path must have a closing '---' frontmatter delimiter")
        }
    }

    private fun extractFrontMatter(text: String): String {
        val lines = text.lines()
        assertTrue(lines.isNotEmpty() && lines[0] == "---", "expected file to start with '---' frontmatter delimiter")
        val closingIndex = lines.drop(1).indexOfFirst { it == "---" } + 1
        assertTrue(closingIndex > 0, "expected a closing '---' frontmatter delimiter")
        return lines.subList(1, closingIndex).joinToString("\n")
    }
}