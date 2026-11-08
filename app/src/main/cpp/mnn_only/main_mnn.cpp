// Standalone SD 1.5 MNN / OpenCL runtime for MediaTek-class Android devices.
//
// IMPORTANT: this is a separate source target, NOT an ABI-compatible rewrite
// of Qualcomm's QNN runtime. It preserves Local Dream's local HTTP/SSE contract
// and SD1.5 model layout. It intentionally does NOT claim support for
// Qualcomm-specific NPU/DiT or SDXL. The original SD1.5 MNN
// safetensors conversion is supported as a separate bounded offline command.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <cstdint>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "Logger.hpp"
#include "Config.hpp"
#include "MnnUtils.hpp"
#include "Pipeline.hpp"
#include "PipelineSd15Cpu.hpp"
#include "RequestParser.hpp"
#include "SafeTensor2MNN.hpp"
#include "SDUtils.hpp"
#include "TextEncoder.hpp"
#include "httplib.h"
#include "json.hpp"

namespace {

struct Options {
  std::string type;
  std::string model_dir;
  int port = 8081;
  int cpu_threads = 4;  // configurable; OpenCL kernels do not use this
  bool disable_img2img = false;
  bool use_v_pred = false;
  bool convert_mode = false;
  bool convert_clip_skip_2 = false;
};

// A conservative error boundary: unsupported QNN flags fail closed, rather
// than silently starting a CPU server for an incompatible model type.
Options parseArgs(int argc, char **argv) {
  Options opts;
  for (int i = 1; i < argc; ++i) {
    const std::string flag = argv[i];
    auto value = [&]() -> std::string {
      if (++i >= argc) throw std::invalid_argument("Value missing for " + flag);
      return argv[i];
    };
    if (flag == "--type") {
      opts.type = value();
    } else if (flag == "--model_dir") {
      opts.model_dir = value();
    } else if (flag == "--port") {
      opts.port = std::stoi(value());
    } else if (flag == "--cpu_threads") {
      opts.cpu_threads = std::stoi(value());
    } else if (flag == "--convert") {
      opts.convert_mode = true;
      opts.model_dir = value();
    } else if (flag == "--clip_skip_2") {
      opts.convert_clip_skip_2 = true;
    } else if (flag == "--no_img2img") {
      opts.disable_img2img = true;
    } else if (flag == "--use_v_pred") {
      opts.use_v_pred = true;
    } else if (flag == "--help") {
      std::cout << "LocalDream MNN-only SD1.5 runtime\n"
                   "--type sd15cpu --model_dir DIR [--port 8081]"
                   " [--no_img2img] [--use_v_pred] [--cpu_threads 1..8]\n"
                   "--convert DIR [--clip_skip_2] (offline SD1.5)\n";
      std::exit(EXIT_SUCCESS);
    } else if (flag == "--version") {
      std::cout << "LocalDream enhanced MNN-only experimental v1\n";
      std::exit(EXIT_SUCCESS);
    } else if (flag == "--listen_all") {
      // Original remote-host mode has no transport authentication. Do not
      // silently expose a local image generator on arbitrary public Wi-Fi.
      throw std::invalid_argument(
          "LAN binding is disabled until authenticated remote hosting ships");
    } else if (flag == "--upscaler_mode") {
      throw std::invalid_argument(
          "Neural upscaler requires a separately verified MNN model");
    } else {
      throw std::invalid_argument("Unsupported native runtime option: " + flag);
    }
  }
  if (!opts.convert_mode && opts.type != "sd15cpu") {
    throw std::invalid_argument(
        "This MNN-only build supports sd15cpu. QNN, SDXL and DiT require "
        "separately validated backends; requested: " + opts.type);
  }
  if (opts.port < 1024 || opts.port > 65535) {
    throw std::invalid_argument("Invalid local HTTP port");
  }
  if (opts.cpu_threads < 1 || opts.cpu_threads > 8)
    throw std::invalid_argument("--cpu_threads must be in 1..8");
  if (opts.model_dir.empty()) throw std::invalid_argument("Missing --model_dir");
  return opts;
}

// Offline one-shot model conversion, inherited from upstream SafeTensor2MNN.
// A dedicated Android staging folder contains only the user-selected model,
// baseline .mnn graphs and tokenizer, never an installed model directory.
void convertSd15Safetensors(const Options &opts) {
  namespace fs = std::filesystem;
  const fs::path dir = fs::weakly_canonical(opts.model_dir);
  if (!fs::is_directory(dir))
    throw std::invalid_argument("Conversion staging directory missing");
  const auto source = dir / "model.safetensors";
  if (!fs::is_regular_file(source))
    throw std::invalid_argument("Expected model.safetensors in staging directory");
  const auto source_size = fs::file_size(source);
  if (source_size < 100ull * 1024 * 1024 ||
      source_size > 6ull * 1024 * 1024 * 1024)
    throw std::invalid_argument("Unsupported SD1.5 checkpoint size");
  if (fs::exists(dir / "finished"))
    throw std::invalid_argument("Refusing to convert a finished model in place");
  // Reject corrupt or malicious safetensors headers BEFORE SafeTensorReader
  // turns the user-controlled 64-bit length into a large memory allocation.
  std::ifstream source_file(source, std::ios::binary);
  std::uint64_t header_length = 0;
  source_file.read(reinterpret_cast<char*>(&header_length), sizeof(header_length));
  if (!source_file || header_length < 2 ||
      header_length > 16ull * 1024 * 1024 ||
      header_length + sizeof(header_length) >= source_size)
    throw std::invalid_argument("Unreasonable safetensors metadata size");
  source_file.close();

  const std::vector<std::string> source_graphs = {
      "clip_v2.mnn", "unet.mnn", "vae_decoder.mnn",
      "vae_encoder.mnn", "tokenizer.json"};
  for (const auto &part : source_graphs)
    if (!fs::is_regular_file(dir / part))
      throw std::invalid_argument("Missing SD1.5 conversion base: " + part);

  // No QNN runtime is loaded and no network connection is used. LoRAs must
  // be merged into the source model before conversion; do not silently ignore
  // a user-supplied LoRA. The original UI conversion path is limited to one
  // plain SD1.5 checkpoint in this enhanced edition.
  for (auto it = fs::directory_iterator(dir);
       it != fs::directory_iterator(); ++it) {
    const auto file = it->path().filename().string();
    if (file.rfind("lora.", 0) == 0)
      throw std::invalid_argument(
          "LoRA sidecars are not supported; merge LoRA into safetensors first");
  }
  std::cout << "Converting SD1.5 safetensors offline using MNN..." << std::endl;
  generateMNNModels(dir.string(), "model.safetensors",
                    opts.convert_clip_skip_2);
  const std::vector<std::string> outputs = {
      "clip_v2.mnn.weight", "unet.mnn.weight",
      "vae_decoder.mnn.weight", "vae_encoder.mnn.weight",
      "pos_emb.bin", "token_emb.bin", "finished"};
  for (const auto &part : outputs)
    if (!fs::is_regular_file(dir / part) ||
        (part != "finished" && fs::file_size(dir / part) == 0))
      throw std::runtime_error("Incomplete MNN conversion: " + part);
  std::cout << "MNN conversion verified in temporary directory" << std::endl;
}

std::mutex generation_lock;

std::unique_ptr<Pipeline> createPipeline(const Options &opts,
                                         TextEncoder &encoder) {
  const std::filesystem::path dir(opts.model_dir);
  if (!std::filesystem::is_directory(dir)) {
    throw std::invalid_argument("MNN model directory not found");
  }
  const std::string clip = (dir / "clip_v2.mnn").string();
  const std::string unet = (dir / "unet.mnn").string();
  const std::string vae_decoder = (dir / "vae_decoder.mnn").string();
  const std::string vae_encoder =
      opts.disable_img2img ? "" : (dir / "vae_encoder.mnn").string();

  const std::vector<std::string> required = {
      (dir / "tokenizer.json").string(), clip, unet, vae_decoder,
      (dir / "pos_emb.bin").string(), (dir / "token_emb.bin").string()};
  for (const auto &p : required) {
    if (!std::filesystem::is_regular_file(p)) {
      throw std::invalid_argument("Missing model component: " + p);
    }
  }
  if (!vae_encoder.empty() && !std::filesystem::is_regular_file(vae_encoder)) {
    throw std::invalid_argument("VAE encoder missing; use --no_img2img");
  }
  return std::make_unique<PipelineSd15Cpu>(
      encoder, opts.model_dir, clip, unet, vae_decoder, vae_encoder,
      opts.use_v_pred, opts.cpu_threads);
}

std::string encodeImage(const GenerationResult &result,
                        const std::string &format) {
  if (format == "png") {
    const auto bytes = encodePNG(result.image_data, result.width, result.height,
                                 result.channels);
    return base64_encode(std::string(bytes.begin(), bytes.end()));
  }
  if (format == "jpeg") {
    const auto bytes = encodeJPEG(result.image_data, result.width, result.height,
                                  95, result.channels);
    return base64_encode(std::string(bytes.begin(), bytes.end()));
  }
  return base64_encode(std::string(result.image_data.begin(),
                                   result.image_data.end()));
}

// Validate before parseGenerationRequest constructs latent/noise buffers.
bool isAllowedNativeCanvas(int width, int height) {
  constexpr int kMinSide = 128;
  constexpr int kMaxSide = 1024;
  constexpr long long kMaxPixels = 1024LL * 1024;
  return width >= kMinSide && height >= kMinSide &&
         width <= kMaxSide && height <= kMaxSide &&
         width % 64 == 0 && height % 64 == 0 &&
         static_cast<long long>(width) * height <= kMaxPixels;
}

void registerGenerate(httplib::Server &server, Pipeline &pipeline) {
  server.Post("/generate", [&pipeline](const httplib::Request &request,
                                        httplib::Response &res) {
    try {
      // On the phone, the request carries base64 image or mask data in JSON.
      // This bound prevents malformed clients from consuming unbounded RAM.
      constexpr size_t kMaxRequestBytes = 64ull * 1024 * 1024;
      if (request.body.size() > kMaxRequestBytes) {
        res.status = 413;
        res.set_content(R"({"error":{"type":"request_error","message":"Request too large"}})",
                        "application/json");
        return;
      }
      const auto body = nlohmann::json::parse(request.body);
      const int requestedWidth =
          body.contains("size") ? body.at("size").get<int>() :
                                  body.value("width", 512);
      const int requestedHeight =
          body.contains("size") ? requestedWidth : body.value("height", 512);
      if (!isAllowedNativeCanvas(requestedWidth, requestedHeight)) {
        throw std::invalid_argument(
            "SD1.5 MNN native canvas must be 128..1024 in both dimensions, "
            "multiple of 64 and <= 1048576 total pixels");
      }
      auto input = std::make_shared<GenerationRequest>(
          parseGenerationRequest(body, false, false,
                                 pipeline.supportsImg2Img(),
                                 false, pipeline.supportsUltrafix()));
      // Reject malformed work before allocating GPU/CPU tensors. Preserve
      // the upstream UI's 1..50-step boundary and disallow NaN/infinity.
      if (input->steps < 1 || input->steps > 50 ||
          !std::isfinite(input->cfg) || input->cfg < 0.0f ||
          input->cfg > 30.0f || input->prompt.size() > 8192 ||
          input->negative_prompt.size() > 8192) {
        throw std::invalid_argument("Out-of-range generation parameters");
      }
      // Validate the resolved request too. 1024x768 runs at native MNN
      // latent resolution; 1440x1080 export is a separate resize operation.
      if (!isAllowedNativeCanvas(input->width, input->height)) {
        throw std::invalid_argument(
            "Resolved native canvas exceeds the SD1.5 MNN safety bound");
      }
      res.set_header("Cache-Control", "no-store");
      res.set_chunked_content_provider(
          "text/event-stream",
          [&pipeline, input](intptr_t, httplib::DataSink &sink) -> bool {
            try {
              std::lock_guard<std::mutex> guard(generation_lock);
              GenerationResult result = pipeline.generate(
                  *input, [&sink, &input](int done, int total,
                                          const std::string &preview) {
                    nlohmann::json event = {
                        {"type", "progress"}, {"step", done},
                        {"total_steps", total}};
                    if (!preview.empty()) {
                      event["image"] = preview;
                      event["format"] = input->preview_format;
                    }
                    const std::string wire =
                        "event: progress\ndata: " + event.dump() + "\n\n";
                    if (!sink.is_writable() ||
                        !sink.write(wire.c_str(), wire.size())) {
                      throw std::runtime_error("Client disconnected");
                    }
                  });

              const nlohmann::json completed = {
                  {"type", "complete"},
                  {"image", encodeImage(result, input->output_format)},
                  {"format", input->output_format},
                  {"seed", input->seed},
                  {"width", result.width},
                  {"height", result.height},
                  {"channels", result.channels},
                  {"generation_time_ms", result.generation_time_ms},
                  {"first_step_time_ms", result.first_step_time_ms},
              };
              const std::string wire =
                  "event: complete\ndata: " + completed.dump() + "\n\n";
              if (!sink.is_writable() || !sink.write(wire.c_str(), wire.size())) {
                throw std::runtime_error("Client disconnected");
              }
              sink.done();
              return true;
            } catch (const std::exception &error) {
              const nlohmann::json failed = {
                  {"type", "error"}, {"message", error.what()}};
              const std::string wire =
                  "event: error\ndata: " + failed.dump() + "\n\n";
              if (sink.is_writable()) sink.write(wire.c_str(), wire.size());
              sink.done();
              return false;
            }
          });
    } catch (const std::exception &error) {
      res.status = 400;
      const nlohmann::json failed = {{"error", {
          {"type", "request_error"}, {"message", error.what()}}}};
      res.set_content(failed.dump(), "application/json");
    }
  });
}

void registerTokenize(httplib::Server &server, TextEncoder &encoder) {
  server.Post("/tokenize", [&encoder](const httplib::Request &request,
                                      httplib::Response &res) {
    try {
      const auto value = nlohmann::json::parse(request.body);
      const std::string prompt = value.value("prompt", std::string());
      const int chunks = encoder.contextLength(prompt) / 77;
      const int maxLength = chunks * 75 + 2;
      const TokenizeInfo tokens = encoder.tokenizeInfo(prompt, maxLength);
      const nlohmann::json output = {
          {"count", tokens.count},
          {"max_length", 0},
          {"overflow_offset", tokens.overflow_offset}};
      res.set_content(output.dump(), "application/json");
    } catch (const std::exception &error) {
      res.status = 400;
      const nlohmann::json failed = {{"error", {
          {"type", "tokenize_error"}, {"message", error.what()}}}};
      res.set_content(failed.dump(), "application/json");
    }
  });
}

}  // namespace

