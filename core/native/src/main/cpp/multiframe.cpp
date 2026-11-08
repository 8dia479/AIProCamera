#include "multiframe.h"

#include <algorithm>
#include <cmath>

namespace aipro {
namespace {

inline int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Moves a coordinate by (dx,dy) while keeping the frame inside its own bounds.
// 3x3 mean, used for motion detection: averaging first keeps sensor noise from looking like motion.
inline double localMean(const uint8_t* plane, int width, int height, int stride, int x, int y) {
  double sum = 0.0;
  for (int dy = -1; dy <= 1; ++dy) {
    const int cy = clampInt(y + dy, 0, height - 1);
    const uint8_t* row = plane + static_cast<int64_t>(cy) * stride;
    for (int dx = -1; dx <= 1; ++dx) {
      sum += row[clampInt(x + dx, 0, width - 1)];
    }
  }
  return sum / 9.0;
}

inline int sampleAt(const uint8_t* plane, int width, int height, int stride, int x, int y) {
  const int cx = clampInt(x, 0, width - 1);
  const int cy = clampInt(y, 0, height - 1);
  return plane[static_cast<int64_t>(cy) * stride + cx];
}

double neighbourNoise(const uint8_t* plane, int width, int height, int stride) {
  double sum = 0.0;
  int count = 0;
  for (int y = 1; y < height - 1; y += 2) {
    for (int x = 1; x < width - 1; x += 2) {
      const int c = plane[static_cast<int64_t>(y) * stride + x];
      const int r = plane[static_cast<int64_t>(y) * stride + x + 1];
      const int d = plane[static_cast<int64_t>(y + 1) * stride + x];
      sum += std::abs(c - r) + std::abs(c - d);
      count += 2;
    }
  }
  return count == 0 ? 0.0 : sum / count;
}

}  // namespace

Shift estimateShift(const uint8_t* ref, const uint8_t* alt, int width, int height, int stride,
                    int searchRadius) {
  Shift shift;
  if (ref == nullptr || alt == nullptr || width <= 0 || height <= 0) return shift;

  const int block = 32;
  const int step = 4;
  const int gridX = std::max(1, width / 128);
  const int gridY = std::max(1, height / 128);
  const int cellW = width / gridX;
  const int cellH = height / gridY;

  std::vector<int> dxVotes;
  std::vector<int> dyVotes;
  for (int gy = 0; gy < gridY; ++gy) {
    for (int gx = 0; gx < gridX; ++gx) {
      const int bx = gx * cellW + (cellW - block) / 2;
      const int by = gy * cellH + (cellH - block) / 2;
      double best = 1e9;
      int bestDx = 0, bestDy = 0;
      // Search from the centre outwards so that equal-cost candidates keep the smallest motion.
      for (int radius = 0; radius <= searchRadius; radius += 2) {
        for (int dy = -radius; dy <= radius; dy += 2) {
          for (int dx = -radius; dx <= radius; dx += 2) {
            if (radius > 0 && std::abs(dx) != radius && std::abs(dy) != radius) continue;
          // Compare the reference block with the shifted alternative block.
          double sum = 0.0;
          int count = 0;
          for (int y = by; y < by + block; y += step) {
            for (int x = bx; x < bx + block; x += step) {
              const int ax = x + dx, ay = y + dy;
              if (ax < 0 || ax >= width || ay < 0 || ay >= height) continue;
              const int a = ref[static_cast<int64_t>(y) * stride + x];
              const int b = alt[static_cast<int64_t>(ay) * stride + ax];
              sum += std::abs(a - b);
              ++count;
            }
          }
          if (count == 0) continue;
          // Small-displacement preference: without it, sensor noise alone can win the search.
          const double cost = sum / count +
              0.08 * std::sqrt(static_cast<double>(dx * dx + dy * dy));
          if (cost < best - 1e-6) {
            best = cost;
            bestDx = dx;
            bestDy = dy;
          }
        }
      }
      }
      dxVotes.push_back(bestDx);
      dyVotes.push_back(bestDy);
    }
  }
  if (dxVotes.empty()) return shift;

  std::sort(dxVotes.begin(), dxVotes.end());
  std::sort(dyVotes.begin(), dyVotes.end());
  shift.dx = dxVotes[dxVotes.size() / 2];
  shift.dy = dyVotes[dyVotes.size() / 2];

  int agreeing = 0;
  for (size_t i = 0; i < dxVotes.size(); ++i) {
    if (std::abs(dxVotes[i] - shift.dx) <= 1 && std::abs(dyVotes[i] - shift.dy) <= 1) ++agreeing;
  }
  shift.confidence = static_cast<float>(agreeing) / static_cast<float>(dxVotes.size());
  return shift;
}

MergeStats mergeFrames(const std::vector<const uint8_t*>& frames, int width, int height, int stride,
                       uint8_t* out, MergeMode mode, float motionThreshold, int maxFrames) {
  MergeStats stats;
  if (frames.empty() || out == nullptr || width <= 0 || height <= 0) return stats;

  const int count = std::min<int>(static_cast<int>(frames.size()), std::max(1, maxFrames));
  std::vector<Shift> shifts(count);
  for (int i = 1; i < count; ++i) {
    shifts[i] = estimateShift(frames[0], frames[i], width, height, stride);
    stats.meanShiftPx += std::sqrt(static_cast<float>(shifts[i].dx * shifts[i].dx + shifts[i].dy * shifts[i].dy));
  }
  if (count > 1) stats.meanShiftPx /= static_cast<float>(count - 1);
  stats.framesUsed = count;
  stats.noiseBefore = neighbourNoise(frames[0], width, height, stride);

  const double motionScale = 1.0 / (2.0 * motionThreshold * motionThreshold);
  int64_t motionPixels = 0;

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const double referenceMean = localMean(frames[0], width, height, stride, x, y);
      double weighted = 0.0;
      double totalWeight = 0.0;
      for (int i = 0; i < count; ++i) {
        const int value = sampleAt(frames[i], width, height, stride, x + shifts[i].dx, y + shifts[i].dy);
        double weight;
        if (mode == MergeMode::kExposureFusion) {
          // Well-exposedness: prefer frames whose local value is near middle grey, never clip.
          const double v = value / 255.0;
          weight = std::exp(-((v - 0.5) * (v - 0.5)) / (2.0 * 0.16 * 0.16));
          // A blown-out pixel contributes almost nothing, so highlights are preserved.
          if (value >= 250) weight *= 0.05;
          if (value <= 4) weight *= 0.4;
        } else {
          // Compare 3x3 means: sensor noise is averaged out, real subject motion is not.
          const double sampleMean =
              localMean(frames[i], width, height, stride, x + shifts[i].dx, y + shifts[i].dy);
          const double diff = sampleMean - referenceMean;
          weight = std::exp(-diff * diff * motionScale);
        }
        if (i > 0 && weight < 0.25) ++motionPixels;
        weighted += value * weight;
        totalWeight += weight;
      }
      const int merged = static_cast<int>(weighted / std::max(totalWeight, 1e-6) + 0.5);
      out[static_cast<int64_t>(y) * stride + x] = static_cast<uint8_t>(clampInt(merged, 0, 255));
    }
  }
  const double pixelCount = static_cast<double>(width) * height * std::max(1, count - 1);
  stats.motionRatio = pixelCount > 0 ? static_cast<float>(motionPixels / pixelCount) : 0.0f;
  stats.noiseAfter = neighbourNoise(out, width, height, stride);
  return stats;
}

void yuv420ToNv21(const uint8_t* y, const uint8_t* u, const uint8_t* v, int width, int height,
                  int yStride, int uStride, int vStride, int uPixelStride, int vPixelStride,
                  uint8_t* nv21) {
  if (y == nullptr || u == nullptr || v == nullptr || nv21 == nullptr) return;
  for (int row = 0; row < height; ++row) {
    const uint8_t* src = y + static_cast<int64_t>(row) * yStride;
    uint8_t* dst = nv21 + static_cast<int64_t>(row) * width;
    for (int col = 0; col < width; ++col) dst[col] = src[col];
  }
  uint8_t* vu = nv21 + static_cast<int64_t>(width) * height;
  const int chromaRows = height / 2;
  const int chromaCols = width / 2;
  for (int row = 0; row < chromaRows; ++row) {
    const uint8_t* vRow = v + static_cast<int64_t>(row) * vStride;
    const uint8_t* uRow = u + static_cast<int64_t>(row) * uStride;
    for (int col = 0; col < chromaCols; ++col) {
      vu[row * width + col * 2] = vRow[col * std::max(1, vPixelStride)];
      vu[row * width + col * 2 + 1] = uRow[col * std::max(1, uPixelStride)];
    }
  }
}

}  // namespace aipro
