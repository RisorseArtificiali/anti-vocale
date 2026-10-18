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
#include <jni.h>
#include <mutex>
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
constexpr int kOutCapacity = 1 << 20;
std::vector<char> &out_buffer() {
    static std::vector<char> buffer(kOutCapacity);
    return buffer;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
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
JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_transcribeNative(
        JNIEnv *env, jclass, jfloatArray pcm, jstring language,
        jstring keywords, jboolean word_timestamps) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jsize samples = env->GetArrayLength(pcm);
    jfloat *pcm_ptr = env->GetFloatArrayElements(pcm, nullptr);
    std::string lang = jstring_to_std(env, language);
    std::string kw = jstring_to_std(env, keywords);
    int rc = needle_transcribe(
        pcm_ptr, static_cast<int>(samples),
        lang.empty() ? nullptr : lang.c_str(),
        kw.empty() ? nullptr : kw.c_str(),
        word_timestamps ? 1 : 0,
        out_buffer().data(), kOutCapacity);
    env->ReleaseFloatArrayElements(pcm, pcm_ptr, JNI_ABORT);
    if (rc < 0) return nullptr;
    return env->NewStringUTF(out_buffer().data());
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    streamProcess
 * Signature: ([FLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;
 *
 * Live mode: append ~1 s of PCM, get the newly committed words plus the
 * unconfirmed tail. Feeds longer than 30 s are taken in one-second
 * pieces by the engine itself.
 */
JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamProcess(
        JNIEnv *env, jclass, jfloatArray pcm, jstring language,
        jstring keywords) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jsize samples = env->GetArrayLength(pcm);
    jfloat *pcm_ptr = env->GetFloatArrayElements(pcm, nullptr);
    std::string lang = jstring_to_std(env, language);
    std::string kw = jstring_to_std(env, keywords);
    int rc = needle_stream_transcribe_process(
        pcm_ptr, static_cast<int>(samples),
        lang.empty() ? nullptr : lang.c_str(),
        kw.empty() ? nullptr : kw.c_str(),
        out_buffer().data(), kOutCapacity);
    env->ReleaseFloatArrayElements(pcm, pcm_ptr, JNI_ABORT);
    if (rc < 0) return nullptr;
    return env->NewStringUTF(out_buffer().data());
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    streamStop
 * Signature: ()Ljava/lang/String;
 *
 * Ends the stream: the tail becomes committed words; the next
 * streamProcess starts a new stream.
 */
JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamStop(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    int rc = needle_stream_transcribe_stop(out_buffer().data(), kOutCapacity);
    if (rc < 0) return nullptr;
    return env->NewStringUTF(out_buffer().data());
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
Java_com_antivocale_app_transcription_WhistleNative_reset(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    needle_reset();
}

/*
 * Class:     com_antivocale_app_transcription_WhistleNative
 * Method:    lastErrorNative
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_lastErrorNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const char *err = needle_last_error();
    return env->NewStringUTF(err != nullptr ? err : "");
}

}  // extern "C"
