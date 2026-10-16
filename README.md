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

- One real OCR backend: [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), with official [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) Japanese/English/Russian LSTM data. Source language selects the language data; automatic mode combines the three.
- Text lines retain physical display coordinates. Recursive whitespace cuts establish horizontal reading order. Session-local IDs match repeated text by geometry and survive small OCR coordinate changes.
- [llama.cpp](https://github.com/ggml-org/llama.cpp) runs the actual model through JNI on a background worker. Each screen is submitted once as ordered `[ID] → source text` lines. Output retains these markers with translated text. Previous-screen source/translation pairs provide context without introducing old IDs. The native grammar fixes every current ID and its order; parsing rejects missing, extra, reordered, empty or embedded protocol markers before rendering.
- API translation uses one non-streaming OpenAI-compatible Chat Completions POST per screen, sharing the prompt renderer and ID parser with local translation. DeepSeek uses non-thinking mode. Truncated, malformed or incorrectly mapped output never reaches the overlay. Requests have connect/read timeouts and are disconnected on cancellation. Errors are reported without silently switching engines or retrying paid requests.
- The preceding displayed screen supplies up to 2,400 characters of source/translation context for 30 seconds. It is reused only for continuous pages in the same package, with the same language direction, active prompt and backend/endpoint/model. Local KV memory is cleared before each request.
- A touch-through accessibility overlay covers recognized source bounds with sampled background colors and fitted translations. Scrolling/window changes cancel stale work; app/window changes dismiss overlays. Rotation cancels work. Protected content cannot be translated.
- No fake translation path, saved screenshots, translation history, MediaProjection fallback, or broad storage/overlay permission. In local mode, text stays on the device and internet access is used for model downloads. When API mode is enabled, recognized text and its short-term context are sent to the configured HTTPS service. Screenshots are never uploaded.

API keys are encrypted with Android Keystore AES-GCM and stored under app-private `noBackupFilesDir`, separately from ordinary preferences. Keys are bound to their HTTPS origin; changing origins requires entering a key for that destination. Redirects are rejected. Saved keys are shown as **已保存**, never repopulated as plaintext in the field, and are sent only in the Authorization header. Language direction, Overlay and provider/prompt preferences persist locally.

## Translation prompts

**Settings → 翻译设置 → 本地模型 → 翻译提示词** provides **通用** (default), **正式**, **网络聊天**, **漫画**, and **自定义**. The four presets share whole-screen and previous-screen context instructions, while differing in register, idiom handling and dialogue style. Previous-screen text is reference material only; every mode outputs just the current screen's IDs in their original order.

Custom mode edits the complete template and saves locally. Available placeholders are `{{source_language}}`, `{{target_language}}`, `{{previous_screen}}` and `{{current_screen}}`. Both screen placeholders must remain so the model receives the whole page and its short-term context. The app prefixes the current language direction and output contract for every mode; custom wording cannot remove the native ID/order constraints. Editing can be dismissed without changing the selected mode.

**第三方 API → 翻译提示词** offers the same four presets and full custom editor. API and local prompt selections/custom drafts are independent. API prompt changes are saved together with the provider form using **完成**; every response must pass the same current-ID and ordering checks.

## Short-term context boundaries

Continuity is a small deterministic heuristic, shared by local and API translation:

- Same actual `packageName`, less than 30 seconds since the preceding translation was displayed, and unchanged source/target language, active preset/custom template and backend. Changing any of these clears context, including changing away and back without translating. Editing an inactive prompt draft or Overlay preferences does not reset the active translation context.
- The OCR body must overlap enough: length-weighted token Dice score at least 0.35, with at least four shared characters of evidence. Latin/Cyrillic words are case/punctuation insensitive; Japanese uses adjacent character pairs. When screenshot bounds are available, the top/bottom eighths are excluded to avoid matching only toolbars.
- Alternatively, a detailed background scene may remain almost unchanged while dialogue changes. A 12×16 RGB grid samples the content area, excluding OCR glyphs and top/bottom chrome. At least 75% of body cells must be comparable and 90% of those must differ by no more than 18 RGB levels on average; aspect ratios must agree within 3%. At least a quarter of usable horizontal neighbour pairs must differ by 24 levels, so a shared flat background or small toolbar cannot establish visual continuity.

Only the preceding **displayed** page and this tiny colour grid stay in memory; screenshots are recycled and never cached. App/config changes clear immediately without waiting for model/network work. Expiry runs on the main handler, not the busy inference worker. A rejected page erases the older cache before translation, so a failed unrelated page cannot revive stale context. Results invalidated while in flight cannot repopulate the cache. A timer for the older page may expire it while a new valid translation is running without discarding that new result.

This is conservative evidence of continuity, not semantic understanding: a related page with entirely new words and substantially different imagery starts fresh. No extra AI request, embedding model or persistent history is used.

## Model downloads

Only configuration, immutable upstream URLs and expected SHA-256 digests are included. **No model weights or OCR language data are packaged in the APK.**

- Translation: [Tencent's official Hy-MT2 GGUF repository](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), `Hy-MT2-1.8B-Q4_K_M.gguf`, revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`.
- OCR: official `tesseract-ocr/tessdata_fast`, revision `87416418657359cb625c412a48b6e1d6d41c29bd`; `eng`, `jpn`, `rus`.

A foreground service downloads over HTTPS, resumes matching byte ranges, restarts safely when Range is ignored, verifies size/SHA-256, then atomically deploys each file. Models are verified again once per process before loading. Failed transfers retain `.part` files; checksum failures never deploy. Files stay in app-private, non-backed-up storage and are removed on uninstall.

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

## Verification

Unit tests cover ordering, stable/duplicate IDs, one-call page submission, response validation, context isolation/expiry/budget, prompt templates and configuration routing, literal placeholder handling, ordered output parsing, download resumption, ignored/invalid Range responses, interrupted transfers and checksum rejection.

After downloading both models on a connected emulator/device:

```powershell
.\gradlew.bat assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.yomikomi.RealPipelineTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.TranslationPromptDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.ApiTranslationDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class io.github.yomikomi.ContextContinuityDeviceTest io.github.yomikomi.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests use real OCR and Hy-MT2, checking Japanese/English/Russian, masked system bars, pixel coordinates, ID mapping, previous-screen context, all five prompt modes and persisted prompt settings. The test APK contains a standalone `io.github.yomikomi.fixture.ScreenFixtureActivity` for manual tile-to-overlay checks; it is not shipped in the app. Avoid `uiautomator dump` or accessibility-tree inspection tools during these checks: they can suppress the app's accessibility service. Mobile MCP's persistent `com.mobilenext.mobilecli.DeviceServer` also holds this automation connection; stop that device-side server before verifying the accessibility pipeline. ADB screenshots and logs are safe for this stage.

API device tests cover whole-screen serialization, all prompts, ID parsing, HTTP errors/redirects, truncated output, cancellation, origin-bound encrypted key storage and independent settings. Live DeepSeek calls are opt-in: after saving a DeepSeek key in the app, run the API test class with `-e liveApi true` to translate a public two-block fixture in all five modes. This uses the supplied API account and sends only the fixture and its public context. No credentials are embedded in either APK or test source.

The external fixture accepts `--ez dark true` for dark-background OCR/overlay checks. After displaying a translation, reopen Yomikomi: its activity start cancels pending work, removes the overlay immediately and clears short-term context. Accessibility events from the app's own overlay remain ignored so it cannot dismiss itself.

For continuity checks, the fixture also accepts `--es page story1`, `story2` and `unrelated`; `--activity-single-top` updates the same activity. Hide the existing overlay with the tile before each change, then translate again. `story1 → story2` should log `context=true`; returning from another app or moving to `unrelated` should log `context=false`. No OCR/translated content is logged by this diagnostic.

The generated launcher artwork and its production prompt are included in [`app/src/main/res/drawable-nodpi/yomikomi_wordmark.png`](app/src/main/res/drawable-nodpi/yomikomi_wordmark.png) and [`docs/launcher-icon-prompt.txt`](docs/launcher-icon-prompt.txt). Android 8+ uses an adaptive icon with lettering inset into the circular safe zone; older versions use the square artwork.

## Current limits

This version targets horizontal, readable text. Vertical Japanese, stylized lettering, overlapping layouts and low-resolution text can be misrecognized or ordered incorrectly. The overlay uses sampled solid backgrounds rather than reconstructing textures. Long translations shrink to their original bounds. Dense screens exceeding the 8,192-token context or 4,096-token output budget report an error instead of silently splitting or dropping blocks. CPU speed and memory use need physical-device validation.

## References and licenses

UI styling draws on [Open Claude UI](https://github.com/Caho1/open-claude-ui) and [Claude Design System Prompt](https://github.com/Trystan-SA/claude-design-system-prompt). Pipeline separation is conceptually informed by [StarFlow's README](https://github.com/xujunjiex/StarFlow); no LGPL source was copied or linked.

Yomikomi is [Apache-2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and notices in the APK's `assets/licenses` directory for dependencies.
