// Pure C++17 core helpers shared by the JNI bridge and the host test harness.
// No Android/JNI dependencies live in this header so the math can be unit-tested on a desktop.
#ifndef AIPRO_CORE_H
#define AIPRO_CORE_H

#include <cstdint>

namespace aipro {

constexpr int kApiVersion = 1;
constexpr int kHistogramBins = 256;

// --- Exposure helpers -------------------------------------------------------
// Exposure value offset -> linear multiplier (2^ev).
double evToExposureMultiplier(double ev);
// Linear multiplier -> EV offset (log2).
double exposureMultiplierToEv(double multiplier);
// Clamp an ISO value into [min,max] and snap it to the nearest supported step,
// mirroring what Camera2 expects for SENSOR_SENSITIVITY.
int clampIso(int iso, int minIso, int maxIso);
// Clamp an exposure time (ns) into [minNs,maxNs].
int64_t clampExposureNs(int64_t ns, int64_t minNs, int64_t maxNs);
// Inverse-square-ish focus helper: convert a 0..1 UI slider (0=near, 1=infinity)
// into a diopter value where 0 means infinity (Camera2 LENS_FOCUS_DISTANCE).
float sliderToDiopters(float slider01, float minimumFocusDistance);

// --- White balance ----------------------------------------------------------
// Approximate colour-temperature (Kelvin) -> RGGB channel gains used with
// CONTROL_AWB_MODE_OFF + COLOR_CORRECTION_GAINS. gains must point to 4 floats
// (R, G_even, G_odd, B). Gains are normalised so the green channel equals 1.0.
void kelvinToRggbGains(int kelvin, float* gains);

// --- Histogram --------------------------------------------------------------
// Fills out[kHistogramBins] with the luma histogram of a YUV_420_888 Y plane.
// rowStride is in bytes, pixelStride in bytes per pixel (1 or 2).
void lumaHistogram(const uint8_t* y, int width, int height, int rowStride,
                   int pixelStride, int32_t* out);

// Mean luma (0..255) of the same plane, used for the scene-brightness readout.
double meanLuma(const uint8_t* y, int width, int height, int rowStride, int pixelStride);

}  // namespace aipro

#endif  // AIPRO_CORE_H
