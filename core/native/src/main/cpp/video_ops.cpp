#include "video_ops.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace aipro {
namespace {
inline uint8_t clamp8(int v) { return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v)); }
}  // namespace

void applyToneLutY(uint8_t* y, int width, int height, const uint8_t* lut) {
  if (y == nullptr || lut == nullptr || width <= 0 || height <= 0) return;
  const int count = width * height;
  for (int i = 0; i < count; ++i) y[i] = lut[y[i]];
}

void sharpenY(uint8_t* y, int width, int height, float amount) {
  if (y == nullptr || width <= 0 || height <= 0 || amount <= 0.0f) return;
  if (width < 3 || height < 3) return;
  std::vector<uint8_t> src(y, y + width * height);
  const float a = std::min(amount, 2.0f);
  for (int row = 1; row < height - 1; ++row) {
    for (int col = 1; col < width - 1; ++col) {
      const int i = row * width + col;
      const int blur = src[i - width - 1] + src[i - width] + src[i - width + 1] + src[i - 1] +
                       2 * src[i] + src[i + 1] + src[i + width - 1] + src[i + width] +
                       src[i + width + 1];
      const float neighbourhood = static_cast<float>(blur) / 10.0f;  // 9 taps + centre weight 2
      const float detail = static_cast<float>(src[i]) - neighbourhood;
      y[i] = clamp8(static_cast<int>(std::lround(src[i] + a * detail)));
    }
  }
}

void denoiseY(uint8_t* y, int width, int height, float sigmaRange) {
  if (y == nullptr || width <= 0 || height <= 0) return;
  if (width < 3 || height < 3) return;
  std::vector<uint8_t> src(y, y + width * height);
  const float sigma = sigmaRange <= 0.0f ? 12.0f : sigmaRange;
  const float inv = 1.0f / (2.0f * sigma * sigma);
  for (int row = 1; row < height - 1; ++row) {
    for (int col = 1; col < width - 1; ++col) {
      const int i = row * width + col;
      const int centre = src[i];
      float sum = 0.0f;
      float weights = 0.0f;
      for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
          const int v = src[i + dy * width + dx];
          const float diff = static_cast<float>(v - centre);
          const float w = std::exp(-diff * diff * inv);
          sum += v * w;
          weights += w;
        }
      }
      y[i] = weights > 0.0f ? clamp8(static_cast<int>(std::lround(sum / weights))) : clamp8(centre);
    }
  }
}

void mirrorY(uint8_t* y, int width, int height) {
  if (y == nullptr || width <= 1 || height <= 0) return;
  for (int row = 0; row < height; ++row) {
    uint8_t* line = y + row * width;
    std::reverse(line, line + width);
  }
}

void stretchY(uint8_t* y, int width, int height, int low, int high) {
  if (y == nullptr || width <= 0 || height <= 0) return;
  if (high <= low) return;
  const float scale = 255.0f / static_cast<float>(high - low);
  const int count = width * height;
  for (int i = 0; i < count; ++i) {
    y[i] = clamp8(static_cast<int>(std::lround((static_cast<int>(y[i]) - low) * scale)));
  }
}

}  // namespace aipro
