/*
 * TASK-853: the x86_64 stubs. Upstream ships no android-x86_64 engine
 * (their linux-x86_64 build links glibc, not bionic), so that ABI builds
 * this stub form: the whistle_jni library exists (Kotlin's loadLibrary
 * succeeds), every entry point reports failure, and the callers' real
 * gate is load()/isSpeechLoaded(), which the stubs always answer false.
 *
 * CONTRACT: this file and whistle_jni.cpp must export the IDENTICAL
 * Java_com_antivocale_app_transcription_WhistleNative_* symbol set; a
 * symbol present in one and not the other compiles clean and fails only
 * at first call on x86_64. Pinned by WhistleJniSymbolSetTest.
 */
#include <jni.h>
#include <mutex>
#include <cstring>

namespace {
std::mutex g_stub_mutex;
const char *g_last_error = "no engine for this ABI (upstream ships arm only)";

jbyteArray error_bytes(JNIEnv *env) {
    jsize len = static_cast<jsize>(strlen(g_last_error));
    jbyteArray bytes = env->NewByteArray(len);
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, len,
        reinterpret_cast<const jbyte *>(g_last_error));
    return bytes;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_loadNative(
        JNIEnv *, jclass, jbyteArray) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_loadFromFileNative(
        JNIEnv *, jclass, jstring) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_isSpeechLoadedNative(
        JNIEnv *, jclass) {
    return JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_transcribeNative(
        JNIEnv *, jclass, jfloatArray, jstring, jstring, jboolean) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return nullptr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamProcessNative(
        JNIEnv *, jclass, jfloatArray, jstring, jstring) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return nullptr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_streamStopNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_antivocale_app_transcription_WhistleNative_resetNative(
        JNIEnv *, jclass) {
}

JNIEXPORT jbyteArray JNICALL
Java_com_antivocale_app_transcription_WhistleNative_lastErrorNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return error_bytes(env);
}

}  // extern "C"
