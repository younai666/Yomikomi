#include <jni.h>
#include <llama.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <chrono>

namespace {
std::atomic<bool> canceled{false};
std::unique_ptr<llama_model, decltype(&llama_model_free)> model(nullptr, llama_model_free);
std::unique_ptr<llama_context, decltype(&llama_free)> context(nullptr, llama_free);
bool abort_inference(void *) { return canceled.load(); }
using Clock = std::chrono::steady_clock;
void report(JNIEnv * env, jobject receiver, int stage, Clock::time_point start, int tokens) {
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
    auto type = env->GetObjectClass(receiver);
    auto method = env->GetMethodID(type, "onNativeStage", "(IJI)V");
    env->CallVoidMethod(receiver, method, static_cast<jint>(stage), static_cast<jlong>(elapsed), static_cast<jint>(tokens));
    env->DeleteLocalRef(type);
}
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string utf(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path) {
    context.reset();
    model.reset();
    canceled = false;
    // Our Kotlin boundary logs timings and sanitized failures, never upstream free-form text.
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    model.reset(llama_model_load_from_file(utf(env, path).c_str(), mp));
    if (!model) { fail(env, "GGUF load failed"); return; }
    auto cp = llama_context_default_params();
    cp.n_ctx = 8192;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_inference;
    cp.abort_callback_data = nullptr;
    context.reset(llama_init_from_model(model.get(), cp));
    if (!context) { model.reset(); fail(env, "Inference context allocation failed"); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeGenerate(
    JNIEnv * env, jobject receiver, jbyteArray input, jstring grammar, jint max_tokens) {
    try {
        if (!context) throw std::runtime_error("Model not loaded");
        canceled = false;
        std::string prompt(env->GetArrayLength(input), '\0');
        env->GetByteArrayRegion(input, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
        const llama_chat_message message{"user", prompt.c_str()};
        const char * tmpl = llama_model_chat_template(model.get(), nullptr);
        int length = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
        if (length < 0) throw std::runtime_error("Unsupported model chat template");
        std::vector<char> formatted(length + 1);
        llama_chat_apply_template(tmpl, &message, 1, true, formatted.data(), formatted.size());
        const auto * vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, formatted.data(), length, nullptr, 0, true, true);
        if (count <= 0 || count + max_tokens > static_cast<int>(llama_n_ctx(context.get())))
            throw std::runtime_error("Screen exceeds model context; reduce visible text");
        std::vector<llama_token> tokens(count);
        llama_tokenize(vocab, formatted.data(), length, tokens.data(), count, true, true);
        llama_memory_clear(llama_get_memory(context.get()), true);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto grammar_text = utf(env, grammar);
        auto * constrained = llama_sampler_init_grammar(vocab, grammar_text.c_str(), "root");
        if (!constrained) throw std::runtime_error("Invalid output grammar");
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0, 0));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        auto prefill_start = Clock::now();
        for (int pos = 0; pos < count; pos += 256) {
            if (canceled) throw std::runtime_error("Canceled");
            auto batch = llama_batch_get_one(tokens.data() + pos, std::min(256, count - pos));
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Prompt evaluation failed");
        }
        report(env, receiver, 0, prefill_start, count);
        auto decode_start = Clock::now();
        int generated = 0;
        std::string output;
        bool finished = false;
        for (int i = 0; i < max_tokens; ++i) {
            if (canceled) throw std::runtime_error("Canceled");
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { finished = true; break; }
            char buffer[256];
            int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            if (size < 0) throw std::runtime_error("Token conversion failed");
            output.append(buffer, size);
            generated++;
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Generation failed");
        }
        report(env, receiver, 1, decode_start, generated);
        if (!finished) throw std::runtime_error("Translation exceeded output limit");
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeCancel(JNIEnv *, jobject) { canceled = true; }
extern "C" JNIEXPORT void JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeClose(JNIEnv *, jobject) { context.reset(); model.reset(); }
