# Third-party notices

Yomikomi's integration code is independently implemented. StarFlow source is not included.

| Component | Source | License |
| --- | --- | --- |
| llama.cpp, pinned at `620d2225cc436ab0b001e3200e590e3b286ef9ab` | https://github.com/ggml-org/llama.cpp | MIT |
| Tesseract4Android 4.9.0 | https://github.com/adaptech-cz/Tesseract4Android | Apache-2.0 |
| Tesseract OCR 5.5.1 | https://github.com/tesseract-ocr/tesseract | Apache-2.0 |
| Leptonica 1.85.0 | https://github.com/DanBloomberg/leptonica | BSD-2-Clause |
| libpng 1.6.48 | https://github.com/pnggroup/libpng | libpng license |
| IJG libjpeg 9f | https://ijg.org | IJG license |
| AndroidX / Jetpack Compose | https://android.googlesource.com/platform/frameworks/support | Apache-2.0 |
| Kotlin | https://github.com/JetBrains/kotlin | Apache-2.0 |

Native dependency license texts are distributed in `app/src/main/assets/licenses`. This software is based in part on the work of the Independent JPEG Group.

Model data is downloaded separately, never bundled into the APK:

- Tencent Hy-MT2-1.8B Q4_K_M: https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF (Apache-2.0).
- Tesseract fast Japanese/English/Russian data: https://github.com/tesseract-ocr/tessdata_fast (Apache-2.0).

JUnit and AndroidX Test are test-only dependencies; the fixture and test runner are not shipped in the application APK.
