# Native dependency compatibility

Keep Compose Multiplatform **1.11.1** and Material 3 **1.9.0** together. The Material 3 pin preserves the selected pairing with Android Material 3 1.4.0; the dependency normalization does not change it.

The published CMP dependency graph contains forwarding artifacts for libraries migrated to AndroidX. Their common metadata repeats the destination library's `unique_name`, producing eleven KLIB loader warnings. `shared/ui/build.gradle.kts` substitutes these coordinates only in metadata configurations:

| Forwarding family | AndroidX destination already selected by the original graph |
| --- | --- |
| `org.jetbrains.compose.runtime` runtime and runtime-saveable | `androidx.compose.runtime`, 1.11.2 |
| `org.jetbrains.compose.annotation-internal` annotation | `androidx.annotation:annotation`, 1.9.1 |
| `org.jetbrains.compose.collection-internal` collection | `androidx.collection:collection`, 1.5.0 |
| `org.jetbrains.androidx.lifecycle` common, runtime, runtime-compose, viewmodel, viewmodel-savedstate | `androidx.lifecycle`, 2.9.4 |
| `org.jetbrains.androidx.savedstate` savedstate and savedstate-compose | `androidx.savedstate`, 1.4.0 |

The forwarding relationships and minimum destinations are declared in the libraries' published Gradle module metadata. Savedstate 1.4.0 is already selected by CMP UI, above the forwarding artifacts' 1.3.3 minimum.

Do **not** apply these substitutions to native compile or framework configurations. Precompiled CMP native manifests explicitly reference both the AndroidX library and its forwarding KLIB. The native forwarding archives have distinct identities and must remain available. Removing all forwarders produces an incomplete dependency set despite eliminating the metadata warnings.

Run from `iOS/` with the repository's Gradle wrapper:

```text
../android-v2/gradlew :shared:ui:verifyNativeDependencyGraph
../android-v2/gradlew :shared:ui:compileIosMainKotlinMetadata --rerun-tasks
```

On Windows use `..\android-v2\gradlew.bat`. The verification task is also required by `check` and framework link tasks. It resolves actual external native archives for both iOS targets, reads their KLIB manifests, and checks:

- Material 3 remains 1.9.0.
- Metadata resolution contains no migrated forwarding components.
- Each native KLIB identity appears once.
- Every external, non-platform dependency named by each native manifest is present.

The current graph contains **71 external native KLIBs per target**, with no duplicate or missing identities. Kotlin's standard library and Apple platform libraries are supplied by the compiler/SDK and are outside this external-artifact check. These checks run on Windows; they do not establish that an Apple framework links or that the application runs. The macOS build and simulator/device checks remain required.

## Dependency locks

`shared/lyrics/gradle.lockfile`, `shared/platform/gradle.lockfile`, and `shared/ui/gradle.lockfile` record the resolved common metadata, native main/test/API, framework export, and KSP processor graphs. Dependency locking is enabled for all shared-project configurations; the update task deliberately resolves these relevant configurations without attempting unsupported Apple compilation on Windows.

For an intentional dependency update, run from `iOS/`:

```text
../android-v2/gradlew :resolveDependencyLocks --write-locks
../android-v2/gradlew :resolveDependencyLocks :shared:ui:verifyNativeDependencyGraph :shared:ui:compileIosMainKotlinMetadata
```

Review and commit all three generated lockfiles with the dependency change. The second command verifies the recorded resolutions without rewriting the locks. Each module resolves its own configurations, respecting Gradle's project isolation requirements.
