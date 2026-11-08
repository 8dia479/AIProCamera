#include "image_ops.h"

#include <algorithm>
#include <cmath>

namespace aipro {
namespace {

inline int clamp255(double v) { return v < 0.0 ? 0 : (v > 255.0 ? 255 : static_cast<int>(v + 0.5)); }
inline int alpha(uint32_t p) { return static_cast<int>((p >> 24) & 0xFFu); }
inline int red(uint32_t p) { return static_cast<int>((p >> 16) & 0xFFu); }
inline int green(uint32_t p) { return static_cast<int>((p >> 8) & 0xFFu); }
inline int blue(uint32_t p) { return static_cast<int>(p & 0xFFu); }
inline uint32_t pack(int a, int r, int g, int b) {
  return (static_cast<uint32_t>(a) << 24) | (static_cast<uint32_t>(r) << 16) |
         (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
}
inline double lumaOf(int r, int g, int b) {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
inline uint32_t at(const uint32_t* p, int w, int h, int x, int y) {
  const int cx = std::clamp(x, 0, w - 1);
  const int cy = std::clamp(y, 0, h - 1);
  return p[static_cast<int64_t>(cy) * w + cx];
}

double cubicWeight(double t) {
  // Catmull-Rom kernel.
  const double a = -0.5;
  t = std::fabs(t);
  if (t <= 1.0) return (a + 2) * t * t * t - (a + 3) * t * t + 1;
  if (t < 2.0) return a * t * t * t - 5 * a * t * t + 8 * a * t - 4 * a;
  return 0.0;
}

}  // namespace

void bilateralDenoise(uint32_t* pixels, int width, int height, int radius, float sigmaRange,
                      float sigmaSpatial) {
  if (pixels == nullptr || width <= 0 || height <= 0) return;
  radius = std::clamp(radius, 1, 4);
  if (sigmaSpatial <= 0.0f) sigmaSpatial = radius * 0.6f;
  const double invRange = 1.0 / (2.0 * static_cast<double>(sigmaRange) * sigmaRange);
  const double invSpace = 1.0 / (2.0 * static_cast<double>(sigmaSpatial) * sigmaSpatial);

  std::vector<float> spatial(2 * radius + 1);
  for (int d = 0; d <= radius; ++d) {
    spatial[d] = static_cast<float>(std::exp(-(d * d) * invSpace));
  }

  std::vector<uint32_t> copy(pixels, pixels + static_cast<int64_t>(width) * height);
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const uint32_t center = copy[static_cast<int64_t>(y) * width + x];
      const int cr = red(center), cg = green(center), cb = blue(center);
      const double centerLuma = lumaOf(cr, cg, cb);
      double sumR = 0, sumG = 0, sumB = 0, sumW = 0;
      for (int dy = -radius; dy <= radius; ++dy) {
        for (int dx = -radius; dx <= radius; ++dx) {
          const uint32_t sample = at(copy.data(), width, height, x + dx, y + dy);
          const int sr = red(sample), sg = green(sample), sb = blue(sample);
          const double lumaDiff = lumaOf(sr, sg, sb) - centerLuma;
          const double rangeWeight = std::exp(-(lumaDiff * lumaDiff) * invRange);
          const double spaceWeight = spatial[std::abs(dx)] * spatial[std::abs(dy)];
          const double weight = rangeWeight * spaceWeight + 1e-9;
          sumR += sr * weight;
          sumG += sg * weight;
          sumB += sb * weight;
          sumW += weight;
        }
      }
      pixels[static_cast<int64_t>(y) * width + x] =
          pack(alpha(center), clamp255(sumR / sumW), clamp255(sumG / sumW), clamp255(sumB / sumW));
    }
  }
}

void unsharpMask(uint32_t* pixels, int width, int height, float amount, int radius) {
  if (pixels == nullptr || width <= 0 || height <= 0 || amount <= 0.0f) return;
  radius = std::clamp(radius, 1, 8);
  // Separable box blur approximates a Gaussian well enough for detail recovery.
  std::vector<uint32_t> blurred(pixels, pixels + static_cast<int64_t>(width) * height);
  std::vector<uint32_t> temp(blurred.size());
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      int sr = 0, sg = 0, sb = 0, n = 0;
      for (int dx = -radius; dx <= radius; ++dx) {
        const uint32_t s = at(blurred.data(), width, height, x + dx, y);
        sr += red(s); sg += green(s); sb += blue(s); ++n;
      }
      temp[static_cast<int64_t>(y) * width + x] =
          pack(alpha(at(blurred.data(), width, height, x, y)), sr / n, sg / n, sb / n);
    }
  }
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      int sr = 0, sg = 0, sb = 0, n = 0;
      for (int dy = -radius; dy <= radius; ++dy) {
        const uint32_t s = at(temp.data(), width, height, x, y + dy);
        sr += red(s); sg += green(s); sb += blue(s); ++n;
      }
      blurred[static_cast<int64_t>(y) * width + x] = pack(0, sr / n, sg / n, sb / n);
    }
  }
  for (int64_t i = 0; i < static_cast<int64_t>(width) * height; ++i) {
    const uint32_t original = pixels[i];
    const uint32_t low = blurred[i];
    const int r = clamp255(red(original) + amount * (red(original) - red(low)));
    const int g = clamp255(green(original) + amount * (green(original) - green(low)));
    const int b = clamp255(blue(original) + amount * (blue(original) - blue(low)));
    pixels[i] = pack(alpha(original), r, g, b);
  }
}

