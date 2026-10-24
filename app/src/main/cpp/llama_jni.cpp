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
#include <asm/hwcap.h>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdarg>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <mutex>
#include <new>
#include <string>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "llama.h"

#define LLM_TAG "VideoNotesLlm"
#define LLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LLM_TAG, __VA_ARGS__)

// 关键诊断行：同时写 logcat 和 JNI 桥接 buffer（Kotlin 侧 drain 后写入 llm.log）
#define LLM_LOGI_BRIDGE(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LLM_TAG, __VA_ARGS__); \
    char _bridge_buf[2048]; \
    std::snprintf(_bridge_buf, sizeof(_bridge_buf), __VA_ARGS__); \
    bridge_log(_bridge_buf); \
} while (0)

#define LLM_CPP_TAG "VideoNotesLlmCpp"

// llama.cpp 内部日志转发到 logcat（定位 decode 失败等 native 层错误用）
// 同时桥接到 g_jni_log_buffer，供 Kotlin 侧 drain 后写入 llm.log（App 内可见）
static void bridge_log(const char* line);

// 断言文本捕获（定义在下方匿名命名空间的面包屑一节；此处同置于匿名
// 命名空间以保证声明与定义为同一实体）：ggml/llama 的 assert/abort 类
// 日志行命中关键词时立即追加写进面包屑文件，避免进程 abort 后桥接
// buffer 全丢。
namespace {
void breadcrumb_capture_assert(const char* text);
}  // namespace

static void llama_log_forward(enum ggml_log_level level, const char* text, void* /*user_data*/) {
    if (!text || !*text) return;
    int prio;
    const char* levelTag;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; levelTag = "E"; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  levelTag = "W"; break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; levelTag = "D"; break;
        case GGML_LOG_LEVEL_INFO:
        default:                   prio = ANDROID_LOG_INFO;  levelTag = "I"; break;  // CONT/NONE 按 INFO
    }
    // 纯观测：assert/abort 类日志先即时落盘到面包屑文件（进程随后 abort 时
    // 桥接 buffer 会全丢，这行是唯一能把断言原文带出来的机会）。
    breadcrumb_capture_assert(text);
    __android_log_print(prio, LLM_CPP_TAG, "%s", text);
    char line[2048];
    std::snprintf(line, sizeof(line), "[llama.cpp][%s] %s", levelTag, text);
    bridge_log(line);
}

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

// JNI 日志桥接 buffer：llama.cpp 内部日志 + 关键 LLM_LOGI 诊断行，
// Kotlin 侧经 drainJniLogs() 取走后写入 llm.log（App 内"详细日志"页可见）。
// 注意：单次模型 load 的 llama.cpp 日志 spam 可达 1000+ 行，buffer 太小会把
// probe 起始处的诊断行（pin/cpu_features）挤掉——2026-10-17 实测 200 行不够。
std::mutex g_jni_log_mu;
std::vector<std::string> g_jni_log_buffer;
constexpr size_t kJniLogMaxLines = 4096;

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

std::string get_last_progress() {
    std::lock_guard<std::mutex> lock(g_prog_mu);
    return g_last_progress;
}

// ---------- 原生崩溃面包屑（信号捕获 + 里程碑） ----------
//
// 背景：天玑 9400 上本地生成曾出现进程原生崩溃（系统弹「已停止运行」），
// 且崩溃发生在 JNI 调用内，Kotlin 层日志与 JNI 桥接 buffer 都来不及落盘，
// 事后无从定性。本机制：
// - 里程碑：generate / loadModel / probe 的关键节点把当前阶段写入定长缓冲
//   （仅内存拷贝，无锁无 IO，decode 热路径只更新原子计数）；
// - 信号处理：SIGSEGV/SIGABRT/SIGILL/SIGBUS/SIGFPE 触发时，若崩溃线程正是
//   正在执行 native 调用的线程，只用 write() 把一行（时间戳 / 信号 / 里程碑 /
//   prefill 与 decode 计数）追加到面包屑文件，随后把信号保真链回旧 handler
//   （SA_SIGINFO 时原样传递 siginfo/ucontext，ART 的隐式空指针检查等机制
//   不受影响；旧动作为默认时恢复默认并 re-raise，进程按原样崩溃、系统
//   弹窗与 tombstone 行为不变）。
// - 文件路径由 Kotlin 侧经 setBreadcrumbPath 传入；未设置或非 native
//   执行期崩溃时不写文件，只链回旧 handler。

static char g_breadcrumb_path[512] = {0};
static std::atomic<bool> g_breadcrumb_path_set{false};

/** 正在执行 native 生成类调用的线程 tid（0 = 无）；入口置位、出口清零。 */
static std::atomic<int> g_native_tid{0};

/** 里程碑文本（定长缓冲；写入为单次 memcpy + 长度原子发布，读侧允许撕裂容忍）。 */
static char g_milestone[192] = {0};
static std::atomic<uint32_t> g_milestone_len{0};
static std::atomic<int32_t> g_ms_prefill_tokens{0};
static std::atomic<int32_t> g_ms_decode_tokens{0};

void set_milestone(const char* text) {
    if (!text) text = "";
    size_t n = std::strlen(text);
    if (n >= sizeof(g_milestone)) n = sizeof(g_milestone) - 1;
    std::memcpy(g_milestone, text, n);
    g_milestone[n] = '\0';
    g_milestone_len.store((uint32_t) n, std::memory_order_release);
}

void set_milestonef(const char* fmt, ...) {
    char buf[sizeof(g_milestone)];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    set_milestone(buf);
}

struct sigaction g_old_sigactions[32];
bool g_old_sigaction_valid[32] = {false};

const char* breadcrumb_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGILL:  return "SIGILL";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        default:      return "SIG?";
    }
}

// 以下 append 辅助只做内存拷贝（异步信号安全），供信号 handler 拼行用。
size_t bc_append(char* dst, size_t cap, size_t off, const char* s) {
    while (*s && off + 1 < cap) dst[off++] = *s++;
    return off;
}

size_t bc_append_u64(char* dst, size_t cap, size_t off, unsigned long long v) {
    char tmp[24];
    int n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0) {
        tmp[n++] = (char) ('0' + (v % 10));
        v /= 10;
    }
    while (n > 0 && off + 1 < cap) dst[off++] = tmp[--n];
    return off;
}

