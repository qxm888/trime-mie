// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include "rime_api.h"

namespace {
JavaVM* vm = nullptr;
jclass rime_class = nullptr;
jclass object_class = nullptr;
jmethodID handle_message = nullptr;

void fail(JNIEnv* env, const char* message) {
  const auto cls = env->FindClass("java/lang/IllegalStateException");
  if (cls) env->ThrowNew(cls, message);
}

void notify(void*, RimeSessionId, const char* type, const char* value) {
  int message_type = 0;
  if (std::strcmp(type, "schema") == 0) message_type = 1;
  else if (std::strcmp(type, "option") == 0) message_type = 2;
  else if (std::strcmp(type, "deploy") == 0) message_type = 3;
  if (!message_type) return;

  JNIEnv* env = nullptr;
  const bool attach = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
  if (attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
  if (!env) return;
  if (env->PushLocalFrame(4) == JNI_OK) {
    const auto params = env->NewObjectArray(1, object_class, nullptr);
    const auto text = env->NewStringUTF(value);
    if (params && text) {
      env->SetObjectArrayElement(params, 0, text);
      if (!env->ExceptionCheck()) env->CallStaticVoidMethod(rime_class, handle_message, message_type, params);
    }
    env->PopLocalFrame(nullptr);
  }
  // Preserve synchronous Java exceptions on their calling thread. Attached
  // native worker threads have no Java caller to receive an exception.
  if (attach) {
    if (env->ExceptionCheck()) {
      __android_log_print(ANDROID_LOG_ERROR, "MieStartup", "Rime notification callback failed");
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    vm->DetachCurrentThread();
  }
}

bool set_path(JNIEnv* env, jstring input, const char* name) {
  const auto value = env->GetStringUTFChars(input, nullptr);
  if (!value) return false;
  const bool ok = setenv(name, value, 1) == 0;
  env->ReleaseStringUTFChars(input, value);
  if (!ok) fail(env, "Cannot set Rime data directory");
  return ok;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_startupRimeWithoutMaintenance(
    JNIEnv* env, jclass clazz, jstring shared, jstring user, jstring version) {
  // Rime's Java initializer has already loaded librime_jni and registered its
  // plugins. Use only its versioned public API; never patch its code/API table.
  static void* library = dlopen("librime_jni.so", RTLD_NOW | RTLD_LOCAL);
  if (!library) { fail(env, "Cannot open the existing Rime engine"); return; }
  const auto get_api = reinterpret_cast<RimeApi* (*)()>(dlsym(library, "rime_get_api"));
  const auto api = get_api ? get_api() : nullptr;
  if (!RIME_PROVIDED(api, setup) || !RIME_PROVIDED(api, initialize) ||
      !RIME_PROVIDED(api, set_notification_handler) || !RIME_PROVIDED(api, deployer_initialize)) {
    fail(env, "Rime engine does not provide the required startup API"); return;
  }
  if (!rime_class) {
    if (env->GetJavaVM(&vm) != JNI_OK) { fail(env, "Cannot obtain Java VM"); return; }
    handle_message = env->GetStaticMethodID(clazz, "handleRimeMessage", "(I[Ljava/lang/Object;)V");
    if (!handle_message) return;
    const auto object = env->FindClass("java/lang/Object");
    if (!object) return;
    object_class = static_cast<jclass>(env->NewGlobalRef(object));
    env->DeleteLocalRef(object);
    if (!object_class) return;
    rime_class = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (!rime_class) { env->DeleteGlobalRef(object_class); object_class = nullptr; return; }
  }
  if (!set_path(env, shared, "RIME_SHARED_DATA_DIR") ||
      !set_path(env, user, "RIME_USER_DATA_DIR") ||
      !set_path(env, version, "RIME_DISTRIBUTION_VERSION")) return;

  RimeTraits traits{};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = getenv("RIME_SHARED_DATA_DIR");
  traits.user_data_dir = getenv("RIME_USER_DATA_DIR");
  traits.distribution_name = "Trime";
  traits.distribution_code_name = "trime";
  traits.distribution_version = getenv("RIME_DISTRIBUTION_VERSION");
  traits.app_name = "rime.trime";
  traits.log_dir = "";
  api->setup(&traits);
  api->initialize(&traits);
  // Register deployment/sync task types without scheduling or running them.
  // Existing dictionary sync remains usable even before a manual deployment.
  api->deployer_initialize(&traits);
  api->set_notification_handler(notify, nullptr);
  __android_log_print(ANDROID_LOG_INFO, "MieStartup", "Initialized existing Rime data; automatic maintenance disabled");
}
