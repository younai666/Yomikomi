#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <ggml-vulkan.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
std::atomic<jlong> cancellation_version{0};
std::atomic<jlong> active_version{0};
std::unique_ptr<llama_model, decltype(&llama_model_free)> model(nullptr, llama_model_free);
std::unique_ptr<llama_context, decltype(&llama_free)> context(nullptr, llama_free);
std::vector<llama_token> cached_prompt;
ggml_backend_dev_t gpu_device = nullptr;
ggml_backend_dev_t model_devices[2] = {nullptr, nullptr};
bool using_vulkan = false;
int offloaded_layers = 0;
int total_layers = 0;
int fallback_reason = 0; // 0 none, 1 unavailable, 2 GPU model allocation, 3 GPU context initialization, 4 no offload.
std::atomic<int> gpu_error{0}; // Fixed categories only; never retain upstream text.
double model_gpu_mib = 0;

void model_log(ggml_log_level, const char * text, void *) {
    // Parse only fixed numeric diagnostics from the pinned llama.cpp; never forward free-form output.
    const char * layers = strstr(text, "offloaded ");
    if (layers) sscanf(layers, "offloaded %d/%d layers to GPU", &offloaded_layers, &total_layers);
    const char * buffer = strstr(text, "model buffer size = ");
    double mib = 0;
    if (buffer && strstr(text, "Vulkan") && sscanf(buffer, "model buffer size = %lf MiB", &mib) == 1) model_gpu_mib += mib;
    if (strstr(text, "ErrorOutOfDeviceMemory") || strstr(text, "No suitable memory type") || strstr(text, "buffer size limit")) gpu_error = 1;
    else if (strstr(text, "ErrorFeatureNotPresent")) gpu_error = 2;
    else if (strstr(text, "ErrorExtensionNotPresent")) gpu_error = 3;
    else if (strstr(text, "ErrorIncompatibleDriver")) gpu_error = 4;
    else if (strstr(text, "ErrorDeviceLost")) gpu_error = 5;
    else if (strstr(text, "Shared memory size too small")) gpu_error = 6;
    else if (strstr(text, "createComputePipelines")) gpu_error = 7;
    else if (strstr(text, "ErrorInitializationFailed")) gpu_error = 8;
}
bool abort_inference(void *) { return cancellation_version.load() != active_version.load(); }
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

