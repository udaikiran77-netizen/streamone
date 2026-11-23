# Phase 4 — CloudStream extensions engine: implementation notes

StreamOne can now load **unmodified CloudStream `.cs3` extensions**: add a
CloudStream repository URL in the Extensions screen, install plugins, and
browse/search/play through them with the same UI and player as the Vega
providers.

## How it works

- **Vendored library**: the complete Android source set of the upstream
  CloudStream *library* module (`commonMain` + `jvmCommonMain` + `androidMain`
  from recloudstream/cloudstream, 148 files) is copied into
  `app/src/main/java/com/lagradost/...` under the **original package names**
  (`com.lagradost.cloudstream3`, `com.lagradost.api`), so prebuilt `.cs3`
  dexes link against the exact classes/methods they were compiled with:
  `MainAPI`, `APIHolder`, all `SearchResponse`/`LoadResponse` types,
  `Episode`, `ExtractorApi`/`ExtractorLink` (+ the full built-in extractor
  registry `extractorApis`, pre-populated by the library itself),
  `BasePlugin`, Jackson `mapper`, the NiceHttp-backed `app` client, Rhino
  JS interpreter/unpackers, M3u8 helpers, meta providers, sync providers.
- **Plugin class**: `com.lagradost.cloudstream3.plugins.Plugin` is copied
  verbatim from the upstream *app* module.
- **Loading** (`extensions/PluginEngine.kt`) mirrors upstream
  `PluginManager.loadPlugin`: file set read-only → `PathClassLoader` with the
  app class loader as parent → read `manifest.json` from the plugin zip →
  parse as `BasePlugin.Manifest` (Jackson, via the library's own
  `AppUtils.parseJson`) → instantiate `pluginClassName` → `filename` set →
  resources attached when `requiresResources` → `Plugin.load(context)` (or
  `BasePlugin.load()`). Providers register themselves in
  `APIHolder.allProviders` via `BasePlugin.registerMainAPI`, exactly like
  upstream; newly registered APIs get `init()` called. Unload runs
  `beforeUnload()` and removes the plugin's APIs/extractors.
- **Repositories** (`Repository`/`SitePlugin` in
  `plugins/RepositoryModels.kt`) are the upstream app-module models copied
  verbatim (fields + Jackson/kotlinx annotations), so real repository JSON
  parses identically. Repo URL → repository JSON → each `pluginLists` URL →
  array of `SitePlugin` → download `.cs3` → SHA-256 verified against
  `fileHash` when present (upstream `"sha256-…"` format) → installed under
  `filesDir/Extensions/<repo>/<internalName>.cs3`, mirroring upstream layout.
  `cloudstreamrepo://` and `https://cs.repo/` prefixes are unwrapped like
  upstream `parseRepoUrl`.
- **Bridge** (`extensions/ExtensionBridge.kt`): each `MainAPI` is wrapped and
  mapped onto StreamOne's existing models — `getMainPage` sections and
  `search` → `Post`, `load` (`Movie/TvSeries/Anime/LiveStreamLoadResponse`)
  → detail info + episode list, `loadLinks` → `StreamItem` (headers via
  `ExtractorLink.getAllHeaders()`, so Referer/User-Agent reach the player;
  `ExtractorLinkPlayList` expanded per item; subtitles → `TextTrack`).
  Playback reuses the Phase 3 ExoPlayer screen.
- **Persistence**: repos + installed-plugin metadata in
  `filesDir/extensions.json` (org.json). Installed plugins auto-load at app
  start on a background coroutine; `com.lagradost.api.setContext(...)` is
  called in `MainActivity.onCreate` so WebView-based extractors work.

## Toolchain / dependency decisions

