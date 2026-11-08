// Host verification for multiframe.cpp (alignment, night stacking, HDR fusion, NV21 conversion).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "multiframe.h"

namespace {
int g_failures = 0;
void expect(bool cond, const char* what) {
  if (!cond) {
    std::printf("FAIL: %s\n", what);
    ++g_failures;
  }
}

uint32_t nextRandom(uint32_t& state) {
  state = state * 1664525u + 1013904223u;
  return state;
}

// Noise magnitude `amplitude`, optional shift and an optional bright patch that "moves".
std::vector<uint8_t> makeFrame(int w, int h, int stride, int amplitude, int shiftX, int shiftY,
                               int base = 120, int patchX = -100, int patchY = -100,
                               int frameIndex = 0) {
  std::vector<uint8_t> plane(static_cast<size_t>(stride) * h, 0);
  // Different frames must carry *independent* sensor noise, otherwise stacking cannot show a gain.
  uint32_t seed = 999u + static_cast<uint32_t>(amplitude * 7 + shiftX * 31 + shiftY * 17) +
                  static_cast<uint32_t>(frameIndex) * 7919u;
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      // Aperiodic structure (sinusoids + block hash) so alignment is well posed.
      const int cx = x + shiftX;
      const int cyy = y + shiftY;
      const uint32_t blockHash = (static_cast<uint32_t>(cx / 8) * 73856093u) ^
                                 (static_cast<uint32_t>(cyy / 8) * 19349663u);
      const int texture = base +
          static_cast<int>(22.0 * std::sin(cx / 9.0) * std::cos(cyy / 13.0)) +
          static_cast<int>(blockHash % 15u);
      const int noise = static_cast<int>(nextRandom(seed) >> 28) - 8;
      int value = texture + noise * amplitude / 8;
      if (std::abs(x - patchX) < 12 && std::abs(y - patchY) < 12) value = 240;
      plane[static_cast<size_t>(y) * stride + x] = static_cast<uint8_t>(std::max(0, std::min(255, value)));
    }
  }
  return plane;
}

double variance(const std::vector<uint8_t>& plane, int w, int h, int stride) {
  double sum = 0, sumSq = 0;
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      const double v = plane[static_cast<size_t>(y) * stride + x];
      sum += v;
      sumSq += v * v;
    }
  }
  const double n = static_cast<double>(w) * h;
  const double mean = sum / n;
  return sumSq / n - mean * mean;
}
}  // namespace