void load_model(const std::string & path, ggml_backend_dev_t device) {
    context.reset();
    model.reset();
    cached_prompt.clear();
    offloaded_layers = 0;
    total_layers = 0;
    model_gpu_mib = 0;
    model_devices[0] = device;
    auto mp = llama_model_default_params();
    mp.devices = model_devices; // A null-only list explicitly excludes GPUs in CPU mode.
    mp.n_gpu_layers = device ? -1 : 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    model.reset(llama_model_load_from_file(path.c_str(), mp));
    if (!model) throw std::runtime_error("GGUF model allocation failed");
    if (total_layers == 0) total_layers = llama_model_n_layer(model.get()) + 1;
    if (device && offloaded_layers <= 0) {
        fallback_reason = 4;
        throw std::runtime_error("GPU layers were not offloaded");
    }
    if (device) fallback_reason = 3;
    auto cp = llama_context_default_params();
    cp.n_ctx = 8192;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    cp.n_threads_batch = cp.n_threads;
    cp.offload_kqv = device != nullptr;
    cp.op_offload = device != nullptr;
    cp.abort_callback = abort_inference;
    cp.abort_callback_data = nullptr;
    context.reset(llama_init_from_model(model.get(), cp));
    if (!context) throw std::runtime_error("Inference context allocation failed");
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path, jboolean prefer_gpu) {
    try {
        context.reset(); model.reset(); cached_prompt.clear();
        using_vulkan = false;
        gpu_device = nullptr;
        fallback_reason = prefer_gpu ? 1 : 0;
        gpu_error = 0;
        active_version = cancellation_version.load();
        llama_log_set(model_log, nullptr);
        // Keep registry construction CPU-only. Explicitly register Vulkan only when requested.
        setenv("GGML_DISABLE_VULKAN", "1", 1);
        llama_backend_init();
        ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        const auto model_path = utf(env, path);
        if (prefer_gpu) {
            try {
                auto reg = ggml_backend_reg_by_name("Vulkan");
                if (!reg) {
                    reg = ggml_backend_vk_reg();
                    if (reg) ggml_backend_register(reg);
                }
                if (reg && ggml_backend_reg_dev_count(reg) > 0) {
                    gpu_device = ggml_backend_reg_dev_get(reg, 0);
                    fallback_reason = 2;
                    load_model(model_path, gpu_device);
                    using_vulkan = true;
                    fallback_reason = 0;
                }
            } catch (const std::exception & error) {
                model_log(GGML_LOG_LEVEL_ERROR, error.what(), nullptr);
                // Free partial allocations before CPU fallback; never keep two resident models.
                context.reset(); model.reset(); cached_prompt.clear();
            }
        }
        if (!using_vulkan) load_model(model_path, nullptr);
    } catch (const std::exception & error) {
        context.reset(); model.reset(); cached_prompt.clear();
        fail(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeGpuName(JNIEnv * env, jobject) {
    return env->NewStringUTF(gpu_device ? ggml_backend_dev_description(gpu_device) : "none");
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeBackendInfo(JNIEnv * env, jobject) {
    size_t free = 0, total = 0;
    try { if (gpu_device) ggml_backend_dev_memory(gpu_device, &free, &total); } catch (...) { }
    const jlong values[] = {using_vulkan ? 1 : 0, offloaded_layers, total_layers,
        static_cast<jlong>(free), static_cast<jlong>(total),
        model ? static_cast<jlong>(llama_model_size(model.get())) : 0, fallback_reason,
        static_cast<jlong>(model_gpu_mib * 1024 * 1024), gpu_error.load()};
    auto result = env->NewLongArray(9);
    env->SetLongArrayRegion(result, 0, 9, values);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeGenerate(
    JNIEnv * env, jobject receiver, jbyteArray input, jstring grammar, jint max_tokens, jlong version, jboolean reuse_prefix) {
    try {
        if (!context) throw std::runtime_error("Model not loaded");
        active_version = version;
        if (abort_inference(nullptr)) throw std::runtime_error("Canceled");
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
        report(env, receiver, 3, Clock::now(), count);
        auto memory = llama_get_memory(context.get());
        int shared = 0;
        if (reuse_prefix) {
            // Re-evaluate at least the final prompt token: the current logits may be from an old response.
            while (shared < count - 1 && shared < static_cast<int>(cached_prompt.size()) &&
                   tokens[shared] == cached_prompt[shared]) ++shared;
        }
        if (shared == 0 || !llama_memory_seq_rm(memory, 0, shared, -1)) {
            llama_memory_clear(memory, true);
            shared = 0;
        }
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
        report(env, receiver, 2, prefill_start, shared);
        for (int pos = shared; pos < count; pos += 256) {
            if (abort_inference(nullptr)) throw std::runtime_error("Canceled");
            auto batch = llama_batch_get_one(tokens.data() + pos, std::min(256, count - pos));
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Prompt evaluation failed");
        }
        llama_synchronize(context.get());
        cached_prompt = std::move(tokens);
        report(env, receiver, 0, prefill_start, count - shared);
        auto decode_start = Clock::now();
        int generated = 0;
        std::string output;
        bool finished = false;
        for (int i = 0; i < max_tokens; ++i) {
            if (abort_inference(nullptr)) throw std::runtime_error("Canceled");
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
        llama_synchronize(context.get());
        report(env, receiver, 1, decode_start, generated);
        if (!finished) throw std::runtime_error("Translation exceeded output limit");
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) {
        cached_prompt.clear();
        if (context) llama_memory_clear(llama_get_memory(context.get()), true);
        fail(env, error.what()); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeCancel(JNIEnv *, jobject) { cancellation_version.fetch_add(1); }
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_yomikomi_translation_NativeLlama_nativeCancellationVersion(JNIEnv *, jobject) { return cancellation_version.load(); }