void breadcrumb_signal_handler(int sig, siginfo_t* info, void* uctx) {
    // 只记录「正在执行 native 调用的那个线程」自身的崩溃；
    // 其他线程的信号（如 UI 线程的托管代码隐式检查）只链回、不落盘，避免误报。
    const int self_tid = (int) ::syscall(SYS_gettid);
    const int native_tid = g_native_tid.load(std::memory_order_acquire);
    if (native_tid != 0 && native_tid == self_tid &&
        g_breadcrumb_path_set.load(std::memory_order_acquire)) {
        const int fd = ::open(g_breadcrumb_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            char line[640];
            size_t n = 0;
            n = bc_append(line, sizeof(line), n, "CRASH ts=");
            n = bc_append_u64(line, sizeof(line), n,
                              (unsigned long long) ::time(nullptr));
            n = bc_append(line, sizeof(line), n, " sig=");
            n = bc_append_u64(line, sizeof(line), n, (unsigned long long) sig);
            n = bc_append(line, sizeof(line), n, " (");
            n = bc_append(line, sizeof(line), n, breadcrumb_signal_name(sig));
            n = bc_append(line, sizeof(line), n, ") milestone=");
            uint32_t ml = g_milestone_len.load(std::memory_order_acquire);
            if (ml >= sizeof(g_milestone)) ml = (uint32_t) sizeof(g_milestone) - 1;
            for (uint32_t i = 0; i < ml && n + 1 < sizeof(line); ++i) {
                line[n++] = g_milestone[i];
            }
            n = bc_append(line, sizeof(line), n, " prefill_tok=");
            n = bc_append_u64(line, sizeof(line), n,
                              (unsigned long long) (uint32_t)
                                  g_ms_prefill_tokens.load(std::memory_order_relaxed));
            n = bc_append(line, sizeof(line), n, " decode_tok=");
            n = bc_append_u64(line, sizeof(line), n,
                              (unsigned long long) (uint32_t)
                                  g_ms_decode_tokens.load(std::memory_order_relaxed));
            n = bc_append(line, sizeof(line), n, "\n");
            (void) ::write(fd, line, n);
            ::close(fd);
        }
    }
    // 链回旧 handler：SA_SIGINFO 的原样传递 siginfo/ucontext 调用，
    // 使 ART 故障处理等既有机制按原语义工作（它若判定为真实崩溃会自行终止进程；
    // 若返回，表示已处理）。普通 handler 同理直接调用。
    if (sig >= 0 && sig < 32 && g_old_sigaction_valid[sig]) {
        struct sigaction* old = &g_old_sigactions[sig];
        if (old->sa_handler == SIG_IGN) return;
        if ((old->sa_flags & SA_SIGINFO) && old->sa_sigaction != nullptr) {
            old->sa_sigaction(sig, info, uctx);
            return;
        }
        if (old->sa_handler != SIG_DFL && old->sa_handler != nullptr) {
            old->sa_handler(sig);
            return;
        }
    }
    // 旧动作为默认（或未记录）：恢复默认动作并重新 raise，进程按原样崩溃。
    ::signal(sig, SIG_DFL);
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, sig);
    ::pthread_sigmask(SIG_UNBLOCK, &set, nullptr);
    ::raise(sig);
}

// ---------- 断言文本捕获（纯观测，不改任何行为） ----------
//
// ggml 内部断言（GGML_ASSERT / ggml_abort 等）在 abort 前会先把断言原文经
// llama_log_forward 送出。若只攒在桥接 buffer 里等 Kotlin 侧 drain，进程
// 一 abort 内容全丢——这正是第一阶段面包屑只有信号+里程碑、没有断言原文
// 的原因。这里在日志回调入口做关键词过滤：命中行按定长截断后立即以
// O_APPEND 追加写进面包屑文件（与 CRASH 行同文件、独立 LOG 行格式），
// 上报时附到紧随其后的 CRASH 记录文案里（见 LlmCrashBreadcrumb.kt）。
//
// 防重入：写入只用 open/write/close（本身不产生 ggml/llama 日志），另加
// thread_local 标志兜底；路径未设置时直接跳过；写入失败一律静默。
// 普通 [D] 图日志量巨大，不可能命中关键词，不会全量落盘。
//
// 第三阶段收紧：关键词集只保留 ggml_abort / ggml_assert / fatal。真 abort
// 原因已由下方 ggml abort 回调（ABORT 行）负责捕获，LOG 行降为补充通道；
// 去掉裸 assert / abort 是因为 llama.cpp 的例行 DEBUG 行（如
// set_abort_callback 函数的打头日志）含子串 abort 会被误抓成假阳性。

static thread_local bool g_assert_capture_busy = false;

static bool assert_line_matches(const char* text) {
    static const char* const kKeys[] = {
        "ggml_abort", "ggml_assert", "fatal",
    };
    for (const char* key : kKeys) {
        const size_t klen = std::strlen(key);
        // ASCII 大小写不敏感子串匹配（ggml 日志为英文）
        for (const char* p = text; *p; ++p) {
            size_t i = 0;
            while (i < klen && p[i]) {
                char a = p[i];
                if (a >= 'A' && a <= 'Z') a = (char) (a - 'A' + 'a');
                if (a != key[i]) break;
                ++i;
            }
            if (i == klen) return true;
        }
    }
    return false;
}

void breadcrumb_capture_assert(const char* text) {
    if (!text || !*text) return;
    if (g_assert_capture_busy) return;
    if (!g_breadcrumb_path_set.load(std::memory_order_acquire)) return;
    if (!assert_line_matches(text)) return;
    g_assert_capture_busy = true;
    const int fd = ::open(g_breadcrumb_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char line[768];
        size_t n = 0;
        n = bc_append(line, sizeof(line), n, "LOG ts=");
        n = bc_append_u64(line, sizeof(line), n,
                          (unsigned long long) ::time(nullptr));
        n = bc_append(line, sizeof(line), n, " ");
        // 原文定长截断：换行/回车压成空格，保证 LOG 行自身单行
        for (const char* p = text; *p && n + 1 < sizeof(line) - 1; ++p) {
            line[n++] = (*p == '\n' || *p == '\r') ? ' ' : *p;
        }
        n = bc_append(line, sizeof(line), n, "\n");
        (void) ::write(fd, line, n);
        ::close(fd);
    }
    g_assert_capture_busy = false;
}

// ---------- ggml abort 原因捕获 + stderr 兜底（第三阶段，纯观测） ----------
//
// 源码实证（dependencies/llama.cpp/llama.cpp-0.7.0 的 ggml/src/ggml.c）：
// ggml_abort() 把原因格式化为「<file>:<line>: <reason>」后，若未注册
// 全局回调，只 fprintf 到 stderr 再 abort()，根本不走日志回调——这就是
// 第二阶段的日志关键词捕获抓不到真原因（只误抓含子串 abort 的 DEBUG 行）
// 的根源。注册 ggml_set_abort_callback 后，ggml_abort 会先把完整的
// message 交给回调，回调返回后照常 abort()：崩溃表现与系统弹窗不变，
// 我们只需在回调里把 message 落进面包屑文件（ABORT 行），上报时它即
// 真原因原文（见 LlmCrashBreadcrumb.kt 的「原因：」附带）。
//
// 注册时机：路径设置函数 setBreadcrumbPath（JNI）内调用
// install_abort_forensics()。JNI_OnLoad 时路径尚不可知，没法在那里
// 注册；而 setBreadcrumbPath 由 Kotlin 侧 LocalLlmEngine 的 init 在
// 引擎构造时调用，严格早于该引擎的一切 loadModel / generate / 探针
// 调用（上下文创建与首次 decode 都在其后），故保证 abort 发生前回调
// 已就位。幂等：重复调用只注册一次。
//
// stderr 兜底：同一初始化点把 stderr 重定向到面包屑同目录的
// native_stderr.txt，兜住不走 ggml_abort 的其他 abort 路径（如 libc
// 的 assert 失败打印）。关键顺序：先 setvbuf(stderr, NULL, _IONBF, 0)
// 把 stderr 设为无缓冲，再 dup2——否则 fprintf 的内容会停在 stdio
// 缓冲里随 abort 一起丢失，兜底形同虚设。只重定向 stderr，不动
// stdout；保留原 fd 副本；全程失败静默，不影响主流程。

