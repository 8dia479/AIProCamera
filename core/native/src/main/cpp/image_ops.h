// Image processing primitives used by the enhancement pipeline.
// Pixels are packed 0xAARRGGBB in a uint32 buffer, rows top-to-bottom.
// Pure C++ (no JNI, no Android) so every routine is covered by the host test harness.
#ifndef AIPRO_IMAGE_OPS_H
#define AIPRO_IMAGE_OPS_H

#include <cstdint>
#include <vector>

namespace aipro {

// Edge-preserving (bilateral) denoiser. radius in pixels (1..4), sigmaRange in 0..255 luma units,
// sigmaSpatial in pixels. Detail is preserved instead of being blurred away.
void bilateralDenoise(uint32_t* pixels, int width, int height, int radius, float sigmaRange,
                      float sigmaSpatial);

// Unsharp mask: amount 0..2 (0 = no change), radius in pixels.
void unsharpMask(uint32_t* pixels, int width, int height, float amount, int radius);

// Bicubic (Catmull-Rom) resampling - used as the fallback upscaler when no AI model is installed.
void resizeBicubic(const uint32_t* src, int srcWidth, int srcHeight, uint32_t* dst, int dstWidth,
                   int dstHeight);

// Tone curve (256-entry LUT) + saturation + simple warm/cool shift (temperature in -1..1).
void toneAndColor(uint32_t* pixels, int width, int height, const uint8_t* lut, float saturation,
                  float temperature);

// Portrait helper: edge-aware smoothing that keeps eyes/hair edges, strength 0..1.
void portraitSmooth(uint32_t* pixels, int width, int height, float strength);

// Analysis helpers -----------------------------------------------------------
struct Stats {
  double meanLuma = 0.0;
  double contrast = 0.0;      // standard deviation of luma
  double clippedHighlights = 0.0;  // fraction of pixels >= 250
  double clippedShadows = 0.0;     // fraction of pixels <= 5
  double saturationMean = 0.0;
};

Stats analyze(const uint32_t* pixels, int width, int height);

// Builds an S-curve/contrast LUT for a shooting purpose. purpose: 0=auto 1=portrait 2=landscape 3=urban
void buildPurposeLut(uint8_t* lut, int purpose, double contrast);

}  // namespace aipro

#endif  // AIPRO_IMAGE_OPS_H
