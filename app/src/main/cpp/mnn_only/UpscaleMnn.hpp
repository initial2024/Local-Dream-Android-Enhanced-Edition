#pragma once
// ARM64 offline 4x neural SR; MNN 4x NCHW RGB graphs only.
// Input is raw RGB; output is lossless PNG. Never uses remote inference.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <MNN/Interpreter.hpp>
#include "MnnUtils.hpp"
#include "SDUtils.hpp"
#include "httplib.h"
#include "json.hpp"

namespace localdream_sr {
constexpr int kScale = 4, kTile = 192, kOverlap = 24;
constexpr int kOutTile = kTile * kScale;
constexpr int kMaxSide = 640;
inline std::vector<int> tileStarts(int n) {
  if (n <= kTile) return {0};
  std::vector<int> x;
  for (int p = 0; p < n - kTile; p += kTile - kOverlap) x.push_back(p);
  if (x.empty() || x.back() != n - kTile) x.push_back(n - kTile);
  return x;
}
inline int dimension(const httplib::Request &req, const char *header) {
  const std::string s = req.get_header_value(header);
  if (s.empty() || s.size() > 5 ||
      !std::all_of(s.begin(), s.end(), [](unsigned char c) {
        return c >= '0' && c <= '9';
      })) throw std::invalid_argument(std::string("Invalid ") + header);
  return std::stoi(s);
}
inline std::vector<uint8_t> upscale(
    const std::vector<uint8_t> &rgb, int w, int h,
    const std::string &path, bool gpu) {
  std::unique_ptr<MNN::Interpreter> engine(
      createMnnInterpreterMmap(path.c_str()));
  if (!engine) throw std::runtime_error("Invalid MNN super-resolution graph");
  MnnSessionOptions options;
  options.use_opencl = gpu;
  options.num_threads = 4;
  if (gpu) {
    const auto cache = ensureCacheDir(
        std::filesystem::path(path).parent_path().string());
    if (!cache.empty()) options.cache_file = cache + "/upscale.mnnc";
  }
  auto *session = createMnnSession(engine.get(), options);
  if (!session) throw std::runtime_error("Could not create MNN SR session");
  auto *input = engine->getSessionInput(session, nullptr);
  if (!input) throw std::runtime_error("MNN SR input tensor missing");
  const std::vector<int> inShape = {1, 3, kTile, kTile};
  const std::vector<int> outShape = {1, 3, kOutTile, kOutTile};
  engine->resizeTensor(input, inShape);
  engine->resizeSession(session);
  auto *output = engine->getSessionOutput(session, nullptr);
  if (!output || output->shape() != outShape)
    throw std::runtime_error("MNN model must output 4x RGB NCHW");
  std::vector<float> tile(3ull * kTile * kTile);
  std::unique_ptr<MNN::Tensor> inputHost(
      MNN::Tensor::create<float>(inShape, tile.data(), MNN::Tensor::CAFFE));
  std::unique_ptr<MNN::Tensor> outputHost(
      MNN::Tensor::create<float>(outShape, nullptr, MNN::Tensor::CAFFE));
  if (!inputHost || !outputHost)
    throw std::runtime_error("Could not allocate MNN SR tensors");

  const int outW = w * kScale, outH = h * kScale;
  const size_t pixels = static_cast<size_t>(outW) * outH;
  std::vector<uint8_t> result(pixels * 3, 0);
  std::vector<float> weights(pixels, 0.f);
  const auto xs = tileStarts(w), ys = tileStarts(h);
  int done = 0;
  for (int y : ys) for (int x : xs) {
    // Pad with replicated edge pixels for inputs smaller than one model tile.
    for (int c = 0; c < 3; ++c)
      for (int ty = 0; ty < kTile; ++ty)
        for (int tx = 0; tx < kTile; ++tx) {
          const int iy = std::min(y + ty, h - 1);
          const int ix = std::min(x + tx, w - 1);
          tile[(static_cast<size_t>(c) * kTile + ty) * kTile + tx] =
              rgb[(static_cast<size_t>(iy) * w + ix) * 3 + c] / 255.f;
        }
    input->copyFromHostTensor(inputHost.get());
    if (engine->runSession(session) != 0)
      throw std::runtime_error("MNN tile inference failed");
    output->copyToHostTensor(outputHost.get());
    const float *neural = outputHost->host<float>();
    if (!neural) throw std::runtime_error("MNN output tensor missing data");
    for (int ty = 0; ty < kOutTile; ++ty) {
      const int iy = y * kScale + ty;
      if (iy >= outH) continue;
      float fy = 1.f;
      if (y > 0 && ty < kOverlap * kScale)
        fy = std::min(fy, (ty + 1.f) / (kOverlap * kScale));
      if (y + kTile < h && ty >= kOutTile - kOverlap * kScale)
        fy = std::min(fy, (kOutTile - ty) / float(kOverlap * kScale));
      for (int tx = 0; tx < kOutTile; ++tx) {
        const int ix = x * kScale + tx;
        if (ix >= outW) continue;
        float fx = 1.f;
        if (x > 0 && tx < kOverlap * kScale)
          fx = std::min(fx, (tx + 1.f) / (kOverlap * kScale));
        if (x + kTile < w && tx >= kOutTile - kOverlap * kScale)
          fx = std::min(fx, (kOutTile - tx) / float(kOverlap * kScale));
        const float newWeight = fx * fy;
        const size_t dst = static_cast<size_t>(iy) * outW + ix;
        const float oldWeight = weights[dst];
        const float totalWeight = oldWeight + newWeight;
        const size_t src = static_cast<size_t>(ty) * kOutTile + tx;
        for (int c = 0; c < 3; ++c) {
          const float raw = neural[
              static_cast<size_t>(c) * kOutTile * kOutTile + src];
          const float value = std::clamp(raw, 0.f, 1.f) * 255.f;
          const size_t outIdx = dst * 3 + c;
          const float blended =
              (result[outIdx] * oldWeight + value * newWeight) / totalWeight;
          result[outIdx] = static_cast<uint8_t>(
              std::clamp(std::lround(blended), 0l, 255l));
        }
        weights[dst] = totalWeight;
      }
    }
    QNN_INFO("Processed tile %d/%d", ++done, int(xs.size() * ys.size()));
  }
  return result;
}
inline void registerRoute(httplib::Server &server, std::mutex &gate) {
  server.Post("/upscale", [&gate](const httplib::Request &req,
                                 httplib::Response &res) {
    try {
      const int w = dimension(req, "X-Image-Width");
      const int h = dimension(req, "X-Image-Height");
      if (w < 1 || h < 1 || w > kMaxSide || h > kMaxSide ||
          int64_t(w) * h > 640LL * 640)
        throw std::invalid_argument("Local AI upscaler input must be <= 640x640");
      if (req.body.size() != static_cast<size_t>(w) * h * 3)
        throw std::invalid_argument("Invalid RGB image byte count");
      const std::string filename = req.get_header_value("X-Upscaler-Path");
      const std::filesystem::path model(filename);
      if (filename.empty() || filename.size() > 2048 ||
          std::filesystem::is_symlink(model) ||
          !std::filesystem::is_regular_file(model) ||
          (model.extension() != ".bin" && model.extension() != ".mnn") ||
          std::filesystem::file_size(model) < 4096 ||
          std::filesystem::file_size(model) > 128ull * 1024 * 1024)
        throw std::invalid_argument("Valid installed MNN SR model required");
      const auto started = std::chrono::steady_clock::now();
      const std::vector<uint8_t> rgb(req.body.begin(), req.body.end());
      std::vector<uint8_t> pixels;
      {
        std::lock_guard<std::mutex> lock(gate);
        try {
          pixels = upscale(rgb, w, h, filename, true);
        } catch (const std::exception &e) {
          QNN_WARN("OpenCL SR unavailable, retry CPU: %s", e.what());
          pixels = upscale(rgb, w, h, filename, false);
        }
      }
      const auto png = encodePNG(pixels, w * kScale, h * kScale, 3);
      if (png.empty()) throw std::runtime_error("SR output PNG failed");
      res.set_header("Cache-Control", "no-store");
      res.set_header("X-Output-Width", std::to_string(w * kScale));
      res.set_header("X-Output-Height", std::to_string(h * kScale));
      res.set_header("X-Duration-Ms", std::to_string(
          std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::steady_clock::now() - started).count()));
      res.set_content(
          std::string(reinterpret_cast<const char*>(png.data()), png.size()),
          "image/png");
    } catch (const std::invalid_argument &e) {
      res.status = 400;
      res.set_content(nlohmann::json({
          {"error", {{"type", "request"}, {"message", e.what()}}}
      }).dump(), "application/json");
    } catch (const std::exception &e) {
      res.status = 422;
      res.set_content(nlohmann::json({
          {"error", {{"type", "inference_failed"}, {"message", e.what()}}}
      }).dump(), "application/json");
    }
  });
}
}  // namespace localdream_sr
