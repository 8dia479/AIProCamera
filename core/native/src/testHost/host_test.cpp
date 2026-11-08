// Host (desktop) verification of the pure C++ core. Build/run:
//   g++ -std=c++17 -O2 -I../main/cpp ../main/cpp/aipro_core.cpp host_test.cpp -o /tmp/aipro_host_test && /tmp/aipro_host_test
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "aipro_core.h"

namespace {
int g_failures = 0;

void expect(bool cond, const char* what) {
  if (!cond) {
    std::printf("FAIL: %s\n", what);
    ++g_failures;
  }
}

void expectNear(double a, double b, double tol, const char* what) {
  if (std::fabs(a - b) > tol) {
    std::printf("FAIL: %s (got %.6f want %.6f)\n", what, a, b);
    ++g_failures;
  }
}
}  // namespace

int main() {
  // Exposure: 1 EV doubles, -1 EV halves, and the pair inverts.
  expectNear(aipro::evToExposureMultiplier(1.0), 2.0, 1e-9, "ev +1 = 2x");
  expectNear(aipro::evToExposureMultiplier(-2.0), 0.25, 1e-9, "ev -2 = 0.25x");
  expectNear(aipro::exposureMultiplierToEv(aipro::evToExposureMultiplier(1.3)), 1.3, 1e-9,
             "ev roundtrip");

  // ISO / exposure clamping against reported hardware ranges.
  expect(aipro::clampIso(99999, 50, 3200) == 3200, "iso upper clamp");
  expect(aipro::clampIso(10, 50, 3200) == 50, "iso lower clamp");
  expect(aipro::clampIso(400, 50, 3200) == 400, "iso passthrough");
  expect(aipro::clampIso(400, 3200, 50) == 400, "iso swapped range tolerated");
  expect(aipro::clampExposureNs(1'000'000, 100'000, 30'000'000'000LL) == 1'000'000,
         "exposure passthrough");
  expect(aipro::clampExposureNs(1, 100'000, 30'000'000'000LL) == 100'000, "exposure clamp low");

  // Manual focus slider: 1.0 -> infinity (0 dioptres), 0.0 -> closest focus.
  expectNear(aipro::sliderToDiopters(1.0f, 10.0f), 0.0f, 1e-6, "slider 1 = infinity");
  expectNear(aipro::sliderToDiopters(0.0f, 10.0f), 10.0f, 1e-6, "slider 0 = min distance");
  expectNear(aipro::sliderToDiopters(0.5f, 10.0f), 5.0f, 1e-6, "slider 0.5 = half");

  // White balance gains neutralise the illuminant: telling the pipeline the light is warm (2600K)
  // means the warm cast must be removed, so blue is boosted over red - and vice versa for 8000K.
  float warm[4] = {0, 0, 0, 0};
  float cool[4] = {0, 0, 0, 0};
  aipro::kelvinToRggbGains(2600, warm);
  aipro::kelvinToRggbGains(8000, cool);
  expect(warm[3] > warm[0], "2600K setting boosts blue (removes warm cast)");
  expect(cool[0] > cool[3], "8000K setting boosts red (removes cool cast)");
  expectNear(warm[1], 1.0, 1e-6, "green even gain normalised");
  expectNear(warm[2], 1.0, 1e-6, "green odd gain normalised");
  expect(warm[0] >= 0.25f && warm[0] <= 4.0f, "gain bounded");
  expect(cool[3] >= 0.25f && cool[3] <= 4.0f, "cool gain bounded");

  // Histogram over a strided plane (rowStride > width, as produced by Camera2).
  const int w = 4, h = 3, rowStride = 8;
  std::vector<uint8_t> plane(static_cast<size_t>(rowStride) * h, 0);
  for (int r = 0; r < h; ++r) {
    for (int c = 0; c < w; ++c) plane[r * rowStride + c] = static_cast<uint8_t>(10 * (r + c));
  }
  std::vector<int32_t> bins(aipro::kHistogramBins, 0);
  aipro::lumaHistogram(plane.data(), w, h, rowStride, 1, bins.data());
  int64_t total = 0;
  for (auto v : bins) total += v;
  expect(total == w * h, "histogram counts every pixel");
  expect(bins[10 * 2] > 0, "histogram places pixel in the right bin");
  // Values: row0 0,10,20,30 | row1 10,20,30,40 | row2 20,30,40,50  -> mean 25.
  expectNear(aipro::meanLuma(plane.data(), w, h, rowStride, 1), 25.0, 1e-9, "mean luma");

  // Null / degenerate input must not crash (preview can tear down mid-frame).
  aipro::lumaHistogram(nullptr, w, h, rowStride, 1, bins.data());
  expectNear(aipro::meanLuma(nullptr, 0, 0, 0, 1), 0.0, 1e-9, "null plane safe");

  if (g_failures == 0) {
    std::printf("ALL NATIVE TESTS PASSED (api v%d)\n", aipro::kApiVersion);
    return 0;
  }
  std::printf("%d NATIVE TEST FAILURES\n", g_failures);
  return 1;
}
