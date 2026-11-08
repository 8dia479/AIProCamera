// JNI bridge for video_ops. Luma planes are passed as direct ByteBuffers (MediaCodec output order).
#include <jni.h>

#include <cstdint>

#include "video_ops.h"

namespace {

inline uint8_t* lumaOf(JNIEnv* env, jobject buffer) {
  if (buffer == nullptr) return nullptr;
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
}

inline jlong capacityOf(JNIEnv* env, jobject buffer) {
  if (buffer == nullptr) return 0;
  return static_cast<jlong>(env->GetDirectBufferCapacity(buffer));
}

inline bool sized(JNIEnv* env, jobject buffer, jint width, jint height) {
  if (width <= 0 || height <= 0) return false;
  const jlong needed = static_cast<jlong>(width) * height;
  return capacityOf(env, buffer) >= needed;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeVideoOps_nativeApplyToneLutY(JNIEnv* env, jobject, jobject luma,
                                                               jint width, jint height,
                                                               jbyteArray lut) {
  if (lut == nullptr || !sized(env, luma, width, height)) return -1;
  if (env->GetArrayLength(lut) < 256) return -2;
  uint8_t* data = lumaOf(env, luma);
  if (data == nullptr) return -3;
  jbyte lutBytes[256];
  env->GetByteArrayRegion(lut, 0, 256, lutBytes);
  aipro::applyToneLutY(data, width, height, reinterpret_cast<const uint8_t*>(lutBytes));
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeVideoOps_nativeSharpenY(JNIEnv* env, jobject, jobject luma,
                                                          jint width, jint height, jfloat amount) {
  if (!sized(env, luma, width, height)) return -1;
  uint8_t* data = lumaOf(env, luma);
  if (data == nullptr) return -3;
  aipro::sharpenY(data, width, height, amount);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeVideoOps_nativeDenoiseY(JNIEnv* env, jobject, jobject luma,
                                                          jint width, jint height,
                                                          jfloat sigmaRange) {
  if (!sized(env, luma, width, height)) return -1;
  uint8_t* data = lumaOf(env, luma);
  if (data == nullptr) return -3;
  aipro::denoiseY(data, width, height, sigmaRange);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeVideoOps_nativeMirrorY(JNIEnv* env, jobject, jobject luma,
                                                         jint width, jint height) {
  if (!sized(env, luma, width, height)) return -1;
  uint8_t* data = lumaOf(env, luma);
  if (data == nullptr) return -3;
  aipro::mirrorY(data, width, height);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeVideoOps_nativeStretchY(JNIEnv* env, jobject, jobject luma,
                                                          jint width, jint height, jint low,
                                                          jint high) {
  if (!sized(env, luma, width, height)) return -1;
  uint8_t* data = lumaOf(env, luma);
  if (data == nullptr) return -3;
  aipro::stretchY(data, width, height, low, high);
  return 0;
}

}  // extern "C"