void resizeBicubic(const uint32_t* src, int srcWidth, int srcHeight, uint32_t* dst, int dstWidth,
                   int dstHeight) {
  if (src == nullptr || dst == nullptr || srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 ||
      dstHeight <= 0) {
    return;
  }
  const double scaleX = static_cast<double>(srcWidth) / dstWidth;
  const double scaleY = static_cast<double>(srcHeight) / dstHeight;
  for (int y = 0; y < dstHeight; ++y) {
    const double sy = (y + 0.5) * scaleY - 0.5;
    const int y0 = static_cast<int>(std::floor(sy));
    for (int x = 0; x < dstWidth; ++x) {
      const double sx = (x + 0.5) * scaleX - 0.5;
      const int x0 = static_cast<int>(std::floor(sx));
      double r = 0, g = 0, b = 0, a = 0, weightSum = 0;
      for (int dy = -1; dy <= 2; ++dy) {
        const double wy = cubicWeight(sy - (y0 + dy));
        if (wy == 0.0) continue;
        for (int dx = -1; dx <= 2; ++dx) {
          const double wx = cubicWeight(sx - (x0 + dx));
          if (wx == 0.0) continue;
          const double w = wx * wy;
          const uint32_t s = at(src, srcWidth, srcHeight, x0 + dx, y0 + dy);
          r += red(s) * w;
          g += green(s) * w;
          b += blue(s) * w;
          a += alpha(s) * w;
          weightSum += w;
        }
      }
      if (weightSum == 0.0) weightSum = 1.0;
      dst[static_cast<int64_t>(y) * dstWidth + x] =
          pack(clamp255(a / weightSum), clamp255(r / weightSum), clamp255(g / weightSum),
               clamp255(b / weightSum));
    }
  }
}

void toneAndColor(uint32_t* pixels, int width, int height, const uint8_t* lut, float saturation,
                  float temperature) {
  if (pixels == nullptr || width <= 0 || height <= 0) return;
  const int64_t count = static_cast<int64_t>(width) * height;
  const double tempR = 1.0 + 0.12 * temperature;
  const double tempB = 1.0 - 0.12 * temperature;
  for (int64_t i = 0; i < count; ++i) {
    const uint32_t p = pixels[i];
    int r = red(p), g = green(p), b = blue(p);
    if (lut != nullptr) {
      r = lut[r];
      g = lut[g];
      b = lut[b];
    }
    if (temperature != 0.0f) {
      r = clamp255(r * tempR);
      b = clamp255(b * tempB);
    }
    if (saturation != 1.0f) {
      const double y = lumaOf(r, g, b);
      r = clamp255(y + (r - y) * saturation);
      g = clamp255(y + (g - y) * saturation);
      b = clamp255(y + (b - y) * saturation);
    }
    pixels[i] = pack(alpha(p), r, g, b);
  }
}

