package io.github.yomikomi.translation

class HyMtTranslationEngine : TranslationEngine {
    override fun translate(request: TranslationRequest): List<TranslationSegment> {
        val prompt = TranslationPrompts.render(request)
        val output = NativeLlama.generate(prompt, TranslationProtocol.grammar(request.segments))
        return TranslationProtocol.parse(output, request.segments)
    }
    override fun cancel() = NativeLlama.cancel()
}
