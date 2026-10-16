# Yomikomi

Native Android screen translation in Kotlin and Jetpack Compose, with a warm, dark-only interface.

Download the signed APK from [GitHub Releases](https://github.com/younai666/Yomikomi/releases).

## First run

1. Open a model on Home, or **Settings → 翻译设置 → 本地模型**.
2. Download the Tesseract Japanese/English/Russian language pack (10.4 MB). For local translation, also download **Hy-MT2-1.8B Q4_K_M** (1.13 GB). Downloads show progress, support pause/resume across app restarts, and deploy and load automatically after verification.
3. Enable Yomikomi in Android Accessibility settings using the Home control, and add its Quick Settings tile.
4. Choose source/target languages. Open a page in another app, expand Quick Settings and tap **Yomikomi**. Tap again to cancel or dismiss the translation.

For API translation, open **Settings → 翻译设置 → 第三方 API**, choose **DeepSeek**, enter your key and enable **使用第三方 API**, then save with **完成**. Defaults: `https://api.deepseek.com`, `deepseek-flash`. The **OpenAI 兼容** option accepts a compatible HTTPS base URL and model name. API mode still uses local OCR, but requires neither the Hy-MT2 download nor loading its weights. Turn the API switch off to return to local translation.

Screen capture requires **Android 11 / API 30+**. The settings UI remains installable on API 23+. Native ABIs: `arm64-v8a`, `x86_64`. CPU inference only.

## Pipeline

`Quick Settings Tile → AccessibilityService screenshot → Tesseract OCR → reading order + stable IDs → one translation request (Hy-MT2 or API) → ID validation → accessibility overlay`

- One real OCR backend: [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), with official [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) Japanese/English/Russian LSTM data. Loading selects the requested source language directly; automatic mode combines the three. Changing languages reinitializes only when necessary.
- Text lines retain physical display coordinates. Recursive whitespace cuts establish horizontal reading order. Session-local IDs match repeated text by geometry and survive small OCR coordinate changes.
- [llama.cpp](https://github.com/ggml-org/llama.cpp) runs the actual model through JNI on a background worker. Each screen is submitted once as ordered `[ID] 原文：source text` lines, distinct from the output format `[ID] → translated text`. Previous-screen source/translation pairs provide context without introducing old IDs. The native grammar fixes every current ID and its order; parsing rejects missing, extra, reordered, empty or embedded protocol markers before rendering.
- API translation uses one non-streaming OpenAI-compatible Chat Completions POST per screen, sharing the prompt renderer and ID parser with local translation. DeepSeek uses non-thinking mode. Truncated, malformed or incorrectly mapped output never reaches the overlay. Requests have connect/read timeouts and are disconnected on cancellation. Errors are reported without silently switching engines or retrying paid requests.
- The preceding displayed screen supplies up to 2,400 characters of source/translation context for 30 seconds. It is reused only for continuous pages in the same package, with the same language direction, active prompt and backend/endpoint/model. Only requests with valid previous-screen context may reuse the exact matching token prefix in local KV memory. The old suffix, including generated output, is removed; the final prompt token is evaluated again for fresh logits. Unrelated requests and failed/cancelled inference clear the cache.
- A touch-through accessibility overlay covers recognized source bounds with sampled background colors and fitted translations. Screenshot conversion, OCR, color sampling and text layout run on the pipeline worker; screenshot pixels are released before model loading or API/local translation. Before attachment, another accessibility screenshot/OCR verifies that every source line still has the same text and physical bounds (up to four pixels of OCR jitter); changed pages never receive the old overlay. The main thread only attaches/removes/draws prepared layouts. Confirmed activity app switches and display geometry changes invalidate immediately. IME/dialog, same-app window and content/scroll events during work are deferred to the final check, rather than cancelling a slow first load. Visible overlays still dismiss on page/window changes. Protected content cannot be translated.
- No fake translation path, saved screenshots, translation history, MediaProjection fallback, or broad storage/overlay permission. In local mode, text stays on the device and internet access is used for model downloads. When API mode is enabled, recognized text and its short-term context are sent to the configured HTTPS service. Screenshots are never uploaded.

API keys are encrypted with Android Keystore AES-GCM and stored under app-private `noBackupFilesDir`, separately from ordinary preferences. Keys are bound to their HTTPS origin; changing origins requires entering a key for that destination. All store instances serialize atomic reads/writes; origin validation and decryption use the same snapshot. Redirects are rejected. Saved keys are shown as **已保存**, never repopulated as plaintext in the field, and are sent only in the Authorization header. Language direction, Overlay and provider/prompt preferences persist locally; the opacity slider updates visually while dragging and commits once when the gesture finishes.

## Translation prompts

**Settings → 翻译设置 → 本地模型 → 翻译提示词** provides **通用** (default), **正式**, **网络聊天**, **漫画**, and **自定义**. The four presets share whole-screen and previous-screen context instructions, while differing in register, idiom handling and dialogue style. Previous-screen text is reference material only; every mode outputs just the current screen's IDs in their original order.

Custom mode edits the complete template and saves locally. Available placeholders are `{{source_language}}`, `{{target_language}}`, `{{previous_screen}}` and `{{current_screen}}`. Both screen placeholders must remain so the model receives the whole page and its short-term context. The app prefixes the current language direction and output contract for every mode and ends with a direction reminder, keeping the small local model from treating Japanese source lines as completed translations. Custom wording cannot remove the native ID/order constraints. Editing can be dismissed without changing the selected mode.

**第三方 API → 翻译提示词** offers the same four presets and full custom editor. API and local prompt selections/custom drafts are independent. API prompt changes are saved together with the provider form using **完成**; every response must pass the same current-ID and ordering checks.

## Short-term context boundaries

Continuity is a small deterministic heuristic, shared by local and API translation:

- Same actual `packageName`, less than 30 seconds since the preceding translation was displayed, and unchanged source/target language, active preset/custom template and backend. Changing any of these clears context, including changing away and back without translating. Editing an inactive prompt draft or Overlay preferences does not reset the active translation context.
- The OCR body must overlap enough: length-weighted token Dice score at least 0.35, with at least four shared characters of evidence. Latin/Cyrillic words are case/punctuation insensitive; Japanese uses adjacent character pairs. When screenshot bounds are available, the top/bottom eighths are excluded to avoid matching only toolbars.
- Alternatively, a detailed background scene may remain almost unchanged while dialogue changes. A 12×16 RGB grid samples the content area, excluding OCR glyphs and top/bottom chrome. At least 75% of body cells must be comparable and 90% of those must differ by no more than 18 RGB levels on average; aspect ratios must agree within 3%. At least a quarter of usable horizontal neighbour pairs must differ by 24 levels, so a shared flat background or small toolbar cannot establish visual continuity.

The context cache retains only the preceding **displayed** page and this tiny colour grid; screenshots are recycled and never cached. App/config changes clear immediately without waiting for model/network work. Expiry runs on the main handler, not the busy inference worker, with one timer removed when the service is destroyed. A rejected page erases the older cache before translation, so a failed unrelated page cannot revive stale context. Results invalidated while in flight cannot repopulate the cache. A timer for the older page may expire it while a new valid translation is running without discarding that new result. Native cancellation uses a monotonic request version, so entering inference cannot reset an earlier cancellation.

This is conservative evidence of continuity, not semantic understanding: a related page with entirely new words and substantially different imagery starts fresh. No extra AI request, embedding model or persistent history is used.

## Model downloads

Only configuration, immutable upstream URLs and expected SHA-256 digests are included. **No model weights or OCR language data are packaged in the APK.**

- Translation: [Tencent's official Hy-MT2 GGUF repository](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), `Hy-MT2-1.8B-Q4_K_M.gguf`, revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`.
- OCR: official `tesseract-ocr/tessdata_fast`, revision `87416418657359cb625c412a48b6e1d6d41c29bd`; `eng`, `jpn`, `rus`.

A foreground service downloads over HTTPS, resumes matching byte ranges, restarts safely when Range is ignored, verifies size/SHA-256, then atomically deploys each file. A multi-file pack reports complete only after every file is verified, including when reusing existing files. Models are verified again once per process before loading. Failed transfers retain `.part` files; checksum failures never deploy. Files stay in app-private, non-backed-up storage and are removed on uninstall.

## Build

Requirements: JDK 17, Android SDK 36 / Build Tools 36.0.0, NDK `27.3.13750724`, CMake `3.22.1`. Set `ANDROID_HOME` or untracked `local.properties`. The first build downloads the pinned llama.cpp source and the Tesseract AAR; model downloads happen in the installed app.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat installDebug
adb shell am start -n io.github.yomikomi/.MainActivity
```

Use `./gradlew` on macOS/Linux. APK: `app/build/outputs/apk/debug/app-debug.apk`.

For a signed release, create an untracked `keystore.properties` at the project root with your own signing key:

```properties
storeFile=/absolute/path/to/yomikomi-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=yomikomi
keyPassword=YOUR_KEY_PASSWORD
```

Run `./gradlew assembleRelease`. Signed APK: `app/build/outputs/apk/release/app-release.apk`.
Without `keystore.properties`, the release APK is unsigned. Keep the key and passwords outside Git and back them up securely; future updates must use the same signing key. Release builds use a different certificate from local debug builds and cannot install over them.

### Repository contents and local cleanup

- Keep `app/src`, the Gradle build files and Wrapper (`gradle/wrapper`, `gradlew`, `gradlew.bat`), `README.md`, `LICENSE` and `THIRD_PARTY_NOTICES.md`. Dependency license texts under `app/src/main/assets/licenses` are packaged with the app.
- `tools/build-update-fixtures.ps1` generates the small signed APKs used by the optional installer tests below; it is test infrastructure, not an app dependency.
- `keystore.properties` and its referenced signing key are private local release inputs, already ignored by Git. Do not delete them as cache: losing the key prevents publishing compatible updates. Debug builds do not require them.
- `.gradle/`, `.kotlin/`, `app/.cxx/` and `app/build/` are ignored, generated data. Stop Gradle (`./gradlew --stop`) before removing them. The next build recreates them; deleting `.gradle/native` also requires downloading and recompiling the pinned llama.cpp dependency. None of these directories contains the installed app's private downloaded models.
- Build verification will recreate caches. They can be removed again afterwards; retain `app/build/outputs/apk`, `app/build/reports` and `app/build/test-results` if APKs and verification reports are still needed.

## Verification

Unit tests cover ordering, stable/duplicate IDs, one-call page submission, response validation, context isolation/expiry/budget, prompt templates and configuration routing, literal placeholder handling, ordered output parsing, download resumption, ignored/invalid Range responses, interrupted transfers, multi-file completion and checksum rejection.

After downloading both models on a connected emulator/device:

```powershell
.\gradlew.bat assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.yomikomi.RealPipelineTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.TranslationPromptDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.ApiTranslationDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.ContextContinuityDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.NativeInferenceDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.OverlayPreparationDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.MotionDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests use real OCR and Hy-MT2, checking Japanese/English/Russian, masked system bars, pixel coordinates, ID mapping, previous-screen context, all five prompt modes and persisted prompt settings. The test APK contains a standalone `io.github.yomikomi.fixture.ScreenFixtureActivity` for manual tile-to-overlay checks; it is not shipped in the app. Avoid `uiautomator dump` or accessibility-tree inspection tools during these checks: they can suppress the app's accessibility service. Mobile MCP's persistent `com.mobilenext.mobilecli.DeviceServer` also holds this automation connection; stop that device-side server before verifying the accessibility pipeline. ADB screenshots and logs are safe for this stage.

Regression tests also check that a three-line Japanese page really produces Chinese meanings rather than copied Japanese (the presence of Han characters alone is insufficient); loading the chosen OCR language does not initialize it again during recognition; cancellation before native entry or during prefill is not lost; cached-prefix and fresh inference agree, including changed prompts and cache reset after failures; and recycled screenshots are no longer needed for fitted, contrasting Overlay layouts. UI tests cover animated dialog disposal, API prompt draft round-trips and committing opacity only after a drag ends. Omit `-e class ...` to run the complete device suite; live API, live GitHub and APK installer fixture tests remain opt-in.

In the 2026-10-16 audit on the Android 16 x86_64 emulator, a cold-process, dark three-line Japanese fixture took about 27 s from tile to Chinese Overlay: OCR 135 ms, model verification 2.2 s, model loading 0.6 s, prefill 21 s / 498 tokens, and decode 2.3 s. Overlay preparation and attachment each took 3 ms. Returning to Home during another prefill cancelled native work within about 0.4 s. These are single-run diagnostics, not a controlled before/after benchmark; prefill remains the dominant cost. Exact token-prefix reuse avoids reevaluation without skipping integrity checks or relaxing ID validation, and regression tests compare cached/fresh output. Physical-device performance remains unverified.

### Tile request lifetime and device diagnostics

The tile only triggers `ScreenCaptureService`; the system-bound accessibility service owns the request and the application-level executor owns OCR/model/inference work. Stopping tile listening or destroying `TileService` does not cancel it. There is no short capture/model-load timeout or tile-owned coroutine/Job. The 30-second timer expires only previous-screen context, not the active request. A cancelled request may finish an already running model load for later reuse, but its token prevents inference/Overlay publication. Process death is not recoverable in memory; after re-enabling accessibility, trigger the tile again.

`CAPTURE cancelled.<reason>` records fixed reasons (`tile`, `app_foreground`, `app_switch`, `window_changed`, `page_changed`, `config_changed`, `rotation`, `interrupted`, `service_destroyed`), request, elapsed time, numeric event/window/change types and stage: 1 shade, 2 screenshot, 3 OCR, 4 model load, 5 inference, 6 screenshot/OCR validation, 7 Overlay. `change_deferred` and `validation unchanged=...` distinguish noisy events from actual page changes; `tile_stop_listening`, `tile_destroyed`, `APP trim_memory` and new process-start IDs help diagnose lifecycle/memory issues. On Android 11+, `APP previous_exit` records Android's last exit reason/status/timestamp and PSS/RSS counters for this app only, without descriptions or trace contents. No event text, class/package names or screen contents are logged.

On a development emulator with models downloaded, a tile already added and Yomikomi accessibility enabled, run the opt-in lifecycle test separately:

```powershell
adb shell am instrument -w -e class io.github.yomikomi.CaptureLifecycleDeviceTest -e captureLifecycle true io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
```

It keeps accessibility unsuppressed, delays the application worker beyond six seconds, injects IME/dialog/chat-update events, runs real Russian OCR and Hy-MT2 to Overlay, then changes the page during inference and checks that stale output is rejected. Only Yomikomi's already-enabled service may be rebound if necessary; other services and saved translation settings are preserved. This reproduces the cancellation paths, not Samsung hardware or One UI itself.

API device tests cover whole-screen serialization, all prompts, ID parsing, HTTP errors/redirects, truncated output, cancellation, origin-bound encrypted key storage and independent settings. Live DeepSeek calls are opt-in: after saving a DeepSeek key in the app, run the API test class with `-e liveApi true` to translate a public two-block fixture in all five modes. This uses the supplied API account and sends only the fixture and its public context. No credentials are embedded in either APK or test source.

The external fixture accepts `--ez dark true` for dark-background OCR/overlay checks. After displaying a translation, reopen Yomikomi: its activity start cancels pending work, removes the overlay immediately and clears short-term context. Accessibility events from the app's own overlay remain ignored so it cannot dismiss itself.

For continuity checks, the fixture also accepts `--es page story1`, `story2` and `unrelated`; `--activity-single-top` updates the same activity. Hide the existing overlay with the tile before each change, then translate again. In runtime logs, `story1 → story2` should record `CONTEXT decision reused=1`; returning from another app or moving to `unrelated` should record `reused=0`. No OCR/translated content is logged by this diagnostic.

The launcher artwork is included in [`app/src/main/res/drawable-nodpi/yomikomi_wordmark.png`](app/src/main/res/drawable-nodpi/yomikomi_wordmark.png). Android 8+ uses an adaptive icon with lettering inset into the circular safe zone; older versions use the square artwork.

## In-app updates

Home's bottom-right **检查更新** capsule checks public GitHub Releases, including published previews, using semantic version precedence. A newer release downloads inside the app; the capsule shows progress, then opens Android's installer. Otherwise it reports **已是最新**. Android may first ask you to allow installation from Yomikomi; granting access returns to the installer. Cancelling installation leaves **安装更新** available without downloading again.

APK selection prefers the device's ABI, then a universal APK. Downloads reuse size/SHA-256 verification and pinned partial-file resumption. Before installation, the app checks its package name, release version, increasing Android version code, supported native ABI and the **same signing certificate as the installed app**. The temporary content URI grants only read access to the update APK directory. Models, logs and API keys are outside this provider. Debug builds cannot update from release-signed APKs.

Checks are manual and use GitHub without credentials. Successful results are cached in memory for one minute. If the shared public API quota is exhausted or the API is unavailable, checks use GitHub's public Releases Atom feed and asset page; APK size comes from HTTPS HEAD and the SHA-256 digest must still be present on the asset page. API cooldown respects GitHub's reset/retry headers. An expired cache or an unavailable/malformed fallback never reports “latest”. Downloads run while the process is alive; after a system kill, checking again resumes a matching partial file, identified by its digest across both metadata sources. The app never launches an installer from the background; a finished download waits until Home resumes. Release publishers must use a `vMAJOR.MINOR.PATCH` tag, increase `versionCode`, retain the signing key and attach a signed `Yomikomi-*.apk` (optionally suffixed with `arm64-v8a` or `x86_64`) with GitHub's SHA-256 asset digest.

`UpdateReleaseTest` covers version precedence and ABI/metadata selection. `UpdateDeviceTest` covers JSON/HTTP failures and, optionally, a simulated release/download followed by real Android APK validation and the system installation confirmation (automatically cancelled). On a disposable Windows emulator, after building and installing the debug/test APKs:

```powershell
.\tools\build-update-fixtures.ps1
adb shell appops set io.github.yomikomi REQUEST_INSTALL_PACKAGES allow
adb shell am instrument -w -e class io.github.yomikomi.UpdateDeviceTest -e apkFixture true io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell appops set io.github.yomikomi REQUEST_INSTALL_PACKAGES default
```

The tiny APK fixtures stay in the OS temporary directory and `/data/local/tmp`; they are not shipped. Restore the previous app-op mode if it differed from `default`. Revoke it after instrumentation exits: Android can terminate the process when install permission is revoked. These fixtures target development version `0.1.1` / code `2`; raise their future version when testing later releases.

## Runtime logs

**Settings → 运行日志 → 下载** opens Android's file picker and exports a UTF-8 `.txt` snapshot, oldest first, including the current log and up to two rotated files. No storage permission is needed. Logs live in app-private, non-backed-up storage; each file is limited to 1 MiB. Background writes use a bounded queue; dropped records are counted, and logging failures never interrupt translation. Uncaught managed exceptions are written synchronously before delegating to Android's existing crash handler.

Records contain UTC time, level, module, fixed state/event names and numeric metadata such as block counts, HTTP/screenshot status codes, download progress and stage durations. Local inference records prefill/decode durations and token counts; prefill counts only newly evaluated tokens, while `prefix_reuse` counts retained tokens. Exception types, frames, causes and suppressed exceptions are retained with bounded depth; **all exception messages are omitted** because third-party errors can contain private input or responses. Screenshots, OCR/translated text, prompts, API keys, request/response bodies, endpoints and foreground-app package names are never logged. The logger does not collect device-wide Logcat. Native llama.cpp free-form logging is disabled; its Kotlin/JNI boundary supplies sanitized diagnostics. Native crashes, forced process termination and system kills may require Android's own diagnostics.

## Current limits

This version targets horizontal, readable text. Vertical Japanese, stylized lettering, overlapping layouts and low-resolution text can be misrecognized or ordered incorrectly. The overlay uses sampled solid backgrounds rather than reconstructing textures. Long translations shrink to their original bounds. Dense screens exceeding the 8,192-token context or 4,096-token output budget report an error instead of silently splitting or dropping blocks. CPU speed and memory use need physical-device validation.

## References and licenses

UI styling draws on [Open Claude UI](https://github.com/Caho1/open-claude-ui) and [Claude Design System Prompt](https://github.com/Trystan-SA/claude-design-system-prompt). Pipeline separation is conceptually informed by [StarFlow's README](https://github.com/xujunjiex/StarFlow); no LGPL source was copied or linked.

Yomikomi is [Apache-2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and notices in the APK's `assets/licenses` directory for dependencies.