static std::atomic<bool> g_abort_forensics_installed{false};
static thread_local bool g_abort_capture_busy = false;

/** ggml 全局 abort 回调：只写系统调用，不打日志，防重入。 */
void vn_ggml_abort_callback(const char* message) {
    if (g_abort_capture_busy) return;
    if (!message || !*message) return;
    if (!g_breadcrumb_path_set.load(std::memory_order_acquire)) return;
    g_abort_capture_busy = true;
    const int fd = ::open(g_breadcrumb_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char line[768];
        size_t n = 0;
        n = bc_append(line, sizeof(line), n, "ABORT ts=");
        n = bc_append_u64(line, sizeof(line), n,
                          (unsigned long long) ::time(nullptr));
        n = bc_append(line, sizeof(line), n, " ");
        // message 单行化：换行/回车压成空格，整行定长截断（上限 768B）
        for (const char* p = message; *p && n + 1 < sizeof(line) - 1; ++p) {
            line[n++] = (*p == '\n' || *p == '\r') ? ' ' : *p;
        }
        n = bc_append(line, sizeof(line), n, "\n");
        (void) ::write(fd, line, n);
        ::close(fd);
    }
    g_abort_capture_busy = false;
}

/** 注册 ggml abort 回调 + 重定向 stderr（幂等；路径已设置后调用）。 */
void install_abort_forensics() {
    bool expected = false;
    if (!g_abort_forensics_installed.compare_exchange_strong(expected, true)) return;
    ggml_set_abort_callback(&vn_ggml_abort_callback);
    // stderr 兜底：面包屑同目录的 native_stderr.txt
    char stderr_path[sizeof(g_breadcrumb_path)];
    std::snprintf(stderr_path, sizeof(stderr_path), "%s", g_breadcrumb_path);
    char* slash = std::strrchr(stderr_path, '/');
    if (slash) {
        std::snprintf(slash + 1, sizeof(stderr_path) - (size_t) (slash + 1 - stderr_path),
                      "%s", "native_stderr.txt");
        const int fd = ::open(stderr_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            // 无缓冲必须在 dup2 前设置（见上方注释）
            (void) ::setvbuf(stderr, nullptr, _IONBF, 0);
            static int saved_stderr_fd = -1;  // 保留原 stderr fd 副本
            saved_stderr_fd = ::dup(STDERR_FILENO);
            (void) saved_stderr_fd;
            (void) ::dup2(fd, STDERR_FILENO);
            if (fd != STDERR_FILENO) ::close(fd);
        }
    }
}

/** 安装面包屑信号处理（只装一次；JNI_OnLoad 调用）。 */
void install_breadcrumb_handlers() {
    static std::atomic<bool> installed{false};
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) return;
    struct sigaction sa{};
    sa.sa_sigaction = &breadcrumb_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    const int sigs[] = {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE};
    for (int sig : sigs) {
        struct sigaction old{};
        if (::sigaction(sig, &sa, &old) == 0 && sig >= 0 && sig < 32) {
            g_old_sigactions[sig] = old;
            g_old_sigaction_valid[sig] = true;
        }
    }
}

// 返回 s 中最长的合法 UTF-8 前缀长度。Qwen3 等分词器可能把一个多字节字符
// 切成多个 token，逐 token 调 NewStringUTF 会在切开处传非法 UTF-8 → JNI 返回
// NULL → Kotlin 侧 NPE 闪退。流式输出时必须只回调完整字符。
size_t utf8_valid_prefix_len(const std::string& s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len;
        if (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else return i;  // 非法起始字节
        if (i + len > n) return i;  // 尾部不完整，留给下个 token 补齐
        for (size_t k = 1; k < len; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) return i;  // 非法后续字节
        }
        i += len;
    }
    return n;
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
    set_milestone("loadModel 入口");
    const int64_t t0 = now_ms();
    llama_model_params mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    if (!model) {
        set_milestone("loadModel 失败（文件加载）");
        set_last_error("模型文件加载失败：文件不存在、损坏或非 GGUF 格式");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = n_ctx > 0 ? (uint32_t) n_ctx : cparams.n_ctx;
    cparams.n_threads      = n_threads > 0 ? n_threads : cparams.n_threads;
    cparams.n_threads_batch = cparams.n_threads;
    // 崩溃修复第二阶段（单变量验证）：显式关闭 Flash Attention。
    // 曾为排查首次 decode SIGABRT 显式关闭 Flash Attention 做单变量验证；
    // 后经断言原文 + Llama 对照模型 + 重下载对照定案：元凶是手机上一份损坏的
    // Qwen3-1.7B GGUF 文件，FA 无罪。现恢复显式 AUTO（llama.cpp 默认行为），
    // 其余参数一概不动。
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    // 注：v0.7.0 的 Q8_0 KV cache (type_k/type_v) 标为 EXPERIMENTAL，
    // 曾导致 Qwen3-4B decode 首 batch 即失败，已回退到默认 F16。
    // 若 F16 仍 OOM，再考虑减小 n_ctx。

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        set_milestone("loadModel 失败（上下文初始化）");
        set_last_error("模型上下文初始化失败：内存不足或 n_ctx 过大");
        return 0;
    }

    LlamaHandle* h = new (std::nothrow) LlamaHandle();
    if (!h) {
        llama_free(ctx);
        llama_model_free(model);
        set_milestone("loadModel 失败（句柄分配）");
        set_last_error("内存不足，无法分配模型句柄");
        return 0;
    }
    h->model = model;
    h->ctx   = ctx;
    h->n_ctx = llama_n_ctx(ctx);
    const int64_t load_ms = now_ms() - t0;
    const char* slash = std::strrchr(path, '/');
    LLM_LOGI_BRIDGE("[llm] loadModel: %s in %lldms (threads=%d, n_ctx=%u)",
             slash ? slash + 1 : path, (long long) load_ms,
             (int) cparams.n_threads, h->n_ctx);
    // 关键参数：模型描述（含架构/参数量/量化类型）+ 上下文配置，供诊断用
    {
        char desc[256] = {0};
        llama_model_desc(model, desc, sizeof(desc));
        LLM_LOGI_BRIDGE("[llm] model info: %s", desc);
        LLM_LOGI_BRIDGE("[llm] ctx params: n_ctx=%u, n_threads=%d, n_threads_batch=%d, type_k=%d, type_v=%d, flash_attn=%s",
                 h->n_ctx, (int) cparams.n_threads, (int) cparams.n_threads_batch,
                 (int) cparams.type_k, (int) cparams.type_v,
                 llama_flash_attn_type_name(cparams.flash_attn_type));
    }
    set_milestone("loadModel 返回（成功）");
    return (jlong) h;
}

