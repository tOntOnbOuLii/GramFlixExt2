Recent changes

- Added local fallback config at `app/src/main/assets/providers.json` preloaded by the plugin, with background refresh from `https://cs.tafili.fr/providers.json`.
- Implemented `RemoteConfig.primeFromAssets` and made `refreshFromNetwork` non-fatal.
- Wired `Plugin.load` to initialize remote config.
- Added Gradle wrapper files and toolchain settings to target JDK 17 (auto-download enabled).

Build status

- Build blocked by network access to Jitpack (`jitpack.io` returns 401), required to resolve the Cloudstream Gradle plugin.
- As soon as Jitpack is reachable (or a mirror is provided), run:
  - `gradlew :app:packageReleasePlugin` to produce the `.cs3` artefact.

Options if Jitpack remains blocked

- Mirror/publish the Cloudstream Gradle plugin to an accessible Maven repo and update `app/build.gradle.kts` buildscript dependency coordinates.
- Or provide the plugin’s resolved dependency as a flatDir/local Maven and add it to `buildscript.repositories`.

