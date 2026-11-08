// JNI boundary for :core:native. All maths lives in aipro_core.cpp so it stays host-testable.
#include <jni.h>

#include "aipro_core.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeApiVersion(JNIEnv*, jobject) {
  return aipro::kApiVersion;
}

JNIEXPORT jdouble JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeEvToExposureMultiplier(JNIEnv*, jobject, jdouble ev) {
  return aipro::evToExposureMultiplier(ev);
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeClampIso(JNIEnv*, jobject, jint iso, jint minIso,
                                                      jint maxIso) {
  return aipro::clampIso(iso, minIso, maxIso);
}

JNIEXPORT jlong JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeClampExposureNs(JNIEnv*, jobject, jlong ns,
                                                             jlong minNs, jlong maxNs) {
  return static_cast<jlong>(aipro::clampExposureNs(ns, minNs, maxNs));
}

JNIEXPORT jfloat JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeSliderToDiopters(JNIEnv*, jobject, jfloat slider01,
                                                              jfloat minimumFocusDistance) {
  return aipro::sliderToDiopters(slider01, minimumFocusDistance);
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeKelvinToRggbGains(JNIEnv* env, jobject, jint kelvin,
                                                               jfloatArray out) {
  jfloat gains[4] = {1.f, 1.f, 1.f, 1.f};
  aipro::kelvinToRggbGains(kelvin, gains);
  if (env->GetArrayLength(out) >= 4) {
    env->SetFloatArrayRegion(out, 0, 4, gains);
  }
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeLumaHistogram(JNIEnv* env, jobject, jobject yBuffer,
                                                           jint width, jint height, jint rowStride,
                                                           jint pixelStride, jintArray out) {
  auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
  if (y == nullptr) return -1;
  if (env->GetArrayLength(out) < aipro::kHistogramBins) return -2;
  jint bins[aipro::kHistogramBins];
  aipro::lumaHistogram(y, width, height, rowStride, pixelStride, bins);
  env->SetIntArrayRegion(out, 0, aipro::kHistogramBins, bins);
  return 0;
}

JNIEXPORT jdouble JNICALL
Java_com_aipro_nativebridge_NativeMath_nativeMeanLuma(JNIEnv* env, jobject, jobject yBuffer,
                                                      jint width, jint height, jint rowStride,
                                                      jint pixelStride) {
  auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
  if (y == nullptr) return 0.0;
  return aipro::meanLuma(y, width, height, rowStride, pixelStride);
}

}  // extern "C"
