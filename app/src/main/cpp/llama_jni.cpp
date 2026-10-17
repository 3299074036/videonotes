// llama_jni.cpp — 视记本地 LLM 的 JNI 桥（M7a）。
//
// 对应 Kotlin 契约：com.zola.videonotes.data.llm.local.LlamaCpp（object 单例，
// native 方法为实例方法，JNI 签名第二个参数为 jobject thiz）。
//
// 线程模型：
// - generate 在调用线程同步执行，采样循环直接回调 TokenCallback.onToken
//   （同一线程，该线程发起 JNI 调用时已 attach，无需 AttachCurrentThread）。
// - requestCancel 通过 std::atomic<bool> 跨线程置位，generate 循环中检查。
// - 约定：Kotlin 侧（LocalLlmEngine）保证同一 handle 不并发 generate，
//   generate 进行中不调用 unloadModel。
//
// 异常安全：所有 JNI 入口用 try/catch 包裹，不抛 C++ 异常过边界；
// 错误通过返回值 + lastError()（中文）传递。

#include <jni.h>

#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "llama.h"

#define LLM_TAG "VideoNotesLlm"
#define LLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LLM_TAG, __VA_ARGS__)

// 稳态时钟毫秒（性能打点用，不受系统时间调整影响）
inline int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

namespace {

// native 模型句柄：new 分配，指针以 jlong 交给 Kotlin；unloadModel 时 delete。
struct LlamaHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    uint32_t       n_ctx = 0;
    std::atomic<bool> cancel{false};

    ~LlamaHandle() {
        if (ctx)   { llama_free(ctx);        ctx   = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
    }

    LlamaHandle(const LlamaHandle&) = delete;
    LlamaHandle& operator=(const LlamaHandle&) = delete;
    LlamaHandle() = default;
};

JavaVM* g_vm = nullptr;  // 保留，供将来后台线程回调扩展用

std::mutex g_err_mu;
std::string g_last_error;

// 最近一次 generate 的性能汇总（Kotlin 侧经 lastPerfSummary() 取，用于落盘日志）
std::mutex g_perf_mu;
std::string g_last_perf;

// prefill/decode 实时进度（Kotlin 侧经 lastProgress() 轮询，用于卡死时定位卡在第几个 batch）
std::mutex g_prog_mu;
std::string g_last_progress;

void set_last_error(const char* msg) {
    std::lock_guard<std::mutex> lock(g_err_mu);
    g_last_error = msg ? msg : "";
}

void set_last_perf(const std::string& s) {
    std::lock_guard<std::mutex> lock(g_perf_mu);
    g_last_perf = s;
}

void set_last_progress(const std::string& s) {
    std::lock_guard<std::mutex> lock(g_prog_mu);
    g_last_progress = s;
}

// token → UTF-8 文本片段
bool token_to_text(const llama_vocab* vocab, llama_token token, std::string& out) {
    char buf[128];
    int32_t n = llama_token_to_piece(vocab, token, buf, (int32_t) sizeof(buf), 0, false);
    if (n < 0) {
        std::vector<char> big((size_t) -n);
        n = llama_token_to_piece(vocab, token, big.data(), (int32_t) big.size(), 0, false);
        if (n < 0) return false;
        out.assign(big.data(), (size_t) n);
        return true;
    }
    out.assign(buf, (size_t) n);
    return true;
}

jlong load_impl(const char* path, int32_t n_ctx, int32_t n_threads) {
    const int64_t t0 = now_ms();
    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    if (!model) {
        set_last_error("模型文件加载失败：文件不存在、损坏或非 GGUF 格式");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = n_ctx > 0 ? (uint32_t) n_ctx : cparams.n_ctx;
    cparams.n_threads      = n_threads > 0 ? n_threads : cparams.n_threads;
    cparams.n_threads_batch = cparams.n_threads;
    // 注：v0.7.0 的 Q8_0 KV cache (type_k/type_v) 标为 EXPERIMENTAL，
    // 曾导致 Qwen3-4B decode 首 batch 即失败，已回退到默认 F16。
    // 若 F16 仍 OOM，再考虑减小 n_ctx。

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        set_last_error("模型上下文初始化失败：内存不足或 n_ctx 过大");
        return 0;
    }

    LlamaHandle* h = new (std::nothrow) LlamaHandle();
    if (!h) {
        llama_free(ctx);
        llama_model_free(model);
        set_last_error("内存不足，无法分配模型句柄");
        return 0;
    }
    h->model = model;
    h->ctx   = ctx;
    h->n_ctx = llama_n_ctx(ctx);
    const int64_t load_ms = now_ms() - t0;
    const char* slash = std::strrchr(path, '/');
    LLM_LOGI("[llm] loadModel: %s in %lldms (threads=%d, n_ctx=%u)",
             slash ? slash + 1 : path, (long long) load_ms,
             (int) cparams.n_threads, h->n_ctx);
    return (jlong) h;
}

// 返回码：0=正常结束（EOS / 达 maxTokens / 上下文用尽）；3=取消；其他=错误
jint generate_impl(JNIEnv* env, LlamaHandle* h, const char* prompt_utf8,
                   int32_t max_tokens, float temperature, jobject callback) {
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    if (!vocab) {
        set_last_error("取模型词表失败");
        return 10;
    }

    // 1. prompt 分词（Qwen3 chat template 已在 Kotlin 侧拼好，直接 tokenize）
    const int64_t t_tok0 = now_ms();
    const int32_t text_len = (int32_t) std::strlen(prompt_utf8);
    int32_t n_prompt = llama_tokenize(vocab, prompt_utf8, text_len,
                                      nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt >= 0) {  // 0 或正数表示异常（正常应返回负的所需长度）
        set_last_error("prompt 分词失败");
        return 11;
    }
    n_prompt = -n_prompt;
    if (n_prompt >= (int32_t) h->n_ctx - 8) {
        set_last_error("prompt 过长，超出模型上下文长度");
        return 12;
    }
    std::vector<llama_token> prompt_tokens((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt_utf8, text_len, prompt_tokens.data(),
                       n_prompt, true, true) < 0) {
        set_last_error("prompt 分词失败");
        return 11;
    }
    const int64_t tokenize_ms = now_ms() - t_tok0;

    // 2. 采样器链：temperature + top-p + 分布采样（temperature<=0 时 temp 为 noop → greedy）
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) {
        set_last_error("采样器初始化失败");
        return 13;
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // 3. 回调：取 TokenCallback.onToken 方法 ID，转全局引用
    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cb_class);
    if (!on_token) {
        llama_sampler_free(smpl);
        set_last_error("找不到 TokenCallback.onToken 方法");
        return 14;
    }
    jobject cb_global = env->NewGlobalRef(callback);
    if (!cb_global) {
        llama_sampler_free(smpl);
        set_last_error("创建回调引用失败（内存不足）");
        return 14;
    }

