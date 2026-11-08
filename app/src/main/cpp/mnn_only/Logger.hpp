#ifndef LOCAL_DREAM_MNN_ONLY_LOGGER_HPP
#define LOCAL_DREAM_MNN_ONLY_LOGGER_HPP

// Upstream gets these format macros from Qualcomm's SampleApp SDK.
// Standalone MNN builds must not include or link any Qualcomm SDK code.
#include <cstdarg>
#include <cstdio>
#include <mutex>

namespace mnn_only_log {
inline void write(const char *level, const char *format, ...) {
  static std::mutex lock;
  std::lock_guard<std::mutex> guard(lock);
  std::fprintf(stderr, "[LocalDream MNN %s] ", level);
  va_list args;
  va_start(args, format);
  std::vfprintf(stderr, format, args);
  va_end(args);
  std::fputc('\n', stderr);
}
}  // namespace mnn_only_log

#define QNN_INFO(...) mnn_only_log::write("INFO", __VA_ARGS__)
#define QNN_WARN(...) mnn_only_log::write("WARN", __VA_ARGS__)
#define QNN_ERROR(...) mnn_only_log::write("ERROR", __VA_ARGS__)
#define QNN_DEBUG(...) mnn_only_log::write("DEBUG", __VA_ARGS__)

#endif
