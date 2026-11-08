#include "aipro_core.h"

#include <algorithm>
#include <cmath>

namespace aipro {

double evToExposureMultiplier(double ev) { return std::pow(2.0, ev); }

double exposureMultiplierToEv(double multiplier) {
  if (multiplier <= 0.0) return 0.0;
  return std::log2(multiplier);
}

int clampIso(int iso, int minIso, int maxIso) {
  if (minIso > maxIso) std::swap(minIso, maxIso);
  return std::clamp(iso, minIso, std::max(minIso, maxIso));
}

int64_t clampExposureNs(int64_t ns, int64_t minNs, int64_t maxNs) {
  if (minNs > maxNs) std::swap(minNs, maxNs);
  return std::clamp<int64_t>(ns, minNs, std::max<int64_t>(minNs, maxNs));
}

float sliderToDiopters(float slider01, float minimumFocusDistance) {
  const float s = std::clamp(slider01, 0.0f, 1.0f);
  if (minimumFocusDistance <= 0.0f) return 0.0f;  // fixed-focus lens
  // Manual focus in Camera2 is expressed in dioptres: 0 = infinity,
  // LENS_INFO_MINIMUM_FOCUS_DISTANCE = closest focus.
  return minimumFocusDistance * (1.0f - s);
}

namespace {
// Tanner Helland's black-body approximation, scaled to 0..1 channel weights.
void blackBodyRgb(int kelvin, double& r, double& g, double& b) {
  const double t = std::clamp(static_cast<double>(kelvin), 1000.0, 40000.0) / 100.0;
  if (t <= 66.0) {
    r = 255.0;
    g = 99.4708025861 * std::log(t) - 161.1195681661;
  } else {
    r = 329.698727446 * std::pow(t - 60.0, -0.1332047592);
    g = 288.1221695283 * std::pow(t - 60.0, -0.0755148492);
  }
  if (t >= 66.0) {
    b = 255.0;
  } else if (t <= 19.0) {
    b = 0.0;
  } else {
    b = 138.5177312231 * std::log(t - 10.0) - 305.0447927307;
  }
  r = std::clamp(r, 0.0, 255.0) / 255.0;
  g = std::clamp(g, 0.0, 255.0) / 255.0;
  b = std::clamp(b, 0.0, 255.0) / 255.0;
}
}  // namespace

void kelvinToRggbGains(int kelvin, float* gains) {
  double r, g, b;
  blackBodyRgb(kelvin, r, g, b);
  // The illuminant tint is removed by dividing by the sensor's green reference.
  const double eps = 1e-3;
  const double gr = (g <= eps) ? 1.0 : (r / g);
  const double gb = (g <= eps) ? 1.0 : (b / g);
  const float redGain = static_cast<float>(std::clamp(1.0 / std::max(gr, eps), 0.25, 4.0));
  const float blueGain = static_cast<float>(std::clamp(1.0 / std::max(gb, eps), 0.25, 4.0));
  gains[0] = redGain;
  gains[1] = 1.0f;
  gains[2] = 1.0f;
  gains[3] = blueGain;
}

void lumaHistogram(const uint8_t* y, int width, int height, int rowStride,
                   int pixelStride, int32_t* out) {
  for (int i = 0; i < kHistogramBins; ++i) out[i] = 0;
  if (y == nullptr || width <= 0 || height <= 0 || rowStride <= 0) return;
  const int step = pixelStride <= 0 ? 1 : pixelStride;
  for (int row = 0; row < height; ++row) {
    const uint8_t* line = y + static_cast<int64_t>(row) * rowStride;
    for (int col = 0; col < width; ++col) {
      const int value = line[static_cast<int64_t>(col) * step];
      out[value]++;
    }
  }
}

double meanLuma(const uint8_t* y, int width, int height, int rowStride, int pixelStride) {
  if (y == nullptr || width <= 0 || height <= 0 || rowStride <= 0) return 0.0;
  const int step = pixelStride <= 0 ? 1 : pixelStride;
  uint64_t sum = 0;
  for (int row = 0; row < height; ++row) {
    const uint8_t* line = y + static_cast<int64_t>(row) * rowStride;
    for (int col = 0; col < width; ++col) {
      sum += line[static_cast<int64_t>(col) * step];
    }
  }
  return static_cast<double>(sum) / (static_cast<double>(width) * height);
}

}  // namespace aipro
