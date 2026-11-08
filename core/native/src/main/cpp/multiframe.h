// Multi-frame alignment and merging (stage 4): night-mode stacking + HDR exposure fusion.
// Works on 8-bit luma planes so a 12 MP burst never has to be expanded to ARGB in memory.
// Pure C++ (host-tested in src/testHost).
#ifndef AIPRO_MULTIFRAME_H
#define AIPRO_MULTIFRAME_H

#include <cstdint>
#include <vector>

namespace aipro {

struct Shift {
  int dx = 0;
  int dy = 0;
  float confidence = 0.0f;  // 0..1, share of blocks that agreed
};

enum class MergeMode {
  kNightStack = 0,  // average with motion weights: static detail improves, moving subjects do not ghost
  kExposureFusion = 1,  // HDR: weight each frame by how well exposed it is locally
};

struct MergeStats {
  int framesUsed = 0;
  float meanShiftPx = 0.0f;
  float motionRatio = 0.0f;  // fraction of pixels where motion weights suppressed other frames
  float noiseBefore = 0.0f;  // mean absolute neighbour difference (input frame 0)
  float noiseAfter = 0.0f;   // same measure on the merged result
};

// Coarse translation between two luma planes, estimated on a downsampled grid.
Shift estimateShift(const uint8_t* ref, const uint8_t* alt, int width, int height, int stride,
                    int searchRadius = 12);

// Aligns every frame onto frame 0 and merges them into out (same geometry as the inputs).
MergeStats mergeFrames(const std::vector<const uint8_t*>& frames, int width, int height, int stride,
                       uint8_t* out, MergeMode mode, float motionThreshold = 18.0f,
                       int maxFrames = 9);

// YUV_420_888 (planar) -> NV21 (needed by Android's YuvImage JPEG encoder).
void yuv420ToNv21(const uint8_t* y, const uint8_t* u, const uint8_t* v, int width, int height,
                  int yStride, int uStride, int vStride, int uPixelStride, int vPixelStride,
                  uint8_t* nv21);

}  // namespace aipro

#endif  // AIPRO_MULTIFRAME_H
