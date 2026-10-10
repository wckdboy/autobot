// A single MNN model (text encoder, or a UNet/VAE for the GPU path) behind a tiny API.

#pragma once

#include <memory>
#include <string>
#include <vector>

namespace MNN {
class Interpreter;
class Session;
}  // namespace MNN

namespace npu {

class MnnModel {
public:
    ~MnnModel();

    /**
     * Loads [path] (and `<path>.weight` if present). [gpu] selects OpenCL with fp16 and falls back
     * to the CPU automatically if OpenCL is missing. Compiled GPU kernels are cached in [cache_dir].
     */
    bool load(const std::string & path, bool gpu, int threads, const std::string & cache_dir, std::string * error);

    /** Feeds float input [name] with [shape] (resizing the session when the shape changes). */
    bool set_input(const std::string & name, const std::vector<int> & shape, const float * data, std::string * error);
    /** Feeds an int32 input (token ids, timesteps). */
    bool set_input_int(const std::string & name, const std::vector<int> & shape, const int * data, std::string * error);

    bool run(std::string * error);

    /** Copies output [name] into [out] as floats; [shape] receives its dimensions. */
    bool output(const std::string & name, std::vector<float> * out, std::vector<int> * shape, std::string * error);

    std::vector<std::string> input_names() const;
    bool on_gpu() const { return gpu_; }

private:
    MNN::Interpreter * net_ = nullptr;
    MNN::Session * session_ = nullptr;
    bool gpu_ = false;
    bool resized_ = false;
    std::string cache_file_;

    bool prepare_input(const std::string & name, const std::vector<int> & shape, std::string * error);
};

}  // namespace npu
