#ifndef PIPELINESD15CPU_HPP
#define PIPELINESD15CPU_HPP

#include <MNN/Interpreter.hpp>
#include <memory>
#include <stdexcept>
#include <string>

#include "Config.hpp"
#include "MnnUtils.hpp"
#include "Pipeline.hpp"

// sd15cpu: the whole pipeline runs on MNN (CPU or OpenCL). Nothing stays
// resident between requests -- every stage creates its interpreter from the
// mmap'd model, runs, and releases, keeping idle memory minimal.
class PipelineSd15Cpu : public Pipeline {
 public:
  PipelineSd15Cpu(TextEncoder &text_encoder, const std::string &model_dir,
                  std::string clip_path, std::string unet_path,
                  std::string vae_decoder_path, std::string vae_encoder_path,
                  bool use_v_pred, int cpu_threads = 4)
      : Pipeline(text_encoder, model_dir, /*sdxl=*/false, use_v_pred),
        cpu_threads_(cpu_threads),
        clip_path_(std::move(clip_path)),
        unet_path_(std::move(unet_path)),
        vae_decoder_path_(std::move(vae_decoder_path)),
        vae_encoder_path_(std::move(vae_encoder_path)) {}

  bool initialize() override { return true; }

  bool supportsImg2Img() const override { return !vae_encoder_path_.empty(); }

 protected:
  bool canSkipUncond() const override { return true; }
  bool previewSupported() const override { return false; }

  void encodeText(const ProcessedPromptPair &prompts, bool need_negative,
                  bool need_positive, Conditioning &cond) override {
    MNN::Interpreter *interpreter =
        createMnnInterpreterMmap(clip_path_.c_str());
    if (!interpreter)
      throw std::runtime_error(
          "Failed to create temporary MNN CLIP interpreter!");

    MnnSessionOptions opts;  // CLIP always runs on CPU
    opts.num_threads = cpu_threads_;
    MNN::Session *session = createMnnSession(interpreter, opts);
    if (!session) {
      delete interpreter;
      throw std::runtime_error("Failed to create temporary MNN CLIP session!");
    }

    ScopeExit stage_cleanup{[interpreter, session] {
      interpreter->releaseSession(session);
      delete interpreter;
    }};
    auto input = interpreter->getSessionInput(session, "input_embedding");
    interpreter->resizeTensor(input, {1, 77, text_embedding_size});
    interpreter->resizeSession(session);
    interpreter->releaseModel();

    auto run_side = [&](const std::vector<float> &embeddings, float *dst) {
      const size_t chunk_size = 77 * text_embedding_size;
      for (size_t offset = 0; offset < embeddings.size(); offset += chunk_size) {
        memcpy(input->host<float>(), embeddings.data() + offset,
               chunk_size * sizeof(float));
        if (interpreter->runSession(session) != 0)
          throw std::runtime_error("MNN text encoder execution failed");
        auto out = interpreter->getSessionOutput(session, "last_hidden_state");
        memcpy(dst + offset, out->host<float>(), chunk_size * sizeof(float));
      }
    };

    if (need_negative) run_side(prompts.negative_embeddings, cond.negHidden());
    if (need_positive) run_side(prompts.positive_embeddings, cond.posHidden());

    // Automatically released on both successful and exceptional paths.
  }

  void vaeEncode(const GenerationRequest &req, const float *image, float *mean,
                 float *std_dev) override {
    MNN::Interpreter *interpreter =
        createMnnInterpreterMmap(vae_encoder_path_.c_str());
    if (!interpreter) throw std::runtime_error("Failed MNN VAE Enc create");

    MNN::Session *session =
        createMnnSession(interpreter, sessionOptions(req, "vae_enc_cache"));
    if (!session) {
      delete interpreter;
      throw std::runtime_error("Failed create temp MNN VAE Enc session!");
    }

    ScopeExit stage_cleanup{[interpreter, session] {
      interpreter->releaseSession(session);
      delete interpreter;
    }};
    auto input = interpreter->getSessionInput(session, "input");
    interpreter->resizeTensor(input, {1, 3, req.height, req.width});
    interpreter->resizeSession(session);
    if (req.use_opencl) interpreter->updateCacheFile(session);
    interpreter->releaseModel();

    auto input_nchw_tensor = std::make_unique<MNN::Tensor>(input, MNN::Tensor::CAFFE);
    auto mean_t = interpreter->getSessionOutput(session, "mean");
    auto std_t = interpreter->getSessionOutput(session, "std");
    auto mean_nchw_tensor = std::make_unique<MNN::Tensor>(mean_t, MNN::Tensor::CAFFE);
    auto std_nchw_tensor = std::make_unique<MNN::Tensor>(std_t, MNN::Tensor::CAFFE);

    size_t image_count = (size_t)3 * req.height * req.width;
    size_t latent_count = (size_t)4 * (req.height / 8) * (req.width / 8);
    memcpy(input_nchw_tensor->host<float>(), image,
           image_count * sizeof(float));
    input->copyFromHostTensor(input_nchw_tensor.get());
    if (interpreter->runSession(session) != 0)
      throw std::runtime_error("MNN VAE inference failed");

    mean_t->copyToHostTensor(mean_nchw_tensor.get());
    std_t->copyToHostTensor(std_nchw_tensor.get());
    memcpy(mean, mean_nchw_tensor->host<float>(), latent_count * sizeof(float));
    memcpy(std_dev, std_nchw_tensor->host<float>(),
           latent_count * sizeof(float));


    // Automatically released on both successful and exceptional paths.
  }

