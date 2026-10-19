# McPodface (Android podcast app)

`PLAN.md` is the source of truth for features, behaviour and task breakdown. Read the section your task names.

- App name **McPodface**, package / applicationId `com.steve.mcpodface` (debug builds add `.debug`).
- Kotlin, Compose + Material 3, Hilt (KSP), Room + FTS4, DataStore, Media3, WorkManager, Retrofit/OkHttp, Coil 3.
- AGP 9 with **built-in Kotlin**: do NOT apply `org.jetbrains.kotlin.android`.
- minSdk 33, compile/target 37. Single `:app` module.

## Rules
- **Never add dependencies or change versions.** Everything is pinned in `gradle/libs.versions.toml`. Ask the orchestrator.
- Only edit the files your task lists.
- **Design = Liquid Glass (PLAN.md §11).** Use the pieces in `ui/components/glass/` (`Modifier.glass`, `GlassPage`, `GlassButton`, `GlassIconButton`, `GlassChip`, `ArtworkAmbience`, `rememberArtworkAccent`) and continuous shapes from `com.kyant.shapes` (`Capsule()`, `RoundedRectangle(28.dp)`). Never make glass sample a layer it is inside: only use the provided frames.
- `LargeTitleScaffold` / `GlassTopBarScaffold` padding goes into the list's **`contentPadding`**, never `Modifier.padding`, so content scrolls under the glass bars and the floating tab bar.
- **No hard-coded colours in screens.** Use `MaterialTheme.colorScheme.*`, `AppTheme.colors.*` or `glassTint()`.
- Typography: `headlineMedium` screen titles (28sp bold), `titleMedium` row titles, `bodyLarge` body, `bodySmall` secondary (13sp). Spacing from `Spacing`.
- Every screen/component gets a `@ThemePreviews` preview (light + dark).
- Screens draw their own top bar; the shell Scaffold passes zero content insets.
- `domain/policy` is pure Kotlin: no `android.*` imports.
- Time: inject `core.Clock`, never call `System.currentTimeMillis()` directly.
- Room: tables are `podcasts`, `podcast_settings`, `episodes`, `episode_fts`, `downloads`, `backfill_state`, `queue_items`, `listening_sessions`, `recent_searches`, `catalog_cache`. Any entity change = bump version + real Migration + commit the new `app/schemas` JSON. **Never** destructive migration. **Never** `OnConflictStrategy.REPLACE` on podcasts/episodes (cascading FKs delete child rows); use `@Upsert`/`@Update`/IGNORE.
- Never overwrite episode user state (playState, positionMs, playedAt, isStarred, autoDownloadBlocked) from feed data.
- Settings: `SettingsRepository.settings` (Flow<GlobalSettings>) and `update { it.copy(...) }`. Key names are part of the backup format; don't rename.
- Secrets (`PODCASTINDEX_*`, signing) live in git-ignored `local.properties`, exposed via `BuildConfig`.

## Layout (`app/src/main/java/com/steve/mcpodface/`)
`core/` Clock, DI qualifiers · `data/db` entities, DAOs, AppDatabase · `data/prefs` settings · `data/network|feed|search|backup` · `domain/policy` · `download/` · `refresh/` · `backfill/` · `playback/` · `ui/theme` · `ui/components` · `ui/navigation` (Routes, AppShell) · `ui/<screen>/`.
Tests: `app/src/test/` (JVM + Robolectric, emulating SDK 36 via `robolectric.properties`). Feed fixtures in `app/src/test/resources/feeds/`.

## Commands (Git Bash; set `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` first)
```
./gradlew :app:compileDebugKotlin        # fast check (parallel agents: use only this)
./gradlew :app:testDebugUnitTest         # unit tests
./gradlew :app:lintDebug                 # lint
./gradlew :app:installDebug              # to connected phone(s)
```
Only one Gradle build at a time on this machine.