- **Kotlin bumped 2.0.20 → 2.2.21** (android + compose + serialization
  plugins). Required: the library sources use `kotlin.time.Clock`/`Instant`
  (stable since Kotlin 2.2) in public signatures (e.g.
  `Episode.addDate(Instant)`), so compiling them with 2.0 would have changed
  binary signatures vs. real extensions. AGP stays 8.5.2, Gradle wrapper
  stays 8.9 (both inside KGP 2.2's supported range).
- Dependency versions match the fork's `gradle/libs.versions.toml`:
  jackson-module-kotlin 2.13.1 (upstream pins it for minSdk < 26),
  kotlinx-serialization-json 1.11.0, kotlinx-datetime 0.8.0,
  kotlinx-io-core 0.9.1, atomicfu 0.33.0, coroutines 1.11.0, ktor-http 3.5.0,
  jsoup 1.22.1, ksoup 0.2.6, cryptography 0.6.0, rhino 1.8.1,
  androidx.annotation 1.10.0. JitPack added for NiceHttp 0.4.18
  (`com.github.Blatzar:NiceHttp`) and NewPipeExtractor v0.26.3 — the exact
  coordinates upstream uses. kotlin-reflect pinned to the Kotlin version
  (2.2.21; upstream uses its Kotlin version the same way).
- `-Xskip-metadata-version-check` is enabled so libraries published with a
  newer Kotlin metadata version than 2.2 (e.g. if a kotlinx release was built
  with Kotlin 2.3/2.4) still compile; all APIs actually used are stable.

## Expect/actual merges (only structural change to vendored sources)

The library is a KMP module; StreamOne is a plain Android module, so the
handful of `expect`/`actual` pairs were merged into single Android
declarations with **identical signatures** (default argument values from the
`expect` declarations were restored onto the merged declarations, e.g.
`WebViewResolver`'s constructor and `resolveUsingWebView` overloads):

- `com.lagradost.api.Log` — Android actual kept.
- `com.lagradost.api.ContextHelper` — Android actual kept.
- `cloudstream3.network.WebViewResolver` — Android actual kept.
- `cloudstream3.extractors.YoutubeExtractor` — JVM actual kept, plus the
  three subclasses (`YoutubeShortLink/Mobile/NoCookieExtractor`) that lived
  next to the `expect` declaration.
- `cloudstream3.utils.Coroutines` — `runOnMainThreadNative` (Android actual),
  `workerDispatcher = Dispatchers.IO` and the `WorkerThread` typealias
  (JVM actuals) inlined.
- `cloudstream3.utils.SubtitleHelperPlatform` — Android actuals inlined.
- `cloudstream3.mvvm.ArchComponentExt.platformThrowAbleToResource` — JVM
  actual inlined.

No other vendored file was modified.

## Stubbed / pruned APIs

- **`com.lagradost.api.BuildConfig`** (handwritten): upstream generates this
  with BuildKonfig from the maintainer's secrets. Only two fields are
  referenced by the library (`MDL_API_KEY`, `TRAKT_CLIENT_ID`); both are
  empty strings here, so the MyDramaList/Trakt meta providers simply find no
  key and return nothing instead of crashing.
- **`VideoClickAction` / `VideoClickActionHolder` / `UiText`**
  (`cloudstream3.actions`, `cloudstream3.utils.UiText`): upstream these live
  in the app module and are wired to the player UI (activities, dialogs,
  external-player intents). StreamOne ships minimal shape-compatible stubs
  so `Plugin.registerVideoClickAction(...)` keeps its exact signature and
  plugins calling it don't crash; registered actions are stored but never
  executed. Extensions that *subclass* `VideoClickAction` and override its
  app-UI methods will not link — acceptable: virtually all content
  extensions only register `MainAPI`/`ExtractorApi`.
- **Repository shortcodes**: upstream `parseRepoUrl` also resolves
  cutt.ly / py.md shortlinks over the network. StreamOne supports full URLs
  and the `cloudstreamrepo://` / `cs.repo` prefixes only.
- **Torrent/magnet streams**: `TorrentLoadResponse` has no episode mapping
  and torrent/magnet `ExtractorLink`s are surfaced as stream entries the
  built-in player cannot play (same as upstream, which hands them to
  external apps). No torrent client is bundled.
- **Plugin settings screens** (`Plugin.openSettings`) are not invoked;
  upstream binds them to its settings UI.

## Open risks

- **First CI build is the real compile check** (no Android SDK on the build
  machine used to author this). Most likely friction points, in order:
  a kotlinx dependency published with metadata/APIs newer than Kotlin 2.2
  (mitigated by `-Xskip-metadata-version-check`), KMP root artifacts
  (ksoup, cryptography, kotlinx-io) resolving their Android variants via
  Gradle module metadata, and JitPack availability of NiceHttp /
  NewPipeExtractor at CI time.
- **atomicfu is used without its bytecode-transformer Gradle plugin.** The
  vendored code only uses `kotlinx.atomicfu.locks` (`SynchronizedObject`,
  `synchronized`) and list wrappers, which work untransformed on JVM/Android;
  if a future library file uses `kotlinx.atomicfu.atomic()` delegates, the
  plugin would be needed for correctness under contention.
- **Binary compatibility** is best-effort: signatures were preserved
  verbatim, but extensions compiled against a *different* CloudStream
  library version than this vendored snapshot may reference APIs that were
  added/removed upstream (upstream has the same caveat between its own
  stable/prerelease tracks — see the `@Prerelease` annotation in MainAPI).
- NewPipeExtractor and some extractors use Java 8+ APIs that may need core
  library desugaring on API 24–25 devices at runtime; compile is unaffected.
- Plugins are loaded with the app's class loader as parent (same as
  upstream), so a plugin that bundles its own copies of library classes is
  shadowed by StreamOne's vendored ones — identical to upstream behaviour.