// ── WP2：GGUF 内嵌模板探针与拼装 ──────────────────────────────────────────
// 返回码：0=OK（有内嵌模板且 apply 试拼通过）；1=无内嵌模板；
//        2=模板不受支持（apply 返回负值）；3=模型加载失败。
// 口径：llama.cpp v0.7.0 的 llama_chat_apply_template 不做 Jinja 解析、
// 只认预定义模板集（llama.h 注释明示），2 是运行时固有限制而非文件损坏。
jint probe_chat_template_impl(const char* path) {
    set_milestone("probeChatTemplate 入口");
    llama_model* model = llama_model_load_from_file(path, llama_model_default_params());
    if (!model) {
        set_milestone("probeChatTemplate 失败（文件加载）");
        set_last_error("模型文件加载失败：文件不存在、损坏或非 GGUF 格式");
        return 3;
    }
    const char* tmpl = llama_model_chat_template(model, nullptr);
    jint rc;
    if (!tmpl || !*tmpl) {
        rc = 1;
        set_milestone("probeChatTemplate 结论（无内嵌模板）");
    } else {
        llama_chat_message msgs[2] = {
            {"system", "You are a helpful assistant."},
            {"user", "Hello"},
        };
        std::vector<char> buf(8192);
        int32_t n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            // 返回值大于 buffer 时按文档重试一次（模板本身可用）
            buf.resize((size_t) n + 1);
            n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
        }
        rc = (n < 0) ? 2 : 0;
        set_milestone(rc == 0 ? "probeChatTemplate 结论（可用）"
                              : "probeChatTemplate 结论（模板不受支持）");
    }
    llama_model_free(model);
    return rc;
}

// 用已加载模型的内嵌模板拼 prompt；成功返回 true 并把结果写入 out，
// 无模板/apply 失败返回 false（错误原因经 set_last_error 带出）。
bool apply_embedded_template_impl(LlamaHandle* h,
                                  const std::vector<std::pair<std::string, std::string>>& messages,
                                  bool add_ass, std::string& out) {
    if (!h || !h->model) {
        set_last_error("模型未加载");
        return false;
    }
    const char* tmpl = llama_model_chat_template(h->model, nullptr);
    if (!tmpl || !*tmpl) {
        set_last_error("该模型不含内嵌对话模板，无法用于对话生成");
        return false;
    }
    std::vector<llama_chat_message> msgs;
    msgs.reserve(messages.size());
    size_t total_chars = 0;
    for (const auto& m : messages) {
        msgs.push_back({m.first.c_str(), m.second.c_str()});
        total_chars += m.first.size() + m.second.size();
    }
    std::vector<char> buf(2 * total_chars + 4096);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass,
                                          buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass,
                                      buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        set_last_error("该模型的对话模板不受当前运行时支持");
        return false;
    }
    out.assign(buf.data(), (size_t) n);
    return true;
}