int main() {
  const int w = 320, h = 240, stride = 320;

  // --- 1. shift estimation ------------------------------------------------
  auto ref = makeFrame(w, h, stride, 0, 0, 0);
  auto shifted = makeFrame(w, h, stride, 0, 4, -3);
  const aipro::Shift shift = aipro::estimateShift(ref.data(), shifted.data(), w, h, stride, 12);
  expect(std::abs(shift.dx) <= 6 && std::abs(shift.dy) <= 6, "shift estimate stays near the truth");
  expect(shift.confidence > 0.2f, "shift estimate reports confidence");

  // --- 2. night stacking reduces noise ------------------------------------
  std::vector<std::vector<uint8_t>> noisyFrames;
  for (int i = 0; i < 6; ++i) noisyFrames.push_back(makeFrame(w, h, stride, 40, 0, 0, 120, -100, -100, i));
  std::vector<const uint8_t*> ptrs;
  for (auto& f : noisyFrames) ptrs.push_back(f.data());
  std::vector<uint8_t> merged(static_cast<size_t>(stride) * h, 0);
  const aipro::MergeStats nightStats =
      aipro::mergeFrames(ptrs, w, h, stride, merged.data(), aipro::MergeMode::kNightStack, 18.0f, 9);
  expect(nightStats.framesUsed == 6, "all frames used");
  expect(nightStats.noiseAfter < nightStats.noiseBefore * 0.75f, "multi-frame stacking lowers noise");
  expect(variance(merged, w, h, stride) < variance(noisyFrames[0], w, h, stride),
         "merged frame has lower variance than a single frame");
  const double meanSingle = 120.0;
  double meanMerged = 0;
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) meanMerged += merged[static_cast<size_t>(y) * stride + x];
  meanMerged /= static_cast<double>(w) * h;
  expect(std::fabs(meanMerged - meanSingle) < 20.0, "stacking preserves overall exposure");

  // --- 3. moving subject must not ghost -----------------------------------
  std::vector<std::vector<uint8_t>> withMotion;
  withMotion.push_back(makeFrame(w, h, stride, 0, 0, 0, 120, 40, 40, 0));
  for (int i = 1; i < 4; ++i) {
    withMotion.push_back(makeFrame(w, h, stride, 0, 0, 0, 120, 40 + i * 20, 40, i));
  }
  std::vector<const uint8_t*> movingPtrs;
  for (auto& f : withMotion) movingPtrs.push_back(f.data());
  std::vector<uint8_t> mergedMotion(static_cast<size_t>(stride) * h, 0);
  const aipro::MergeStats motionStats =
      aipro::mergeFrames(movingPtrs, w, h, stride, mergedMotion.data(), aipro::MergeMode::kNightStack, 18.0f, 9);
  expect(motionStats.motionRatio > 0.0f, "motion is detected and down-weighted");
  // Where the patch sits in frame 0 it must stay bright (not averaged into the background).
  const int refPatch = mergedMotion[static_cast<size_t>(40) * stride + 40];
  expect(refPatch > 180, "first-frame subject position is preserved (no ghosting)");

  // --- 4. HDR exposure fusion keeps highlights ----------------------------
  auto dark = makeFrame(w, h, stride, 0, 0, 0, 40);
  auto mid = makeFrame(w, h, stride, 0, 0, 0, 128);
  auto bright = makeFrame(w, h, stride, 0, 0, 0, 240);
  std::vector<const uint8_t*> hdrFrames = {bright.data(), mid.data(), dark.data()};
  std::vector<uint8_t> fused(static_cast<size_t>(stride) * h, 0);
  aipro::mergeFrames(hdrFrames, w, h, stride, fused.data(), aipro::MergeMode::kExposureFusion, 18.0f, 3);
  double fusedMean = 0;
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) fusedMean += fused[static_cast<size_t>(y) * stride + x];
  fusedMean /= static_cast<double>(w) * h;
  expect(fusedMean > 90 && fusedMean < 190, "exposure fusion lands between the darkest and brightest frame");

  // --- 5. degenerate inputs ------------------------------------------------
  std::vector<uint8_t> empty(static_cast<size_t>(stride) * h, 0);
  const aipro::MergeStats emptyStats =
      aipro::mergeFrames({}, w, h, stride, empty.data(), aipro::MergeMode::kNightStack, 18.0f, 9);
  expect(emptyStats.framesUsed == 0, "empty frame list is handled");

  // --- 6. YUV_420_888 -> NV21 ---------------------------------------------
  const int yw = 4, yh = 4;
  std::vector<uint8_t> yPlane = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
  std::vector<uint8_t> uPlane = {100, 101, 102, 103};
  std::vector<uint8_t> vPlane = {200, 201, 202, 203};
  std::vector<uint8_t> nv21(static_cast<size_t>(yw) * yh * 3 / 2, 0);
  aipro::yuv420ToNv21(yPlane.data(), uPlane.data(), vPlane.data(), yw, yh, yw, 2, 2, 1, 1, nv21.data());
  expect(nv21[0] == 1 && nv21[15] == 16, "NV21 keeps the luma plane");
  expect(nv21[16] == 200 && nv21[17] == 100, "NV21 interleaves V then U");

  if (g_failures == 0) {
    std::printf("ALL MULTIFRAME TESTS PASSED\n");
    return 0;
  }
  std::printf("%d MULTIFRAME FAILURES\n", g_failures);
  return 1;
}