int main(int argc, char **argv) {
  try {
    const Options opts = parseArgs(argc, argv);
    if (opts.convert_mode) {
      convertSd15Safetensors(opts);
      return EXIT_SUCCESS;
    }
    TextEncoder encoder(false, false, 0, false);
    encoder.loadTokenizer((std::filesystem::path(opts.model_dir) /
                           "tokenizer.json").string());
    encoder.loadEmbeddingTables(opts.model_dir);
    auto pipeline = createPipeline(opts, encoder);
    if (!pipeline->initialize()) {
      throw std::runtime_error("Could not initialize MNN inference pipeline");
    }

    // Optional user-provided textual inversions, not a remote catalog.
    const std::filesystem::path embeddings =
        std::filesystem::path(opts.model_dir).parent_path().parent_path() /
        "embeddings";
    if (std::filesystem::is_directory(embeddings)) {
      try {
        encoder.loadTextualInversions(embeddings.string());
      } catch (const std::exception &error) {
        QNN_WARN("Optional embedding load failed: %s", error.what());
      }
    }

    httplib::Server server;
    server.set_payload_max_length(64ull * 1024 * 1024);
    server.Get("/health", [](const httplib::Request &,
                             httplib::Response &res) {
      res.status = 200;
    });
    registerGenerate(server, *pipeline);
    registerTokenize(server, encoder);
    server.Post("/upscale", [](const httplib::Request &,
                               httplib::Response &res) {
      res.status = 501;
      res.set_content(
          R"({"error":{"type":"not_implemented","message":"MNN upscaler is pending native validation"}})",
          "application/json");
    });
    QNN_INFO("MNN-only server on 127.0.0.1:%d (SD1.5 CPU/OpenCL)",
             opts.port);
    if (!server.listen("127.0.0.1", opts.port)) {
      throw std::runtime_error("Could not bind local HTTP port");
    }
    return EXIT_SUCCESS;
  } catch (const std::exception &error) {
    QNN_ERROR("Native MNN runtime failed: %s", error.what());
    return EXIT_FAILURE;
  }
}