// ── 预定义命名模板探针与拼装（国内名单定版新增）──────────────────────────
// llama_chat_apply_template 的 tmpl 参数先按模板名查 LLM_CHAT_TEMPLATES 表
// （llama-chat.cpp llm_chat_detect_template），故直接传名即可走预定义模板，
// 适用于内嵌模板为复杂 Jinja、v0.7.0 不解析而须靠预定义模板兜底的条目
// （hunyuan-dense / glmedge）。返回码与内嵌探针同口径：0=OK / 2=不受支持 / 3=加载失败。
jint probe_chat_template_named_impl(const char* path, const char* name) {
    set_milestone("probeChatTemplateNamed 入口");
    llama_model* model = llama_model_load_from_file(path, llama_model_default_params());
    if (!model) {
        set_milestone("probeChatTemplateNamed 失败（文件加载）");
        set_last_error("模型文件加载失败：文件不存在、损坏或非 GGUF 格式");
        return 3;
    }
    llama_chat_message msgs[2] = {
        {"system", "You are a helpful assistant."},
        {"user", "Hello"},
    };
    std::vector<char> buf(8192);
    int32_t n = llama_chat_apply_template(name, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n + 1);
        n = llama_chat_apply_template(name, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    const jint rc = (n < 0) ? 2 : 0;
    set_milestone(rc == 0 ? "probeChatTemplateNamed 结论（可用）"
                          : "probeChatTemplateNamed 结论（模板不受支持）");
    llama_model_free(model);
    return rc;
}

// 用预定义命名模板拼 prompt；成功返回 true 并把结果写入 out。
bool apply_named_template_impl(LlamaHandle* h, const char* name,
                               const std::vector<std::pair<std::string, std::string>>& messages,
                               bool add_ass, std::string& out) {
    if (!h || !h->model) {
        set_last_error("模型未加载");
        return false;
    }
    if (!name || !*name) {
        set_last_error("未指定预定义对话模板");
        return false;
    }
    std::vector<llama_chat_message> msgs;
    msgs.reserve(messages.size());
    size_t total_chars = 0;
    for (const auto& m : messages) {
        msgs.push_back({m.first.c_str(), m.second.c_str()});
        total_chars += m.first.size() + m.second.size();
    }
    std::vector<char> buf(2 * total_chars + 4096);
    int32_t n = llama_chat_apply_template(name, msgs.data(), msgs.size(), add_ass,
                                          buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n + 1);
        n = llama_chat_apply_template(name, msgs.data(), msgs.size(), add_ass,
                                      buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        set_last_error("该模型的对话模板不受当前运行时支持");
        return false;
    }
    out.assign(buf.data(), (size_t) n);
    return true;
}

// 返回码：0=正常结束（EOS / 达 maxTokens / 上下文用尽）；3=取消；其他=错误
jint generate_impl(JNIEnv* env, LlamaHandle* h, const char* prompt_utf8,
                   int32_t max_tokens, float temperature, jobject callback) {
    g_ms_prefill_tokens.store(0, std::memory_order_relaxed);
    g_ms_decode_tokens.store(0, std::memory_order_relaxed);
    set_milestone("generate 入口");
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
    set_milestonef("tokenize 完成（n_prompt=%d）", n_prompt);

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

    // 每次 generate 都从干净 KV 开始：句柄常驻、KV 里可能留着上次 generate 的
    // positions（n_past 每次都从 0 起），不清掉的话 llama.cpp 会报
    // "inconsistent sequence positions"（要求新 batch 位置连续：Y = X + 1）。
    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

    LLM_LOGI_BRIDGE("[llm] generate start: n_prompt=%d, n_batch_max=%d, max_tokens=%d, temp=%.2f",
             n_prompt, n_batch_max, max_tokens, temperature);
    set_milestone("prefill 开始");

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
        const int dec_ret = llama_decode(h->ctx, batch);
        if (dec_ret != 0) {
            char msg[256];
            std::snprintf(msg, sizeof(msg),
                "prompt 编码失败（ret=%d，prompt %d tokens，已处理 %d）",
                dec_ret, n_prompt, n_past);
            set_last_error(msg);
            rc = 15;
            break;
        }
        n_past += n_eval;
        g_ms_prefill_tokens.store(n_past, std::memory_order_relaxed);
        set_milestonef("prefill %d/%d batch", bi + 1, n_batches);
        const int64_t batch_ms = now_ms() - t_b0;
        LLM_LOGI_BRIDGE("[llm] prefill batch %d/%d: %d tok in %lldms",
                 bi + 1, n_batches, n_eval, (long long) batch_ms);
        char prog[128];
        std::snprintf(prog, sizeof(prog), "prefill %d/%d batch, %d/%d tok",
                      bi + 1, n_batches, n_past, n_prompt);
        set_last_progress(prog);
    }
    const int64_t prefill_ms = now_ms() - t_pre0;
    const int32_t n_prefill_done = n_past;  // 实际完成编码的 prompt token 数
    if (rc == 0) set_milestone("prefill 完成，decode 开始");

    // 6. 采样循环
    set_last_progress("decode 进行中…");
    const llama_token eos = llama_vocab_eos(vocab);
    std::string piece;
    std::string utf8_pending;  // 被切开的多字节字符暂存，等下个 token 补齐再回调
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
            // 流式 UTF-8：只回调完整字符，不完整的尾部暂存等下个 token 补齐。
            // 直接 NewStringUTF 传被切开的多字节字符会返回 NULL → Kotlin NPE 闪退。
            utf8_pending.append(piece);
            const size_t valid_len = utf8_valid_prefix_len(utf8_pending);
            if (valid_len > 0) {
                std::string emit = utf8_pending.substr(0, valid_len);
                utf8_pending.erase(0, valid_len);
                jstring jpiece = env->NewStringUTF(emit.c_str());
                if (jpiece) {
                    env->CallVoidMethod(cb_global, on_token, jpiece);
                    env->DeleteLocalRef(jpiece);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    set_last_error("回调 onToken 时发生异常");
                    rc = 16;
                    break;
                }
            }
        }
        if (n_past + 1 >= (int32_t) h->n_ctx) break;  // 上下文用尽，正常结束
        fill_batch(&tok, 1, n_past);
        const int dec_ret = llama_decode(h->ctx, batch);
        if (dec_ret != 0) {
            char msg[128];
            std::snprintf(msg, sizeof(msg), "解码失败（ret=%d）", dec_ret);
            set_last_error(msg);
            rc = 15;
            break;
        }
        n_past += 1;
        n_gen += 1;
        g_ms_decode_tokens.store(n_gen, std::memory_order_relaxed);
        if (n_gen % 256 == 0) {
            set_milestonef("decode 进行中（%d tok）", n_gen);
        }
    }
    const int64_t decode_ms = now_ms() - t_dec0;

    // 循环结束：把残留的未补齐尾部也发出（正常应为空；若有则只发合法前缀）
    if (rc == 0 && !utf8_pending.empty()) {
        const size_t valid_len = utf8_valid_prefix_len(utf8_pending);
        if (valid_len > 0) {
            std::string emit = utf8_pending.substr(0, valid_len);
            jstring jpiece = env->NewStringUTF(emit.c_str());
            if (jpiece) {
                env->CallVoidMethod(cb_global, on_token, jpiece);
                env->DeleteLocalRef(jpiece);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

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
    LLM_LOGI_BRIDGE("%s", perf);
    set_milestone("generate 出口");

    env->DeleteGlobalRef(cb_global);
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    return rc;
}

// 方案 A 诊断：只对 1 个 token 做一次 llama_decode。
// 成功返回 0；若连 1 个 token 都 hang，则此调用不返回（Kotlin 侧用超时判定），
// 结论为根本性 hang（非 batch 大小问题）。
jint probe_impl(LlamaHandle* h) {
    set_milestone("singleTokenProbe 入口");
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    if (!vocab) {
        set_last_error("取模型词表失败");
        return 10;
    }
    // 固定最小输入 "hi"，只取首个 token
    static const char* kProbeText = "hi";
    int32_t n = llama_tokenize(vocab, kProbeText, (int32_t) std::strlen(kProbeText),
                               nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n >= 0) {
        set_last_error("诊断输入分词失败");
        return 11;
    }
    n = -n;
    if (n < 1) {
        set_last_error("诊断输入分词为空");
        return 11;
    }
    std::vector<llama_token> toks((size_t) n);
    if (llama_tokenize(vocab, kProbeText, (int32_t) std::strlen(kProbeText),
                       toks.data(), n, true, true) < 0) {
        set_last_error("诊断输入分词失败");
        return 11;
    }

    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.n_tokens   = 1;
    batch.token[0]   = toks[0];
    batch.pos[0]     = 0;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0]  = true;

    LLM_LOGI_BRIDGE("[llm][probe] 方案A 单 token 诊断开始：输入 \"%s\" → %d tokens，"
             "取首个 token(id=%d) 做 1 次 decode（batch=1, pos=0）",
             kProbeText, n, (int) toks[0]);
    // 2026-10-24：探针进出保存/恢复进度。失败路径（位置校验失败时探针什么都没写入）
    // 不再清 KV、不再留「probe: 进行中」陈旧文本污染生成进度轮询；成功路径仍清 KV
    // （闲时无副作用），并置「probe: 完成」。注意：成功路径的清 KV 在并发时同样致命，
    // 并发防护靠 Kotlin 侧忙时门禁（生成进行中拒绝探针），本函数不单独保证并发安全。
    const std::string saved_progress = get_last_progress();
    set_last_progress("probe: 1 token decode 进行中…");
    const int64_t t0 = now_ms();
    const int ret = llama_decode(h->ctx, batch);
    const int64_t ms = now_ms() - t0;
    llama_batch_free(batch);
    if (ret != 0) {
        // 校验失败：batch 在初始化层即被拒绝，KV 未被探针写入——不清 KV（清操作只会
        // 破坏调用前已存在的 KV，2026-10-24 实测即因此险些毁掉在途生成），并把进度
        // 恢复为探针进入前的值，避免轮询器读到陈旧的 probe 文本。
        set_last_progress(saved_progress);
        char msg[128];
        std::snprintf(msg, sizeof(msg), "单 token decode 失败（ret=%d）", ret);
        set_last_error(msg);
        LLM_LOGI_BRIDGE("[llm][probe] 单 token decode 失败：ret=%d，耗时 %lldms", ret, (long long) ms);
        return 15;
    }
    // 成功路径：decode 已在 KV cache 留下 pos=0 的 token，
    // 不清掉的话，下次 probe/generate 从 pos=0 起 decode 会触发
    // llama.cpp 的 "inconsistent sequence positions" 校验失败（要求 Y = X + 1）。
    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);
    LLM_LOGI_BRIDGE("[llm][probe] KV cache 已清理，探针无副作用");
    LLM_LOGI_BRIDGE("[llm][probe] 单 token decode 成功：ret=0，耗时 %lldms", (long long) ms);
    set_last_progress("probe: 完成");
    return 0;
}

