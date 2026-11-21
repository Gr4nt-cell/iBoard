// JNI bridge between iBoard (com.gr4nt.iboard.voice.WhisperLib) and whisper.cpp.
#include <jni.h>
#include <string>
#include <atomic>
#include <chrono>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_ERROR 6
#define __android_log_print(priority, tag, ...) std::fprintf(stderr, __VA_ARGS__)
#endif
#include "whisper.h"

#define TAG "iBoardWhisper"

struct SpeechSession {
    whisper_context * context;
    std::atomic<bool> cancelled {false};
    std::atomic<int> progress {0};
};

struct DecodeControl {
    SpeechSession * session;
    std::chrono::steady_clock::time_point deadline;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load model");
    return ctx ? reinterpret_cast<jlong>(new SpeechSession{ctx}) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<SpeechSession *>(handle);
    if (session) { whisper_free(session->context); delete session; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeCancel(JNIEnv *, jclass, jlong handle) {
    if (handle) reinterpret_cast<SpeechSession *>(handle)->cancelled.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeProgress(JNIEnv *, jclass, jlong handle) {
    return handle ? reinterpret_cast<SpeechSession *>(handle)->progress.load() : 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gr4nt_iboard_voice_WhisperLib_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray jsamples,
                                                       jstring jlang, jint threads, jstring jprompt, jint timeoutMs) {
    auto *session = reinterpret_cast<SpeechSession *>(handle);
    if (!session || session->cancelled.load()) return env->NewByteArray(0);
    auto *ctx = session->context;
    session->progress.store(0);
    DecodeControl control {session, std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 45000)};

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string langStr(lang);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseStringUTFChars(jprompt, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // One decoder avoids the five simultaneous hypotheses used in the previous keyboard build.
    params.greedy.best_of = 1;
    params.temperature_inc = 0.0f;
    params.abort_callback = [](void *data) {
        auto *control = static_cast<DecodeControl *>(data);
        return control->session->cancelled.load() || std::chrono::steady_clock::now() >= control->deadline;
    };
    params.abort_callback_user_data = &control;
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *data) {
        static_cast<SpeechSession *>(data)->progress.store(progress);
    };
    params.progress_callback_user_data = session;
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
    if (session->cancelled.load()) return env->NewByteArray(0);
    if (std::chrono::steady_clock::now() >= control.deadline) {
        env->ThrowNew(env->FindClass("java/util/concurrent/TimeoutException"),
            "Offline transcription took too long. Please try a shorter phrase.");
        return nullptr;
    }
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Offline speech inference failed");
        return nullptr;
    }

    session->progress.store(100);
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
