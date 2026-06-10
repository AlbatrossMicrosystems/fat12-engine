# Third-Party Notices

This document lists the third-party dependencies of the `fat12-engine` `:core` module
and their licenses. It is scoped to the `:core` artifact published in this repository.

The Android demonstrator application (`:app`) described in the accompanying paper is **not**
part of this repository, so its Android-only dependencies (the USB mass-storage library,
AndroidX, Jetpack Compose, the dependency-injection framework, and a bundled UI font) are
**not** distributed here and are intentionally omitted from the notices below.

## Runtime dependencies

The only runtime dependency of the `:core` engine is the Kotlin standard library, pulled in
transitively by the Kotlin JVM plugin.

| Dependency | Coordinates | SPDX License | License text |
|------------|-------------|--------------|--------------|
| Kotlin standard library | `org.jetbrains.kotlin:kotlin-stdlib` | `Apache-2.0` | https://www.apache.org/licenses/LICENSE-2.0 |

## Test-scoped dependencies

The following dependencies are used **only** to compile and run the test suite. They are
**not** distributed in any runtime artifact produced by this module.

| Dependency | Coordinates | SPDX License | License text |
|------------|-------------|--------------|--------------|
| JUnit 5 (Jupiter) | `org.junit.jupiter:junit-jupiter` | `EPL-2.0` | https://www.eclipse.org/legal/epl-2.0/ |
| JUnit Platform Launcher | `org.junit.platform:junit-platform-launcher` | `EPL-2.0` | https://www.eclipse.org/legal/epl-2.0/ |
| jqwik (property-based testing) | `net.jqwik:jqwik` | `EPL-2.0` | https://www.eclipse.org/legal/epl-2.0/ |
| Kotlinx Coroutines Test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `Apache-2.0` | https://www.apache.org/licenses/LICENSE-2.0 |

## Scope note

No Android USB mass-storage library, no bundled UI font, no AndroidX, no Jetpack Compose, and
no dependency-injection framework are shipped in this repository. Those are concerns of the
separate `:app` demonstrator and are not part of this `:core` artifact.