    // 4. batch（手动管理 pos，llama_batch_get_one 的 pos 从 0 起不适用于续写）
    const int32_t n_batch_max = 512;
    llama_batch batch = llama_batch_init(n_batch_max, 0, 1);
    auto fill_batch = [&](const llama_token* toks, int32_t n, int32_t pos_base) {
        batch.n_tokens = n;
        for (int32_t i = 0; i < n; ++i) {
            batch.token[i]    = toks[i];
            batch.pos[i]      = pos_base + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]   = (i == n - 1);  // 只取最后一个位置的 logits
        }
    };

    jint rc = 0;
    int32_t n_past = 0;
    int32_t n_gen = 0;  // 采样阶段成功 decode 的 token 数（生成速度口径）

    // 5. prompt 分批 decode（每 batch 打一行，卡死时 logcat 最后一行即卡住位置）
    const int64_t t_pre0 = now_ms();
    const int32_t n_batches = (n_prompt + n_batch_max - 1) / n_batch_max;
    {
        char prog0[128];
        std::snprintf(prog0, sizeof(prog0), "prefill 0/%d batch, 0/%d tok", n_batches, n_prompt);
        set_last_progress(prog0);
    }
    for (int32_t off = 0, bi = 0; off < n_prompt && rc == 0; off += n_batch_max, ++bi) {
        if (h->cancel.load(std::memory_order_relaxed)) {
            set_last_error("生成已取消");
            rc = 3;
            break;
        }
        const int32_t n_eval = std::min(n_batch_max, n_prompt - off);
        fill_batch(prompt_tokens.data() + off, n_eval, n_past);
        const int64_t t_b0 = now_ms();
        if (llama_decode(h->ctx, batch) != 0) {
            char msg[256];
            std::snprintf(msg, sizeof(msg),
                "prompt 编码失败（prompt %d tokens，已处理 %d，可能内存不足）",
                n_prompt, n_past);
            set_last_error(msg);
            rc = 15;
            break;
        }
        n_past += n_eval;
        const int64_t batch_ms = now_ms() - t_b0;
        LLM_LOGI("[llm] prefill batch %d/%d: %d tok in %lldms",
                 bi + 1, n_batches, n_eval, (long long) batch_ms);
        char prog[128];
        std::snprintf(prog, sizeof(prog), "prefill %d/%d batch, %d/%d tok",
                      bi + 1, n_batches, n_past, n_prompt);
        set_last_progress(prog);
    }
    const int64_t prefill_ms = now_ms() - t_pre0;
    const int32_t n_prefill_done = n_past;  // 实际完成编码的 prompt token 数

    // 6. 采样循环
    set_last_progress("decode 进行中…");
    const llama_token eos = llama_vocab_eos(vocab);
    std::string piece;
    const int64_t t_dec0 = now_ms();
    for (int32_t i = 0; i < max_tokens && rc == 0; ++i) {
        if (h->cancel.load(std::memory_order_relaxed)) {
            set_last_error("生成已取消");
            rc = 3;
            break;
        }
        const llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        llama_sampler_accept(smpl, tok);
        if (tok == eos) break;  // 正常结束

        if (token_to_text(vocab, tok, piece) && !piece.empty()) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(cb_global, on_token, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                set_last_error("回调 onToken 时发生异常");
                rc = 16;
                break;
            }
        }
        if (n_past + 1 >= (int32_t) h->n_ctx) break;  // 上下文用尽，正常结束
        fill_batch(&tok, 1, n_past);
        if (llama_decode(h->ctx, batch) != 0) {
            set_last_error("解码失败");
            rc = 15;
            break;
        }
        n_past += 1;
        n_gen += 1;
    }
    const int64_t decode_ms = now_ms() - t_dec0;

    // 性能汇总：只在结束时打一次（不逐 token 打，避免刷屏）
    const double prefill_tps = prefill_ms > 0 ? n_prefill_done * 1000.0 / prefill_ms : 0.0;
    const double decode_tps  = decode_ms  > 0 ? n_gen * 1000.0 / decode_ms : 0.0;
    char perf[512];
    std::snprintf(perf, sizeof(perf),
        "[llm] tokenize %lldms | prefill %d tok in %lldms (%.1f tok/s) | "
        "decode %d tok in %lldms (%.1f tok/s) | rc=%d",
        (long long) tokenize_ms,
        n_prefill_done, (long long) prefill_ms, prefill_tps,
        n_gen, (long long) decode_ms, decode_tps,
        rc);
    set_last_perf(perf);
    LLM_LOGI("%s", perf);

    env->DeleteGlobalRef(cb_global);
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    return rc;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_loadModel(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads) {
    try {
        if (!modelPath) {
            set_last_error("模型路径为空");
            return 0;
        }
        const char* cpath = env->GetStringUTFChars(modelPath, nullptr);
        if (!cpath) {
            set_last_error("读取模型路径失败");
            return 0;
        }
        const jlong h = load_impl(cpath, nCtx, nThreads);
        env->ReleaseStringUTFChars(modelPath, cpath);
        return h;
    } catch (...) {
        set_last_error("native 内部异常（loadModel）");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_unloadModel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        delete reinterpret_cast<LlamaHandle*>(handle);  // 空指针 delete 安全
    } catch (...) {
        // 析构不抛异常；此处仅为保险
    }
}