void portraitSmooth(uint32_t* pixels, int width, int height, float strength) {
  if (pixels == nullptr || width <= 0 || height <= 0 || strength <= 0.0f) return;
  strength = std::clamp(strength, 0.0f, 1.0f);
  std::vector<uint32_t> copy(pixels, pixels + static_cast<int64_t>(width) * height);
  const int radius = 2;
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const uint32_t center = copy[static_cast<int64_t>(y) * width + x];
      const double centerLuma = lumaOf(red(center), green(center), blue(center));
      double sumR = 0, sumG = 0, sumB = 0, sumW = 0;
      for (int dy = -radius; dy <= radius; ++dy) {
        for (int dx = -radius; dx <= radius; ++dx) {
          const uint32_t s = at(copy.data(), width, height, x + dx, y + dy);
          const double luma = lumaOf(red(s), green(s), blue(s));
          const double diff = std::fabs(luma - centerLuma);
          // Strong smoothing on flat/skin areas, no smoothing on structural edges.
          const double w = 1.0 / (1.0 + std::pow(diff / 12.0, 2.0));
          sumR += red(s) * w;
          sumG += green(s) * w;
          sumB += blue(s) * w;
          sumW += w;
        }
      }
      const double mix = strength;
      const int r = clamp255(red(center) * (1 - mix) + (sumR / sumW) * mix);
      const int g = clamp255(green(center) * (1 - mix) + (sumG / sumW) * mix);
      const int b = clamp255(blue(center) * (1 - mix) + (sumB / sumW) * mix);
      pixels[static_cast<int64_t>(y) * width + x] = pack(alpha(center), r, g, b);
    }
  }
}

Stats analyze(const uint32_t* pixels, int width, int height) {
  Stats stats;
  if (pixels == nullptr || width <= 0 || height <= 0) return stats;
  const int64_t count = static_cast<int64_t>(width) * height;
  double sum = 0, sumSq = 0, satSum = 0;
  int64_t high = 0, low = 0;
  for (int64_t i = 0; i < count; ++i) {
    const uint32_t p = pixels[i];
    const int r = red(p), g = green(p), b = blue(p);
    const double luma = lumaOf(r, g, b);
    sum += luma;
    sumSq += luma * luma;
    const int mx = std::max(r, std::max(g, b));
    const int mn = std::min(r, std::min(g, b));
    satSum += (mx == 0) ? 0.0 : static_cast<double>(mx - mn) / mx;
    if (luma >= 250) ++high;
    if (luma <= 5) ++low;
  }
  stats.meanLuma = sum / count;
  const double variance = std::max(0.0, sumSq / count - stats.meanLuma * stats.meanLuma);
  stats.contrast = std::sqrt(variance);
  stats.clippedHighlights = static_cast<double>(high) / count;
  stats.clippedShadows = static_cast<double>(low) / count;
  stats.saturationMean = satSum / count;
  return stats;
}

void buildPurposeLut(uint8_t* lut, int purpose, double contrast) {
  if (lut == nullptr) return;
  // contrast ~0.8..1.6; portrait keeps highlights soft, landscape/urban push the S-curve harder.
  double curve = std::clamp(contrast, 0.8, 1.8);
  double lift = 0.0;
  switch (purpose) {
    case 1:  // portrait: slightly lifted shadows, softer contrast
      curve = std::clamp(curve * 0.9, 0.8, 1.5);
      lift = 4.0;
      break;
    case 2:  // landscape: deeper contrast, protected highlights
      curve = std::clamp(curve * 1.15, 0.9, 1.8);
      break;
    case 3:  // urban: crisp midtones
      curve = std::clamp(curve * 1.08, 0.9, 1.7);
      break;
    default:
      break;
  }
  for (int i = 0; i < 256; ++i) {
    double x = i / 255.0;
    // Sigmoid-ish S-curve around the midpoint.
    double y = 0.5 + (x - 0.5) * curve;
    y = std::clamp(y + lift / 255.0, 0.0, 1.0);
    lut[i] = static_cast<uint8_t>(clamp255(y * 255.0));
  }
}

}  // namespace aipro