  void beginDenoise(const GenerationRequest &req) override {
    unet_interpreter_ = createMnnInterpreterMmap(unet_path_.c_str());
    if (!unet_interpreter_)
      throw std::runtime_error(
          "Failed to create temporary MNN UNET interpreter!");

    unet_interpreter_->setSessionMode(MNN::Interpreter::Session_Resize_Defer);
    unet_session_ =
        createMnnSession(unet_interpreter_, sessionOptions(req, "unet_cache"));
    if (!unet_session_)
      throw std::runtime_error("Failed to create temporary MNN UNET session!");

    auto samp = unet_interpreter_->getSessionInput(unet_session_, "sample");
    auto ts = unet_interpreter_->getSessionInput(unet_session_, "timestep");
    auto enc = unet_interpreter_->getSessionInput(unet_session_,
                                                  "encoder_hidden_states");

    const int batch = req.cfg == 1.0f ? 1 : 2;
    const int tokens = text_encoder_.contextLength(req.prompt, req.negative_prompt);
    unet_interpreter_->resizeTensor(samp,
                                    {batch, 4, req.height / 8, req.width / 8});
    unet_interpreter_->resizeTensor(ts, {1});
    unet_interpreter_->resizeTensor(enc, {batch, tokens, text_embedding_size});
    unet_interpreter_->resizeSession(unet_session_);
    if (req.use_opencl) unet_interpreter_->updateCacheFile(unet_session_);

    unet_interpreter_->releaseModel();

    // MNN host-side staging tensors are the same shapes for every diffusion
    // step in one run. Allocate once after resize, not three new/delete pairs
    // on every UNet invocation. This changes no numerical operations.
    unet_sample_host_ = std::make_unique<MNN::Tensor>(samp, MNN::Tensor::CAFFE);
    unet_timestep_host_ = std::make_unique<MNN::Tensor>(ts, MNN::Tensor::CAFFE);
    unet_encoder_host_ = std::make_unique<MNN::Tensor>(enc, MNN::Tensor::CAFFE);
  }

  void runUnetStep(const GenerationRequest &req, const float *latents_batch2,
                   float timestep_f, bool skip_uncond, Conditioning &cond,
                   float *out_batch2) override {
    const int timestep = static_cast<int>(timestep_f);
    auto samp = unet_interpreter_->getSessionInput(unet_session_, "sample");
    auto ts = unet_interpreter_->getSessionInput(unet_session_, "timestep");
    auto enc = unet_interpreter_->getSessionInput(unet_session_,
                                                  "encoder_hidden_states");

    auto *samp_nchw_tensor = unet_sample_host_.get();
    auto *ts_nchw_tensor = unet_timestep_host_.get();
    auto *enc_nchw_tensor = unet_encoder_host_.get();

    const size_t latent_count = (size_t)4 * (req.height / 8) * (req.width / 8);
    const size_t output_count = (skip_uncond ? 1 : 2) * latent_count;
    memcpy(samp_nchw_tensor->host<float>(),
           latents_batch2 + (skip_uncond ? latent_count : 0),
           output_count * sizeof(float));
    memcpy(ts_nchw_tensor->host<int>(), &timestep, sizeof(int));
    memcpy(enc_nchw_tensor->host<float>(),
           skip_uncond ? cond.posHidden() : cond.hidden.data(),
           (skip_uncond ? cond.hidden.size() / 2 : cond.hidden.size()) * sizeof(float));

    samp->copyFromHostTensor(samp_nchw_tensor);
    ts->copyFromHostTensor(ts_nchw_tensor);
    enc->copyFromHostTensor(enc_nchw_tensor);

    // CFG=1 runs only the positive batch.
    if (unet_interpreter_->runSession(unet_session_) != 0)
      throw std::runtime_error("MNN UNet inference failed");

    auto output =
        unet_interpreter_->getSessionOutput(unet_session_, "out_sample");
    output->copyToHostTensor(samp_nchw_tensor);
    memcpy(out_batch2 + (skip_uncond ? latent_count : 0),
           samp_nchw_tensor->host<float>(), output_count * sizeof(float));

    // Staging tensors remain owned by the pipeline until endDenoise().
  }

