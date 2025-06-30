#include <jni.h>
#include <android/log.h>
#include <string>

#define TAG "pocsagsdr-native"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;
static jobject gController = nullptr;
static jmethodID gOnMessageMethod = nullptr;

// Lorsque la librairie est chargée
jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_f4hbw_pocsagsdr_SDRController_nativeProcess(
        JNIEnv* env, jobject thiz,
        jbyteArray data, jint length) {
    // Garde une référence à l'objet pour callback si besoin
    if (!gController) {
        gController = env->NewGlobalRef(thiz);
        jclass cls = env->GetObjectClass(thiz);
        gOnMessageMethod = env->GetMethodID(cls, "onNativeMessage", "(Ljava/lang/String;)V");
    }

    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    // TODO: Démoduler ici POCSAG sur buf[0..length)
    // Pour cette démo, on génère une chaîne factice
    std::string msg = "Received " + std::to_string(length) + " bytes";

    // Callback Java
    jstring jmsg = env->NewStringUTF(msg.c_str());
    env->CallVoidMethod(gController, gOnMessageMethod, jmsg);
    env->DeleteLocalRef(jmsg);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    return length;
}