/** 读 CPU 频率（kHz），部分手机无权限则返回 -1。只读前 8 核。 */
static void log_cpu_freqs(const char* tag) {
    char line[256] = {0};
    size_t off = 0;
    for (int c = 0; c < 8; ++c) {
        char path[128];
        std::snprintf(path, sizeof(path),
                      "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", c);
        FILE* f = std::fopen(path, "r");
        int khz = -1;
        if (f) {
            if (std::fscanf(f, "%d", &khz) != 1) khz = -1;
            std::fclose(f);
        }
        off += std::snprintf(line + off, sizeof(line) - off,
                             "%s%d", c ? "," : "", khz / 1000);
        if (off >= sizeof(line) - 16) break;
    }
    LLM_LOGI_BRIDGE("[llm][cprobe] cpu_freq(%s): %s MHz", tag, line);
}

/** 读 SoC 温度（best-effort）：遍历 thermal_zone 取可读的最大值。 */
static void log_thermal(const char* tag) {
    long max_millic = -1;
    for (int z = 0; z < 16; ++z) {
        char path[128];
        std::snprintf(path, sizeof(path), "/sys/class/thermal/thermal_zone%d/temp", z);
        FILE* f = std::fopen(path, "r");
        if (!f) continue;
        long t = -1;
        if (std::fscanf(f, "%ld", &t) == 1 && t > max_millic) max_millic = t;
        std::fclose(f);
    }
    if (max_millic >= 0) {
        LLM_LOGI_BRIDGE("[llm][cprobe] thermal(%s): max_zone=%.1fC", tag, max_millic / 1000.0);
    } else {
        LLM_LOGI_BRIDGE("[llm][cprobe] thermal(%s): n/a", tag);
    }
}

/** 打 CPU 特性（直接读 HWCAP，和 ggml 运行时检测同一来源）。 */
static void log_cpu_features() {
    // <asm/hwcap.h>：AT_HWCAP / AT_HWCAP2
    const unsigned long hwcap  = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    const int neon     = (hwcap  & HWCAP_ASIMD)   ? 1 : 0;
    const int dotprod  = (hwcap  & HWCAP_ASIMDDP) ? 1 : 0;
    const int sve      = (hwcap  & HWCAP_SVE)     ? 1 : 0;
    const int sve2     = (hwcap2 & HWCAP2_SVE2)   ? 1 : 0;
    const int i8mm     = (hwcap2 & HWCAP2_I8MM)   ? 1 : 0;
    const int fp16     = (hwcap  & HWCAP_FPHP)    ? 1 : 0;  // ASIMD half-precision
    LLM_LOGI_BRIDGE("[llm][cprobe] cpu_features(hwcap): neon=%d dotprod=%d fp16=%d sve=%d sve2=%d i8mm=%d",
             neon, dotprod, fp16, sve, sve2, i8mm);
}

/** 把当前线程（及之后创建的线程，继承 affinity）绑到大核 cpu4-7。 */
static void pin_to_big_cores() {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c = 4; c < 8; ++c) CPU_SET(c, &set);
    // Bionic 没有 pthread_setaffinity_np，用 sched_setaffinity（pid=0 表示当前线程）
    const int rc = sched_setaffinity(0, sizeof(set), &set);
    // 验证实际生效的 mask
    cpu_set_t check;
    CPU_ZERO(&check);
    sched_getaffinity(0, sizeof(check), &check);
    char mask[64] = {0};
    size_t off = 0;
    for (int c = 0; c < 8 && off < sizeof(mask) - 4; ++c) {
        off += std::snprintf(mask + off, sizeof(mask) - off, "%d", CPU_ISSET(c, &check) ? 1 : 0);
    }
    LLM_LOGI_BRIDGE("[llm][cprobe] pin_big_cores: setaffinity rc=%d, mask=%s", rc, mask);
    // 当前线程所在 CPU
    LLM_LOGI_BRIDGE("[llm][cprobe] pin_big_cores: current cpu=%d", sched_getcpu());
}

/** 内存带宽微基准：256MB memset 计时。区分"系统慢" vs "ggml 慢"。 */
static void log_membw() {
    const size_t n = 256ull * 1024 * 1024;
    void* buf = std::malloc(n);
    if (!buf) {
        LLM_LOGI_BRIDGE("[llm][cprobe] membw: malloc 失败，跳过");
        return;
    }
    const int64_t t0 = now_ms();
    std::memset(buf, 0xAB, n);
    const int64_t ms = now_ms() - t0;
    // 防止编译器优化掉 memset：读一个字节
    volatile unsigned char sink = ((unsigned char*) buf)[n - 1];
    (void) sink;
    std::free(buf);
    const double mbs = ms > 0 ? 256.0 * 1000.0 / ms : 0.0;
    LLM_LOGI_BRIDGE("[llm][cprobe] membw: 256MB memset 用时 %lldms（%.0f MB/s）",
             (long long) ms, mbs);
}

