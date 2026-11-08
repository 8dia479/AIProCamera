#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "video_ops.h"

static int failures = 0;
#define CHECK(cond, msg)                                                    \
  do {                                                                      \
    if (!(cond)) {                                                          \
      std::printf("FAIL: %s (line %d)\n", msg, __LINE__);                   \
      ++failures;                                                           \
    }                                                                       \
  } while (0)

static double variance(const std::vector<uint8_t>& v) {
  double mean = 0;
  for (auto x : v) mean += x;
  mean /= v.size();
  double acc = 0;
  for (auto x : v) acc += (x - mean) * (x - mean);
  return acc / v.size();
}

int main() {
  const int w = 64, h = 48;

  // 1. LUT is applied verbatim.
  {
    std::vector<uint8_t> len(w * h);
    for (int i = 0; i < w * h; ++i) len[i] = static_cast<uint8_t>(i * 7 % 256);
    uint8_t lut[256];
    for (int i = 0; i < 256; ++i) lut[i] = static_cast<uint8_t>(255 - i);
    std::vector<uint8_t> base = len;
    aipro::applyToneLutY(len.data(), w, h, lut);
    int mismatches = 0;
    for (int i = 0; i < w * h; ++i) {
      if (len[i] != (255 - base[i])) ++mismatches;
    }
    CHECK(mismatches == 0, "tone LUT must map every sample");
  }

  // 2. Identity LUT is a no-op.
  {
    std::vector<uint8_t> plane(w * h);
    for (int i = 0; i < w * h; ++i) plane[i] = static_cast<uint8_t>((i * 13) % 256);
    std::vector<uint8_t> base = plane;
    uint8_t lut[256];
    for (int i = 0; i < 256; ++i) lut[i] = static_cast<uint8_t>(i);
    aipro::applyToneLutY(plane.data(), w, h, lut);
    CHECK(plane == base, "identity LUT must not change pixels");
  }

  // 3. Sharpen raises edge contrast.
  {
    std::vector<uint8_t> plane(w * h, 40);
    for (int row = 0; row < h; ++row) {
      for (int col = w / 2; col < w; ++col) plane[row * w + col] = 200;
    }
    std::vector<uint8_t> base = plane;
    aipro::sharpenY(plane.data(), w, h, 1.2f);
    const int centre = (h / 2) * w + w / 2;
    const int before = static_cast<int>(base[centre]) - static_cast<int>(base[centre - 1]);
    const int after = static_cast<int>(plane[centre]) - static_cast<int>(plane[centre - 1]);
    CHECK(after > before, "sharpen must increase the edge step");
    CHECK(plane[0] == base[0], "sharpen must leave the border untouched");
  }

  // 4. Sharpen with amount 0 is a no-op and out-of-range amount is clamped, not crashing.
  {
    std::vector<uint8_t> plane(w * h);
    for (int i = 0; i < w * h; ++i) plane[i] = static_cast<uint8_t>((i * 3) % 256);
    std::vector<uint8_t> base = plane;
    aipro::sharpenY(plane.data(), w, h, 0.0f);
    CHECK(plane == base, "amount 0 must be a no-op");
    aipro::sharpenY(plane.data(), w, h, 9.0f);
    for (auto v : plane) CHECK(v >= 0 && v <= 255, "sharpen must clamp");
  }

  // 5. Denoise lowers noise variance on a flat patch.
  {
    std::vector<uint8_t> clean(w * h, 128);
    std::vector<uint8_t> noisy = clean;
    unsigned seed = 12345u;
    for (auto& v : noisy) {
      seed = seed * 1664525u + 1013904223u;
      const int n = static_cast<int>((seed >> 16) % 41) - 20;
      const int value = 128 + n;
      v = static_cast<uint8_t>(value < 0 ? 0 : (value > 255 ? 255 : value));
    }
    const double before = variance(noisy);
    aipro::denoiseY(noisy.data(), w, h, 14.0f);
    const double after = variance(noisy);
    CHECK(after < before * 0.75, "denoise must cut luma noise variance by >= 25%");
  }

  // 6. Denoise keeps a strong edge (edge-preserving).
  {
    std::vector<uint8_t> plane(w * h, 50);
    for (int row = 0; row < h; ++row) {
      for (int col = w / 2; col < w; ++col) plane[row * w + col] = 200;
    }
    aipro::denoiseY(plane.data(), w, h, 8.0f);
    const int centre = (h / 2) * w + w / 2;
    CHECK(plane[centre] >= 195, "denoise must keep the bright side of a hard edge");
    CHECK(plane[(h / 2) * w + (w / 2 - 1)] <= 55, "denoise must keep the dark side of a hard edge");
  }

  // 7. Mirror flips rows, keeps row order.
  {
    std::vector<uint8_t> plane(w * h);
    for (int row = 0; row < h; ++row) {
      for (int col = 0; col < w; ++col) plane[row * w + col] = static_cast<uint8_t>(row);
    }
    for (int col = 0; col < w; ++col) plane[col] = static_cast<uint8_t>(col);
    aipro::mirrorY(plane.data(), w, h);
    CHECK(plane[0] == static_cast<uint8_t>(w - 1), "mirror must reverse the first row");
    CHECK(plane[w] == 1, "mirror must not move rows");
  }

  // 8. Stretch remaps the window, and a degenerate window is ignored.
  {
    std::vector<uint8_t> plane(w * h);
    for (int i = 0; i < w * h; ++i) plane[i] = static_cast<uint8_t>(64 + (i % 64));
    aipro::stretchY(plane.data(), w, h, 64, 127);
    CHECK(plane[0] == 0, "stretch must map the low bound to 0");
    CHECK(plane[63] == 255, "stretch must map the high bound to 255");
    std::vector<uint8_t> same(8, 12);
    aipro::stretchY(same.data(), 4, 2, 100, 100);
    CHECK(same[0] == 12, "degenerate window must be ignored");
  }

  // 9. Degenerate sizes do not crash.
  {
    uint8_t one = 77;
    aipro::applyToneLutY(&one, 1, 1, nullptr);
    aipro::sharpenY(&one, 1, 1, 1.0f);
    aipro::denoiseY(&one, 1, 1, 5.0f);
    aipro::mirrorY(&one, 1, 1);
    aipro::stretchY(&one, 1, 1, 0, 255);
    CHECK(one == 77, "1x1 plane with no LUT stays untouched");
    aipro::applyToneLutY(nullptr, 0, 0, nullptr);
  }

  if (failures == 0) {
    std::printf("ALL VIDEO OPS TESTS PASSED\n");
    return 0;
  }
  std::printf("%d VIDEO OPS FAILURES\n", failures);
  return 1;
}
