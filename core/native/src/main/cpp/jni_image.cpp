// JNI bridge for image_ops. ARGB_8888 bitmaps are passed as Kotlin IntArray (packed 0xAARRGGBB).
#include <jni.h>

#include <cstdint>
#include <vector>

#include "image_ops.h"

namespace {

inline void* criticalInt(JNIEnv* env, jintArray array, jboolean* isCopy) {
  return env->GetPrimitiveArrayCritical(array, isCopy);
}

inline void releaseInt(JNIEnv* env, jintArray array, void* data, jint mode) {
  env->ReleasePrimitiveArrayCritical(array, data, mode);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeBilateral(JNIEnv* env, jobject, jintArray pixels,
                                                           jint width, jint height, jint radius,
                                                           jfloat sigmaRange, jfloat sigmaSpatial) {
  if (pixels == nullptr || width <= 0 || height <= 0) return -1;
  jboolean copy = JNI_FALSE;
  auto* data = static_cast<uint32_t*>(criticalInt(env, pixels, &copy));
  if (data == nullptr) return -2;
  aipro::bilateralDenoise(data, width, height, radius, sigmaRange, sigmaSpatial);
  releaseInt(env, pixels, data, JNI_ABORT);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeUnsharp(JNIEnv* env, jobject, jintArray pixels,
                                                         jint width, jint height, jfloat amount,
                                                         jint radius) {
  if (pixels == nullptr) return -1;
  jboolean copy = JNI_FALSE;
  auto* data = static_cast<uint32_t*>(criticalInt(env, pixels, &copy));
  if (data == nullptr) return -2;
  aipro::unsharpMask(data, width, height, amount, radius);
  releaseInt(env, pixels, data, JNI_ABORT);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeResizeBicubic(JNIEnv* env, jobject,
                                                               jintArray src, jint srcWidth,
                                                               jint srcHeight, jintArray dst,
                                                               jint dstWidth, jint dstHeight) {
  if (src == nullptr || dst == nullptr) return -1;
  jboolean copy = JNI_FALSE;
  auto* srcData = static_cast<uint32_t*>(criticalInt(env, src, &copy));
  auto* dstData = static_cast<uint32_t*>(criticalInt(env, dst, &copy));
  if (srcData == nullptr || dstData == nullptr) return -2;
  aipro::resizeBicubic(srcData, srcWidth, srcHeight, dstData, dstWidth, dstHeight);
  releaseInt(env, src, srcData, JNI_ABORT);
  releaseInt(env, dst, dstData, 0);  // copy back
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeToneAndColor(JNIEnv* env, jobject, jintArray pixels,
                                                              jint width, jint height,
                                                              jbyteArray lut, jfloat saturation,
                                                              jfloat temperature) {
  if (pixels == nullptr) return -1;
  jboolean copy = JNI_FALSE;
  auto* data = static_cast<uint32_t*>(criticalInt(env, pixels, &copy));
  if (data == nullptr) return -2;
  uint8_t localLut[256];
  if (lut != nullptr && env->GetArrayLength(lut) >= 256) {
    env->GetByteArrayRegion(lut, 0, 256, reinterpret_cast<jbyte*>(localLut));
    aipro::toneAndColor(data, width, height, localLut, saturation, temperature);
  } else {
    aipro::toneAndColor(data, width, height, nullptr, saturation, temperature);
  }
  releaseInt(env, pixels, data, JNI_ABORT);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativePortraitSmooth(JNIEnv* env, jobject,
                                                               jintArray pixels, jint width,
                                                               jint height, jfloat strength) {
  if (pixels == nullptr) return -1;
  jboolean copy = JNI_FALSE;
  auto* data = static_cast<uint32_t*>(criticalInt(env, pixels, &copy));
  if (data == nullptr) return -2;
  aipro::portraitSmooth(data, width, height, strength);
  releaseInt(env, pixels, data, JNI_ABORT);
  return 0;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeAnalyze(JNIEnv* env, jobject, jintArray pixels,
                                                         jint width, jint height) {
  if (pixels == nullptr) return nullptr;
  jboolean copy = JNI_FALSE;
  auto* data = static_cast<uint32_t*>(criticalInt(env, pixels, &copy));
  if (data == nullptr) return nullptr;
  const aipro::Stats stats = aipro::analyze(data, width, height);
  releaseInt(env, pixels, data, JNI_ABORT);
  double values[5] = {stats.meanLuma, stats.contrast, stats.clippedHighlights,
                      stats.clippedShadows, stats.saturationMean};
  jdoubleArray out = env->NewDoubleArray(5);
  env->SetDoubleArrayRegion(out, 0, 5, values);
  return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aipro_nativebridge_NativeImageOps_nativeBuildPurposeLut(JNIEnv* env, jobject, jint purpose,
                                                                 jdouble contrast) {
  uint8_t lut[256];
  aipro::buildPurposeLut(lut, purpose, contrast);
  jbyteArray out = env->NewByteArray(256);
  env->SetByteArrayRegion(out, 0, 256, reinterpret_cast<jbyte*>(lut));
  return out;
}

}  // extern "C"