JNIEXPORT jint JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_generate(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring prompt,
        jint maxTokens, jfloat temperature, jobject callback) {
    try {
        LlamaHandle* h = reinterpret_cast<LlamaHandle*>(handle);
        if (!h || !h->ctx || !h->model) {
            set_last_error("模型未加载");
            return 1;
        }
        if (!prompt || !callback) {
            set_last_error("参数错误：prompt 或回调为空");
            return 2;
        }
        if (maxTokens <= 0) {
            set_last_error("参数错误：maxTokens 须为正数");
            return 2;
        }
        h->cancel.store(false, std::memory_order_relaxed);
        const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
        if (!cprompt) {
            set_last_error("读取 prompt 失败");
            return 2;
        }
        const jint rc = generate_impl(env, h, cprompt, maxTokens, temperature, callback);
        env->ReleaseStringUTFChars(prompt, cprompt);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（generate）");
        return 99;
    }
}

JNIEXPORT void JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_requestCancel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    try {
        LlamaHandle* h = reinterpret_cast<LlamaHandle*>(handle);
        if (h) h->cancel.store(true, std::memory_order_relaxed);
    } catch (...) {
    }
}

JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_lastError(
        JNIEnv* env, jobject /*thiz*/) {
    try {
        std::lock_guard<std::mutex> lock(g_err_mu);
        return env->NewStringUTF(g_last_error.c_str());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_lastPerfSummary(
        JNIEnv* env, jobject /*thiz*/) {
    try {
        std::lock_guard<std::mutex> lock(g_perf_mu);
        return env->NewStringUTF(g_last_perf.c_str());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_lastProgress(
        JNIEnv* env, jobject /*thiz*/) {
    try {
        std::lock_guard<std::mutex> lock(g_prog_mu);
        return env->NewStringUTF(g_last_progress.c_str());
    } catch (...) {
        return nullptr;
    }
}

}  // extern "C"
