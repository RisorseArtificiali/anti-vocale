/*
 * TASK-853: the x86_64 stubs. Upstream ships no android-x86_64 engine
 * (their linux-x86_64 build links glibc, not bionic), so that ABI gets a
 * whistle_jni library whose every entry point reports failure through
 * lastError. The library existing at all keeps Kotlin's loadLibrary
 * uniform; WhistleNative.available then gates on isSpeechLoaded, which
 * the stubs always answer false.
 */
#include <jni.h>
#include <mutex>
#include <string>

namespace {
std::mutex g_stub_mutex;
std::string g_last_error = "no engine for this ABI (upstream ships arm only)";
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_loadNative(
        JNIEnv *, jclass, jbyteArray) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_antivocale_app_transcription_WhistleNative_isSpeechLoadedNative(
        JNIEnv *, jclass) {
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_transcribeNative(
        JNIEnv *env, jclass, jfloatArray, jstring, jstring, jboolean) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_antivocale_app_transcription_WhistleNative_lastErrorNative(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_stub_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
