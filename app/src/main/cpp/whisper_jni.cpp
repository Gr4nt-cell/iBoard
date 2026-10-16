// JNI bridge between iBoard (com.gr4nt.iboard.voice.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "iBoardWhisper"

extern "C" JNIEXPORT jlong JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray jsamples,
                                                       jstring jlang, jint threads, jstring jprompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewByteArray(0);

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string langStr(lang);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseStringUTFChars(jprompt, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.n_threads = threads > 0 ? threads : 4;
    params.language = langStr.c_str();
    params.detect_language = false;
    params.initial_prompt = promptStr.empty() ? nullptr : promptStr.c_str();

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return env->NewByteArray(0);
    }

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) out += text;
    }
    // Return raw UTF-8 bytes; Kotlin decodes them (NewStringUTF rejects 4-byte sequences).
    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
