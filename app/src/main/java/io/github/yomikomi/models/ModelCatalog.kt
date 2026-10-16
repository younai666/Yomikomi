package io.github.yomikomi.models

data class ModelAsset(val path: String, val url: String, val bytes: Long, val sha256: String)
data class ModelSpec(val id: String, val title: String, val assets: List<ModelAsset>) {
    val bytes get() = assets.sumOf { it.bytes }
}

object ModelCatalog {
    private const val HF = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/a0c709d9fac510f2c807aa3af52872340dc37a4a"
    private const val TESS = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd"
    val translation = ModelSpec("hy-mt2", "Hy-MT2 · 1.8B", listOf(ModelAsset(
        "Hy-MT2-1.8B-Q4_K_M.gguf", "$HF/Hy-MT2-1.8B-Q4_K_M.gguf", 1_133_080_448,
        "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699")))
    val ocr = ModelSpec("tesseract", "Tesseract · 日/英/俄", listOf(
        ModelAsset("tessdata/eng.traineddata", "$TESS/eng.traineddata", 4_113_088,
            "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        ModelAsset("tessdata/jpn.traineddata", "$TESS/jpn.traineddata", 2_471_260,
            "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"),
        ModelAsset("tessdata/rus.traineddata", "$TESS/rus.traineddata", 3_861_738,
            "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"),
    ))
    val all = listOf(ocr, translation)
}
