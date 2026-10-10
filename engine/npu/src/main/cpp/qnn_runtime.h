// Minimal runtime for precompiled QNN context binaries on the Hexagon NPU (HTP backend).
//
// The QNN libraries (libQnnHtp.so, libQnnSystem.so and the per-arch DSP skeletons) ship in the
// APK from Qualcomm's qnn-runtime package and are loaded with dlopen, so the engine still runs
// (CPU/GPU paths) on phones without an HTP.

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemInterface.h"

namespace npu {

/** Shape, type and quantization of one graph input/output. */
struct TensorSpec {
    std::string name;
    Qnn_DataType_t type = QNN_DATATYPE_UNDEFINED;
    std::vector<uint32_t> dims;
    bool quantized = false;
    float scale = 1.0f;
    int32_t offset = 0;

    size_t elements() const;
    size_t bytes() const;
    std::string json() const;
};

struct GraphSpec {
    std::string name;
    std::vector<TensorSpec> inputs;
    std::vector<TensorSpec> outputs;
    std::vector<Qnn_Tensor_t> input_templates;   // as reported by the binary, names/dims owned by the model
    std::vector<Qnn_Tensor_t> output_templates;
    Qnn_GraphHandle_t handle = nullptr;
};

class QnnRuntime;

/** One loaded context binary (e.g. a UNet) with its graphs. */
class QnnModel {
public:
    ~QnnModel();
    const std::vector<GraphSpec> & graphs() const { return graphs_; }
    int find_input(int graph, const std::string & name) const;

    /**
     * Runs graph [graph]. Buffers are in each tensor's native type and size (see TensorSpec::bytes);
     * use to_native / from_native to convert floats.
     */
    bool execute(int graph, const std::vector<void *> & inputs, const std::vector<void *> & outputs, std::string * error);

    std::string describe() const;

private:
    friend class QnnRuntime;
    QnnRuntime * rt_ = nullptr;
    Qnn_ContextHandle_t context_ = nullptr;
    std::vector<GraphSpec> graphs_;
    std::vector<std::unique_ptr<char[]>> names_;           // owned copies of tensor names
    std::vector<std::unique_ptr<uint32_t[]>> dims_;        // owned copies of dimensions
};

class QnnRuntime {
public:
    ~QnnRuntime();

    /** Loads libQnnHtp/libQnnSystem from [lib_dir] and opens the HTP device. */
    bool init(const std::string & lib_dir, std::string * error);
    bool ready() const { return backend_ != nullptr; }

    /** Loads a context binary from [path] (memory-mapped while the context is created). */
    std::unique_ptr<QnnModel> load(const std::string & path, std::string * error);

    /** Inspects a context binary without loading it on the NPU: graphs, tensors, SoC, SDK build. */
    std::string inspect(const std::string & path, std::string * error);

    /** Asks the NPU to stay at maximum clocks (burst) while generating, or to relax again. */
    void set_performance(bool burst);

    /** SoC model / HTP arch reported by the device, e.g. "v79". */
    std::string arch() const { return arch_; }

    const QNN_INTERFACE_VER_TYPE & api() const { return api_; }

private:
    friend class QnnModel;
    void * htp_lib_ = nullptr;
    void * sys_lib_ = nullptr;
    QNN_INTERFACE_VER_TYPE api_{};
    QNN_SYSTEM_INTERFACE_VER_TYPE sys_{};
    Qnn_LogHandle_t log_ = nullptr;
    Qnn_BackendHandle_t backend_ = nullptr;
    Qnn_DeviceHandle_t device_ = nullptr;
    uint32_t power_id_ = 0;
    bool have_power_ = false;
    std::string arch_;

    bool binary_info(const void * data, size_t size, std::vector<GraphSpec> * graphs, std::string * meta,
                     QnnModel * owner, std::string * error);
};

/** Converts float32 values into a tensor's native representation (fp16/fp32/quantized/int). */
void to_native(const TensorSpec & spec, const float * src, void * dst);
/** Converts a tensor's native representation into float32. */
void from_native(const TensorSpec & spec, const void * src, float * dst);

uint16_t f32_to_f16(float f);
float f16_to_f32(uint16_t h);

}  // namespace npu