// 方案 C：最小化 native 测试。完全绕开封装层，直连 llama.cpp 最简调用：
// - 模型用 llama_model_default_params() 加载；
// - 上下文除 n_ctx / n_threads 外全部默认；
// - 不调 memory_clear、不建 sampler、不走回调；
// - batch 只填最简字段（token/pos/seq_id/logits）。
// 若最小化测试也 hang → llama.cpp 构建问题（ggml 后端/线程/编译选项）；
// 若最小化测试正常 → JNI 封装层用法问题。
// 每次调用独立 load/unload，不碰常驻句柄。
jint minimal_probe_impl(const char* path, int32_t n_threads, int32_t n_ctx, int32_t n_tokens_want,
                        int32_t pin_big_cores) {
    set_milestone("minimalProbe 入口");
    // 大核绑定测试：先绑当前线程，之后创建的 ggml 线程会继承 affinity mask
    if (pin_big_cores) {
        pin_to_big_cores();
    }
    log_cpu_features();
    const int64_t t_load0 = now_ms();
    llama_model* model = llama_model_load_from_file(path, llama_model_default_params());
    if (!model) {
        set_last_error("最小化测试：模型文件加载失败");
        return 21;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t) n_ctx : cparams.n_ctx;
    // 与 loadModel 一致：显式 AUTO 恢复 Flash Attention 默认行为（见 load_impl 注释）
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    if (n_threads > 0) {
        cparams.n_threads       = (uint32_t) n_threads;
        cparams.n_threads_batch = (uint32_t) n_threads;
    }
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        set_last_error("最小化测试：上下文初始化失败");
        return 22;
    }
    const int64_t load_ms = now_ms() - t_load0;
    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        llama_free(ctx);
        llama_model_free(model);
        set_last_error("最小化测试：取词表失败");
        return 23;
    }

    // 固定短文本拼到足够长度，截断到 n_tokens_want。
    // 2026-10-24：方案 T 要 512 token 一批；旧固定 32 遍约 340 token，不够一批，
    // 改为按需求遍数拼（每遍约 10 token、46 字符，8 字符/token 留足余量）；
    // 旧用例（8/64 token）仍走 32 遍、前缀 token 不变，行为不受影响。
    std::string text;
    {
        const int32_t want = n_tokens_want > 0 ? n_tokens_want : 0;
        const int reps = std::max(32, (want + 7) / 8);
        for (int i = 0; i < reps; ++i) text += "Hello world, this is a minimal native probe. ";
    }
    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                               nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n >= 0) {
        llama_free(ctx);
        llama_model_free(model);
        set_last_error("最小化测试：分词失败");
        return 23;
    }
    n = -n;
    const int32_t n_eval = std::min(n, n_tokens_want > 0 ? n_tokens_want : n);
    std::vector<llama_token> toks((size_t) n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(),
                       n, true, true) < 0) {
        llama_free(ctx);
        llama_model_free(model);
        set_last_error("最小化测试：分词失败");
        return 23;
    }

    llama_batch batch = llama_batch_init(n_eval, 0, 1);
    batch.n_tokens = n_eval;
    for (int32_t i = 0; i < n_eval; ++i) {
        batch.token[i]     = toks[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == n_eval - 1);
    }

    LLM_LOGI_BRIDGE("[llm][cprobe] 最小化测试开始：threads=%d, n_ctx=%u, tokens=%d, load=%lldms",
             (int) cparams.n_threads, cparams.n_ctx, n_eval, (long long) load_ms);
    // 系统级基线：CPU 频率 + 温度 + 内存带宽（区分"系统慢" vs "ggml 慢"）
    log_cpu_freqs("before");
    log_thermal("before");
    log_membw();
    // decode 中途采样：后台线程每 500ms 记一次频率/温度，看 governor 是否在 decode 中途降频
    // （before/after 两个瞬时采样会漏掉中途的降频，这是之前分析的最大盲区）
    std::atomic<bool> sampler_stop{false};
    std::thread sampler([&]() {
        int idx = 0;
        while (!sampler_stop.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            if (sampler_stop.load(std::memory_order_relaxed)) break;
            char tag[32];
            std::snprintf(tag, sizeof(tag), "mid-%d", ++idx);
            log_cpu_freqs(tag);
            if (idx % 4 == 0) log_thermal(tag);  // 每 2s 记一次温度
        }
    });
    const int64_t t0 = now_ms();
    const int ret = llama_decode(ctx, batch);
    const int64_t ms = now_ms() - t0;
    sampler_stop.store(true, std::memory_order_relaxed);
    sampler.join();
    log_cpu_freqs("after");
    log_thermal("after");
    llama_batch_free(batch);
    llama_free(ctx);
    llama_model_free(model);
    if (ret != 0) {
        char msg[128];
        std::snprintf(msg, sizeof(msg), "最小化测试 decode 失败（ret=%d）", ret);
        set_last_error(msg);
        LLM_LOGI_BRIDGE("[llm][cprobe] decode 失败：ret=%d，耗时 %lldms", ret, (long long) ms);
        set_milestonef("cprobe fail threads=%d tokens=%d rc=%d", (int) n_threads, n_eval, ret);
        return 24;
    }
    const double tps = ms > 0 ? n_eval * 1000.0 / ms : 0.0;
    LLM_LOGI_BRIDGE("[llm][cprobe] decode 成功：threads=%d, n_ctx=%u, flash_attn=%s, %d tokens in %lldms (%.1f tok/s)",
             (int) cparams.n_threads, cparams.n_ctx,
             llama_flash_attn_type_name(cparams.flash_attn_type), n_eval, (long long) ms, tps);
    set_milestonef("cprobe ok threads=%d tokens=%d %lldms", (int) n_threads, n_eval, (long long) ms);
    return 0;
}

}  // namespace

