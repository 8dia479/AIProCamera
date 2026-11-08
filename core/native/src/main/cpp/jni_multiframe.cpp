// JNI bridge for multiframe.cpp. Frames arrive as Kotlin ByteArray luma planes.
#include <jni.h>

#include <cstdint>
#include <vector>

#include "multiframe.h"

extern "C" {

JNIEXPORT jintArray JNICALL
Java_com_aipro_nativebridge_NativeMultiFrame_nativeEstimateShift(JNIEnv* env, jobject,
                                                                 jbyteArray ref, jbyteArray alt,
                                                                 jint width, jint height, jint stride,
                                                                 jint searchRadius) {
  if (ref == nullptr || alt == nullptr || width <= 0 || height <= 0) return nullptr;
  auto* refData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(ref, nullptr));
  auto* altData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(alt, nullptr));
  if (refData == nullptr || altData == nullptr) return nullptr;
  const aipro::Shift shift =
      aipro::estimateShift(refData, altData, width, height, stride, searchRadius);
  env->ReleasePrimitiveArrayCritical(ref, refData, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(alt, altData, JNI_ABORT);
  jint values[3] = {shift.dx, shift.dy,
                    static_cast<jint>(shift.confidence * 1000.0f)};
  jintArray out = env->NewIntArray(3);
  env->SetIntArrayRegion(out, 0, 3, values);
  return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_aipro_nativebridge_NativeMultiFrame_nativeMergeFrames(JNIEnv* env, jobject,
                                                               jobjectArray frames, jint width,
                                                               jint height, jint stride,
                                                               jbyteArray out, jint mode,
                                                               jfloat motionThreshold,
                                                               jint maxFrames) {
  if (frames == nullptr || out == nullptr || width <= 0 || height <= 0) return nullptr;
  const jsize count = env->GetArrayLength(frames);
  if (count <= 0) return nullptr;

  std::vector<std::vector<uint8_t>> copies;
  std::vector<const uint8_t*> pointers;
  copies.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto* array = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
    const jsize length = env->GetArrayLength(array);
    std::vector<uint8_t> copy(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(copy.data()));
    env->DeleteLocalRef(array);
    copies.push_back(std::move(copy));
  }
  for (auto& copy : copies) pointers.push_back(copy.data());

  jboolean copyOut = JNI_FALSE;
  auto* outData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, &copyOut));
  if (outData == nullptr) return nullptr;
  const aipro::MergeStats stats = aipro::mergeFrames(
      pointers, width, height, stride, outData,
      mode == 1 ? aipro::MergeMode::kExposureFusion : aipro::MergeMode::kNightStack,
      motionThreshold, maxFrames);
  env->ReleasePrimitiveArrayCritical(out, outData, 0);

  jfloat values[4] = {static_cast<jfloat>(stats.framesUsed), stats.meanShiftPx, stats.motionRatio,
                      stats.noiseBefore > 0.0f ? stats.noiseAfter / stats.noiseBefore : 1.0f};
  jfloatArray result = env->NewFloatArray(4);
  env->SetFloatArrayRegion(result, 0, 4, values);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeMultiFrame_nativeYuv420ToNv21(
    JNIEnv* env, jobject, jbyteArray y, jbyteArray u, jbyteArray v, jint width, jint height,
    jint yStride, jint uStride, jint vStride, jint uPixelStride, jint vPixelStride, jbyteArray out) {
  if (y == nullptr || u == nullptr || v == nullptr || out == nullptr) return -1;
  auto* yData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(y, nullptr));
  auto* uData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(u, nullptr));
  auto* vData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(v, nullptr));
  auto* outData = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (yData == nullptr || uData == nullptr || vData == nullptr || outData == nullptr) return -2;
  aipro::yuv420ToNv21(yData, uData, vData, width, height, yStride, uStride, vStride, uPixelStride,
                      vPixelStride, outData);
  env->ReleasePrimitiveArrayCritical(y, yData, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(u, uData, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(v, vData, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(out, outData, 0);
  return 0;
}

}  // extern "C"
