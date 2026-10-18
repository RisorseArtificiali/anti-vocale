/*
 * TASK-853: the JNI bridge over the Cactus Needle speech API
 * (needle.h, Apache-2.0). The engine is process-global and NOT
 * thread-safe (its own header says so), so every entry point here
 * holds a single static mutex; Kotlin-side callers get one serialized
 * engine per process, the same discipline LlmManager applies to its
 * conversation.
 *
 * The bridge is deliberately thin: load(bytes), transcribe(pcm, lang,
 * keywords, wantWords), streamProcess/streamStop for the live mode, and
 * lastError. JSON parsing of the engine's out-buffer happens on the
 * Kotlin side (org.json there, no JSON parser in C++ here).
 */
#include <fcntl.h>
#include <jni.h>
#include <mutex>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include <vector>

#include "include/needle.h"

namespace {

std::mutex g_engine_mutex;

/* The engine's out-buffers are caller-provided; one shared buffer per
 * process is enough under the mutex. 1 MiB holds the word-timestamp JSON
 * of a 30-minute transcript comfortably (the engine caps at ~320 tokens
 * per window; the words array is the only unbounded-looking field and
 * 30 min ≈ 5,400 words ≈ 400 KB of JSON). */
/* +1 so a full-capacity JSON can never push the terminator out: the
 * engine's overflow contract is undocumented (needle.h is silent), so the
 * bridge defensively NUL-guards and treats a missing terminator as
 * failure (review F2). 1 MiB holds text-only JSON for the 2h ceiling
 * (~100 KB) and word timestamps for ~30 min; longer words-JSON fails
 * honestly rather than truncating. */
constexpr int kOutCapacity = (1 << 20) + 1;
std::vector<char> &out_buffer() {
    static std::vector<char> buffer(kOutCapacity);
    return buffer;
}
bool out_terminated() {
    return out_buffer()[kOutCapacity - 1] == '\0';
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};  // pending exception propagates (F3)
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/* The engine emits standard UTF-8; NewStringUTF would mis-read 4-byte
 * sequences (emoji) as modified UTF-8 (review F4). Handing Kotlin the
 * raw bytes and letting String(bytes, UTF_8) decode is the correct
 * conversion; null only on allocation failure. */
jbyteArray out_as_bytes(JNIEnv *env) {
    if (!out_terminated()) return nullptr;
    jsize len = static_cast<jsize>(strnlen(out_buffer().data(), kOutCapacity));
    jbyteArray bytes = env->NewByteArray(len);
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, len,
        reinterpret_cast<const jbyte *>(out_buffer().data()));
    return bytes;
}

}  // namespace

