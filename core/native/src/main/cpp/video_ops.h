// Luma-plane primitives for the video post-processing chain (stage 5).
// Video frames arrive as YUV420 (planar or semi-planar); only the Y plane is touched here because
// cheap tone/sharpening work on luma is what the post job needs and chroma is copied untouched.
// Pure C++ (no JNI, no Android) so the host test harness covers every routine.
#ifndef AIPRO_VIDEO_OPS_H
#define AIPRO_VIDEO_OPS_H

#include <cstdint>

namespace aipro {

// Maps every luma sample through a 256-entry LUT (tone curve built for the shooting purpose).
void applyToneLutY(uint8_t* y, int width, int height, const uint8_t* lut);

// 3x3 unsharp mask on luma. amount 0..2, 0 = no change. Values are clamped to 0..255.
void sharpenY(uint8_t* y, int width, int height, float amount);

// 3x3 edge-preserving denoise on luma. radius must be 1 (kept small for real-time safety),
// sigmaRange in luma units (0 disables the range test and becomes a plain blur).
void denoiseY(uint8_t* y, int width, int height, float sigmaRange);

// Horizontal flip of the luma plane (front-camera mirroring in the encoded output).
void mirrorY(uint8_t* y, int width, int height);

// Clamps a luma plane into [low, high] histogram window (simple contrast stretch, video-safe).
void stretchY(uint8_t* y, int width, int height, int low, int high);

}  // namespace aipro

#endif  // AIPRO_VIDEO_OPS_H