// JNI 日志桥接实现：写入环形 buffer，Kotlin 侧经 drainJniLogs() 取走写入 llm.log
static void bridge_log(const char* line) {
    if (!line || !*line) return;
    std::lock_guard<std::mutex> lock(g_jni_log_mu);
    if (g_jni_log_buffer.size() >= kJniLogMaxLines) {
        g_jni_log_buffer.erase(g_jni_log_buffer.begin());
    }
    g_jni_log_buffer.emplace_back(line);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    // 崩溃面包屑信号处理：只装一次，链回旧 handler，不改变原崩溃行为
    install_breadcrumb_handlers();
    llama_log_set(llama_log_forward, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_setBreadcrumbPath(
        JNIEnv* env, jobject /*thiz*/, jstring path) {
    try {
        if (!path) return;
        const char* cpath = env->GetStringUTFChars(path, nullptr);
        if (!cpath) return;
        std::snprintf(g_breadcrumb_path, sizeof(g_breadcrumb_path), "%s", cpath);
        env->ReleaseStringUTFChars(path, cpath);
        g_breadcrumb_path_set.store(true, std::memory_order_release);
        // 第三阶段取证：路径就位后注册 ggml abort 回调 + stderr 兜底
        // （幂等；此时引擎尚未创建任何上下文，早于首次 decode）
        install_abort_forensics();
    } catch (...) {
    }
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
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jlong h = load_impl(cpath, nCtx, nThreads);
        g_native_tid.store(0, std::memory_order_release);
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
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jint rc = generate_impl(env, h, cprompt, maxTokens, temperature, callback);
        g_native_tid.store(0, std::memory_order_release);
        env->ReleaseStringUTFChars(prompt, cprompt);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（generate）");
        return 99;
    }
}

JNIEXPORT jint JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_singleTokenProbe(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    try {
        LlamaHandle* h = reinterpret_cast<LlamaHandle*>(handle);
        if (!h || !h->ctx || !h->model) {
            set_last_error("模型未加载");
            return 1;
        }
        h->cancel.store(false, std::memory_order_relaxed);
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jint rc = probe_impl(h);
        g_native_tid.store(0, std::memory_order_release);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（singleTokenProbe）");
        return 99;
    }
}

JNIEXPORT jint JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_minimalProbe(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath,
        jint nThreads, jint nCtx, jint nTokens, jint pinBigCores) {
    try {
        if (!modelPath) {
            set_last_error("模型路径为空");
            return 2;
        }
        const char* cpath = env->GetStringUTFChars(modelPath, nullptr);
        if (!cpath) {
            set_last_error("读取模型路径失败");
            return 2;
        }
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jint rc = minimal_probe_impl(cpath, nThreads, nCtx, nTokens, pinBigCores);
        g_native_tid.store(0, std::memory_order_release);
        env->ReleaseStringUTFChars(modelPath, cpath);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（minimalProbe）");
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

// 取走 JNI 桥接日志（多行 \n 连接），返回后清空 buffer。
// Kotlin 侧在 loadModel/generate 结束后调用，写入 llm.log（App 内可见）。
JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_drainJniLogs(
        JNIEnv* env, jobject /*thiz*/) {
    try {
        std::lock_guard<std::mutex> lock(g_jni_log_mu);
        if (g_jni_log_buffer.empty()) {
            return env->NewStringUTF("");
        }
        std::string out;
        for (size_t i = 0; i < g_jni_log_buffer.size(); ++i) {
            if (i > 0) out += '\n';
            out += g_jni_log_buffer[i];
        }
        g_jni_log_buffer.clear();
        return env->NewStringUTF(out.c_str());
    } catch (...) {
        return nullptr;
    }
}

// WP2：GGUF 内嵌模板探针（仅元数据级加载，不建上下文）。
// 返回 0=可用 / 1=无内嵌模板 / 2=模板不受支持 / 3=加载失败。
JNIEXPORT jint JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_probeChatTemplate(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {
    try {
        if (!modelPath) {
            set_last_error("模型路径为空");
            return 3;
        }
        const char* cpath = env->GetStringUTFChars(modelPath, nullptr);
        if (!cpath) {
            set_last_error("读取模型路径失败");
            return 3;
        }
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jint rc = probe_chat_template_impl(cpath);
        g_native_tid.store(0, std::memory_order_release);
        env->ReleaseStringUTFChars(modelPath, cpath);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（probeChatTemplate）");
        return 3;
    }
}

// WP2：用已加载模型的内嵌模板拼装 prompt；失败返回 null（原因见 lastError）。
JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_applyEmbeddedTemplate(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray roles,
        jobjectArray contents, jboolean addAssistant) {
    try {
        LlamaHandle* h = reinterpret_cast<LlamaHandle*>(handle);
        if (!h || !roles || !contents) {
            set_last_error("参数为空");
            return nullptr;
        }
        const jsize n = env->GetArrayLength(roles);
        if (n != env->GetArrayLength(contents)) {
            set_last_error("消息角色与内容数量不一致");
            return nullptr;
        }
        std::vector<std::pair<std::string, std::string>> messages;
        messages.reserve((size_t) n);
        for (jsize i = 0; i < n; ++i) {
            auto role = (jstring) env->GetObjectArrayElement(roles, i);
            auto content = (jstring) env->GetObjectArrayElement(contents, i);
            std::string r, c;
            if (role) {
                const char* s = env->GetStringUTFChars(role, nullptr);
                if (s) { r = s; env->ReleaseStringUTFChars(role, s); }
            }
            if (content) {
                const char* s = env->GetStringUTFChars(content, nullptr);
                if (s) { c = s; env->ReleaseStringUTFChars(content, s); }
            }
            messages.emplace_back(r, c);
        }
        std::string out;
        if (!apply_embedded_template_impl(h, messages, addAssistant == JNI_TRUE, out)) {
            return nullptr;
        }
        return env->NewStringUTF(out.c_str());
    } catch (...) {
        set_last_error("native 内部异常（applyEmbeddedTemplate）");
        return nullptr;
    }
}

// 预定义命名模板探针（仅元数据级加载，不建上下文）。
// 返回 0=可用 / 2=模板不受支持 / 3=加载失败。
JNIEXPORT jint JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_probeChatTemplateNamed(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jstring templateName) {
    try {
        if (!modelPath || !templateName) {
            set_last_error("参数为空");
            return 3;
        }
        const char* cpath = env->GetStringUTFChars(modelPath, nullptr);
        const char* cname = env->GetStringUTFChars(templateName, nullptr);
        if (!cpath || !cname) {
            if (cpath) env->ReleaseStringUTFChars(modelPath, cpath);
            if (cname) env->ReleaseStringUTFChars(templateName, cname);
            set_last_error("读取参数失败");
            return 3;
        }
        g_native_tid.store((int) ::syscall(SYS_gettid), std::memory_order_release);
        const jint rc = probe_chat_template_named_impl(cpath, cname);
        g_native_tid.store(0, std::memory_order_release);
        env->ReleaseStringUTFChars(modelPath, cpath);
        env->ReleaseStringUTFChars(templateName, cname);
        return rc;
    } catch (...) {
        set_last_error("native 内部异常（probeChatTemplateNamed）");
        return 3;
    }
}

// 用预定义命名模板拼装 prompt；失败返回 null（原因见 lastError）。
JNIEXPORT jstring JNICALL
Java_com_zola_videonotes_data_llm_local_LlamaCpp_applyNamedTemplate(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring templateName,
        jobjectArray roles, jobjectArray contents, jboolean addAssistant) {
    try {
        LlamaHandle* h = reinterpret_cast<LlamaHandle*>(handle);
        if (!h || !templateName || !roles || !contents) {
            set_last_error("参数为空");
            return nullptr;
        }
        const jsize n = env->GetArrayLength(roles);
        if (n != env->GetArrayLength(contents)) {
            set_last_error("消息角色与内容数量不一致");
            return nullptr;
        }
        const char* cname = env->GetStringUTFChars(templateName, nullptr);
        if (!cname) {
            set_last_error("读取模板名失败");
            return nullptr;
        }
        std::string name(cname);
        env->ReleaseStringUTFChars(templateName, cname);
        std::vector<std::pair<std::string, std::string>> messages;
        messages.reserve((size_t) n);
        for (jsize i = 0; i < n; ++i) {
            auto role = (jstring) env->GetObjectArrayElement(roles, i);
            auto content = (jstring) env->GetObjectArrayElement(contents, i);
            std::string r, c;
            if (role) {
                const char* s = env->GetStringUTFChars(role, nullptr);
                if (s) { r = s; env->ReleaseStringUTFChars(role, s); }
            }
            if (content) {
                const char* s = env->GetStringUTFChars(content, nullptr);
                if (s) { c = s; env->ReleaseStringUTFChars(content, s); }
            }
            messages.emplace_back(r, c);
        }
        std::string out;
        if (!apply_named_template_impl(h, name.c_str(), messages, addAssistant == JNI_TRUE, out)) {
            return nullptr;
        }
        return env->NewStringUTF(out.c_str());
    } catch (...) {
        set_last_error("native 内部异常（applyNamedTemplate）");
        return nullptr;
    }
}

}  // extern "C"