extern "C" {

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    loadNative
 * Signature: ([B)Z
 *
 * Reads the .cact bytes into the process-global engine. Returns false on
 * failure; the reason is available via lastError() immediately after.
 */
JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_loadNative(
        JNIEnv *env, jclass, jbyteArray cact_bytes) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jsize n = env->GetArrayLength(cact_bytes);
    jbyte *bytes = env->GetByteArrayElements(cact_bytes, nullptr);
    if (bytes == nullptr) return JNI_FALSE;  // OOM; exception propagates (F3)
    int rc = needle_load(
        reinterpret_cast<const unsigned char *>(bytes),
        static_cast<unsigned long long>(n));
    env->ReleaseByteArrayElements(cact_bytes, bytes, JNI_ABORT);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    isSpeechLoadedNative
 * Signature: ()Z
 */
/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    loadFromFileNative
 * Signature: (Ljava/lang/String;)Z
 *
 * The memory-lean load path (efficiency review): mmap the .cact and hand
 * the mapping to needle_load, which copies into engine-owned memory
 * (verified in the shipped artifact's disassembly), then unmap. Peak
 * anonymous memory stays at the engine copy (~17MB) instead of doubling
 * through a Java byte[] on the LOS - the population this 16.9MB model
 * targets. The built-in flow always has a filesystem path; the byte[]
 * entry stays for stream sources.
 */
JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_loadFromFileNative(
        JNIEnv *env, jclass, jstring path) {
    std::string file = jstring_to_std(env, path);
    if (file.empty()) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    int fd = open(file.c_str(), O_RDONLY);
    if (fd < 0) return JNI_FALSE;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return JNI_FALSE;
    }
    void *mapping = mmap(nullptr, static_cast<size_t>(st.st_size),
                         PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapping == MAP_FAILED) return JNI_FALSE;
    int rc = needle_load(
        reinterpret_cast<const unsigned char *>(mapping),
        static_cast<unsigned long long>(st.st_size));
    munmap(mapping, static_cast<size_t>(st.st_size));
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_isSpeechLoadedNative(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return (needle_models() & NEEDLE_SPEECH) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    transcribeNative
 * Signature: ([FLjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;
 *
 * Whole-clip transcription; the engine windows past 30 s itself (each
 * window cut at its last sentence end). Returns the engine's JSON
 * ({"text","language","ttft_ms","decode_tps"[,"words"]}) or null on
 * failure (see lastError).
 */
JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_transcribeNative(
        JNIEnv *env, jclass, jfloatArray pcm, jstring language,
        jstring keywords, jboolean word_timestamps) {
    // Strings decoded BEFORE the array pin so a conversion failure can
    // never strand the pin (review F3).
    std::string lang = jstring_to_std(env, language);
    std::string kw = jstring_to_std(env, keywords);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jsize samples = env->GetArrayLength(pcm);
    jfloat *pcm_ptr = env->GetFloatArrayElements(pcm, nullptr);
    if (pcm_ptr == nullptr) return nullptr;  // OOM; exception propagates
    int rc = needle_transcribe(
        pcm_ptr, static_cast<int>(samples),
        lang.empty() ? nullptr : lang.c_str(),
        kw.empty() ? nullptr : kw.c_str(),
        word_timestamps ? 1 : 0,
        out_buffer().data(), kOutCapacity);
    env->ReleaseFloatArrayElements(pcm, pcm_ptr, JNI_ABORT);
    if (rc < 0 || !out_terminated()) return nullptr;
    return out_as_bytes(env);
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamProcessNative(
        JNIEnv *env, jclass, jfloatArray pcm, jstring language,
        jstring keywords) {
    std::string lang = jstring_to_std(env, language);
    std::string kw = jstring_to_std(env, keywords);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jsize samples = env->GetArrayLength(pcm);
    jfloat *pcm_ptr = env->GetFloatArrayElements(pcm, nullptr);
    if (pcm_ptr == nullptr) return nullptr;
    int rc = needle_stream_transcribe_process(
        pcm_ptr, static_cast<int>(samples),
        lang.empty() ? nullptr : lang.c_str(),
        kw.empty() ? nullptr : kw.c_str(),
        out_buffer().data(), kOutCapacity);
    env->ReleaseFloatArrayElements(pcm, pcm_ptr, JNI_ABORT);
    if (rc < 0 || !out_terminated()) return nullptr;
    return out_as_bytes(env);
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamStopNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    int rc = needle_stream_transcribe_stop(out_buffer().data(), kOutCapacity);
    if (rc < 0 || !out_terminated()) return nullptr;
    return out_as_bytes(env);
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    reset
 * Signature: ()V
 *
 * Clears the loaded model (needle_reset also clears the text model when
 * one is loaded; this process only ever loads speech).
 */
JNIEXPORT void JNICALL
Java_com_antivocale_app_transcription_WhistleNative_resetNative(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    needle_reset();
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    lastErrorNative
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_lastErrorNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const char *err = needle_last_error();
    const char *text = err != nullptr ? err : "";
    jsize len = static_cast<jsize>(strlen(text));
    jbyteArray bytes = env->NewByteArray(len);
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, len,
        reinterpret_cast<const jbyte *>(text));
    return bytes;
}

}  // extern "C"