  void endDenoise() override {
    // Host buffers reference the resized session's shape; free before the
    // MNN session and interpreter are destroyed or replaced.
    unet_sample_host_.reset();
    unet_timestep_host_.reset();
    unet_encoder_host_.reset();
    if (unet_session_) unet_interpreter_->releaseSession(unet_session_);
    unet_session_ = nullptr;
    delete unet_interpreter_;
    unet_interpreter_ = nullptr;
  }

  void releaseTransientModels() override { endDenoise(); }

  void vaeDecode(const GenerationRequest &req, const float *latents,
                 float *pixels) override {
    MNN::Interpreter *interpreter =
        createMnnInterpreterMmap(vae_decoder_path_.c_str());
    if (!interpreter)
      throw std::runtime_error(
          "Failed to create temporary MNN VAE Decoder interpreter!");

    MNN::Session *session =
        createMnnSession(interpreter, sessionOptions(req, "vae_dec_cache"));
    if (!session) {
      delete interpreter;
      throw std::runtime_error("Failed create temp MNN VAE Dec session!");
    }

    ScopeExit stage_cleanup{[interpreter, session] {
      interpreter->releaseSession(session);
      delete interpreter;
    }};
    auto input = interpreter->getSessionInput(session, "latent_sample");
    interpreter->resizeTensor(input, {1, 4, req.height / 8, req.width / 8});
    interpreter->resizeSession(session);
    if (req.use_opencl) interpreter->updateCacheFile(session);
    interpreter->releaseModel();

    auto input_nchw_tensor = std::make_unique<MNN::Tensor>(input, MNN::Tensor::CAFFE);
    auto output = interpreter->getSessionOutput(session, "sample");
    auto output_nchw_tensor = std::make_unique<MNN::Tensor>(output, MNN::Tensor::CAFFE);

    size_t latent_count = (size_t)4 * (req.height / 8) * (req.width / 8);
    size_t pixel_count = (size_t)3 * req.height * req.width;
    memcpy(input_nchw_tensor->host<float>(), latents,
           latent_count * sizeof(float));
    input->copyFromHostTensor(input_nchw_tensor.get());

    if (interpreter->runSession(session) != 0)
      throw std::runtime_error("MNN VAE inference failed");

    output->copyToHostTensor(output_nchw_tensor.get());
    memcpy(pixels, output_nchw_tensor->host<float>(),
           pixel_count * sizeof(float));


    // Automatically released on both successful and exceptional paths.
  }

 private:
  // Each OpenCL cache must be keyed by the actual MNN tensor geometry.
  // Preserve the legacy per-width cache name for square canvases; for
  // rectangular HD sizes distinguish width AND height so incompatible
  // tuning metadata never gets reused across different tensor shapes.
  MnnSessionOptions sessionOptions(const GenerationRequest &req,
                                   const char *stage) const {
    MnnSessionOptions opts;
    opts.use_opencl = req.use_opencl;
    opts.num_threads = cpu_threads_;
    if (req.use_opencl) {
      auto cache_dir = ensureCacheDir(model_dir_);
      if (!cache_dir.empty())
        opts.cache_file =
            cache_dir + "/" + stage + ".mnnc." + std::to_string(req.width) +
            (req.height == req.width ? "" : "x" + std::to_string(req.height));
    }
    return opts;
  }

  const int cpu_threads_;
  const std::string clip_path_;
  const std::string unet_path_;
  const std::string vae_decoder_path_;
  const std::string vae_encoder_path_;

  MNN::Interpreter *unet_interpreter_ = nullptr;
  MNN::Session *unet_session_ = nullptr;
  std::unique_ptr<MNN::Tensor> unet_sample_host_;
  std::unique_ptr<MNN::Tensor> unet_timestep_host_;
  std::unique_ptr<MNN::Tensor> unet_encoder_host_;
};

#endif  // PIPELINESD15CPU_HPP
