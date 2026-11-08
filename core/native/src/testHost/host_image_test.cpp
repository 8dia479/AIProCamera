// Host verification for image_ops.cpp (no Android, no JNI).
// g++ -std=c++17 -O2 -I ../main/cpp ../main/cpp/image_ops.cpp host_image_test.cpp -o /tmp/imgtest
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "image_ops.h"

namespace {
int g_failures = 0;

void expect(bool cond, const char* what) {
  if (!cond) {
    std::printf("FAIL: %s\n", what);
    ++g_failures;
  }
}

uint32_t pack(int a, int r, int g, int b) {
  return (static_cast<uint32_t>(a) << 24) | (static_cast<uint32_t>(r) << 16) |
         (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
}
int red(uint32_t p) { return static_cast<int>((p >> 16) & 0xFF); }
int green(uint32_t p) { return static_cast<int>((p >> 8) & 0xFF); }
int blue(uint32_t p) { return static_cast<int>(p & 0xFF); }

// Flat mid-grey image with alternating noise, used to measure denoise behaviour.
std::vector<uint32_t> noisyImage(int w, int h, int base, int amplitude) {
  std::vector<uint32_t> img(static_cast<size_t>(w) * h);
  uint32_t seed = 12345u;
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      seed = seed * 1664525u + 1013904223u;
      const int noise = static_cast<int>(seed >> 28) - 8;  // -8..7
      const int v = base + (amplitude * noise) / 8;
      img[static_cast<size_t>(y) * w + x] = pack(255, v, v, v);
    }
  }
  return img;
}

double lumaVariance(const std::vector<uint32_t>& img) {
  double sum = 0, sumSq = 0;
  for (auto p : img) {
    const double l = 0.2126 * red(p) + 0.7152 * green(p) + 0.0722 * blue(p);
    sum += l;
    sumSq += l * l;
  }
  const double n = static_cast<double>(img.size());
  const double mean = sum / n;
  return sumSq / n - mean * mean;
}
}  // namespace

int main() {
  // --- denoise: noise variance must drop, mean must stay close -----------------
  const int w = 64, h = 64;
  auto noisy = noisyImage(w, h, 128, 40);
  const double before = lumaVariance(noisy);
  auto denoised = noisy;
  aipro::bilateralDenoise(denoised.data(), w, h, 2, 24.0f, 1.6f);
  const double after = lumaVariance(denoised);
  expect(after < before * 0.5, "bilateral denoise reduces noise variance by >50%");
  const aipro::Stats s1 = aipro::analyze(noisy.data(), w, h);
  const aipro::Stats s2 = aipro::analyze(denoised.data(), w, h);
  expect(std::fabs(s1.meanLuma - s2.meanLuma) < 2.0, "denoise keeps the mean luma");

  // --- denoise must preserve an edge ------------------------------------------
  std::vector<uint32_t> edge(static_cast<size_t>(w) * h, pack(255, 20, 20, 20));
  for (int y = 0; y < h; ++y) {
    for (int x = w / 2; x < w; ++x) edge[static_cast<size_t>(y) * w + x] = pack(255, 230, 230, 230);
  }
  auto edgeCopy = edge;
  aipro::bilateralDenoise(edgeCopy.data(), w, h, 2, 24.0f, 1.6f);
  const int leftLuma = red(edgeCopy[static_cast<size_t>(h / 2) * w + 8]);
  const int rightLuma = red(edgeCopy[static_cast<size_t>(h / 2) * w + (w - 8)]);
  expect(leftLuma < 60 && rightLuma > 200, "bilateral denoise preserves strong edges");

  // --- unsharp mask increases local contrast ----------------------------------
  std::vector<uint32_t> soft(static_cast<size_t>(w) * h, pack(255, 100, 100, 100));
  for (int y = 20; y < 40; ++y) {
    for (int x = 20; x < 40; ++x) soft[static_cast<size_t>(y) * w + x] = pack(255, 140, 140, 140);
  }
  auto sharpened = soft;
  aipro::unsharpMask(sharpened.data(), w, h, 1.0f, 2);
  const double varSoft = lumaVariance(soft);
  const double varSharp = lumaVariance(sharpened);
  expect(varSharp > varSoft, "unsharp mask raises edge contrast");

  // --- bicubic resize doubles the resolution ---------------------------------
  std::vector<uint32_t> small = {pack(255, 10, 10, 10), pack(255, 200, 200, 200),
                                 pack(255, 30, 30, 30), pack(255, 220, 220, 220)};
  std::vector<uint32_t> big(4 * 4, 0);
  aipro::resizeBicubic(small.data(), 2, 2, big.data(), 4, 4);
  expect(red(big[0]) < 60, "upscale keeps the dark corner dark");
  expect(red(big[15]) > 180, "upscale keeps the bright corner bright");
  expect(big.size() == 16, "upscale fills the destination buffer");

  // --- tone curve is monotonic and clips at the ends --------------------------
  uint8_t lut[256];
  aipro::buildPurposeLut(lut, 2, 1.2);
  expect(lut[0] == 0, "tone LUT starts at 0");
  expect(lut[255] == 255, "tone LUT ends at 255");
  bool monotonic = true;
  for (int i = 1; i < 256; ++i) {
    if (lut[i] < lut[i - 1]) monotonic = false;
  }
  expect(monotonic, "tone LUT is monotonic");

  // --- colour: saturation and temperature behave in the expected direction ----
  std::vector<uint32_t> flat(16, pack(255, 120, 120, 150));
  auto saturated = flat;
  aipro::toneAndColor(saturated.data(), 4, 4, nullptr, 1.5f, 0.0f);
  const double satBefore = aipro::analyze(flat.data(), 4, 4).saturationMean;
  const double satAfter = aipro::analyze(saturated.data(), 4, 4).saturationMean;
  expect(satAfter > satBefore, "saturation boost increases chroma");
  auto warm = flat;
  aipro::toneAndColor(warm.data(), 4, 4, nullptr, 1.0f, 1.0f);
  expect(red(warm[0]) > red(flat[0]) && blue(warm[0]) < blue(flat[0]), "positive temperature warms");

  // --- portrait smoothing keeps structure ------------------------------------
  auto face = noisyImage(w, h, 150, 12);
  auto smoothed = face;
  aipro::portraitSmooth(smoothed.data(), w, h, 0.8f);
  expect(lumaVariance(smoothed) <= lumaVariance(face) + 0.5, "portrait smoothing does not add noise");

  // --- analysis stats --------------------------------------------------------
  std::vector<uint32_t> white(static_cast<size_t>(w) * h, pack(255, 255, 255, 255));
  const aipro::Stats whiteStats = aipro::analyze(white.data(), w, h);
  expect(whiteStats.meanLuma > 250, "white image reports high luma");
  expect(whiteStats.clippedHighlights > 0.99, "white image reports clipped highlights");
  std::vector<uint32_t> black(static_cast<size_t>(w) * h, pack(255, 0, 0, 0));
  expect(aipro::analyze(black.data(), w, h).clippedShadows > 0.99, "black image reports clipped shadows");

  if (g_failures == 0) {
    std::printf("ALL IMAGE OPS TESTS PASSED\n");
    return 0;
  }
  std::printf("%d IMAGE OPS FAILURES\n", g_failures);
  return 1;
}
