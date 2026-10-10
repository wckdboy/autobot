#include "qnn_runtime.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cmath>
#include <cstdarg>
#include <cstring>
#include <sstream>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "System/QnnSystemContext.h"

#define TAG "autobot-qnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace npu {
namespace {

void qnn_log(const char * fmt, QnnLog_Level_t level, uint64_t, va_list args) {
    int prio = level == QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_vprint(prio, TAG, fmt, args);
}

std::string err_text(const char * what, Qnn_ErrorHandle_t e) {
    return std::string(what) + " failed (QNN error " + std::to_string(QNN_GET_ERROR_CODE(e)) + ")";
}

size_t type_size(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_INT_8: case QNN_DATATYPE_UINT_8: case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8: case QNN_DATATYPE_BOOL_8: return 1;
        case QNN_DATATYPE_INT_16: case QNN_DATATYPE_UINT_16: case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16: case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_BFLOAT_16: return 2;
        case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32: case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32: case QNN_DATATYPE_FLOAT_32: return 4;
        case QNN_DATATYPE_INT_64: case QNN_DATATYPE_UINT_64: case QNN_DATATYPE_FLOAT_64: return 8;
        default: return 0;
    }
}

const char * type_name(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_32: return "f32";
        case QNN_DATATYPE_FLOAT_16: return "f16";
        case QNN_DATATYPE_BFLOAT_16: return "bf16";
        case QNN_DATATYPE_UFIXED_POINT_8: return "uq8";
        case QNN_DATATYPE_UFIXED_POINT_16: return "uq16";
        case QNN_DATATYPE_SFIXED_POINT_8: return "sq8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sq16";
        case QNN_DATATYPE_INT_32: return "i32";
        case QNN_DATATYPE_UINT_32: return "u32";
        case QNN_DATATYPE_INT_64: return "i64";
        case QNN_DATATYPE_INT_8: return "i8";
        case QNN_DATATYPE_UINT_8: return "u8";
        case QNN_DATATYPE_INT_16: return "i16";
        case QNN_DATATYPE_UINT_16: return "u16";
        case QNN_DATATYPE_BOOL_8: return "bool";
        default: return "?";
    }
}

std::string json_escape(const std::string & s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if ((unsigned char) c < 0x20) { o += ' '; }
        else o += c;
    }
    return o;
}

// The leading fields of Qnn_TensorV1_t and Qnn_TensorV2_t are laid out identically.
Qnn_TensorV1_t & v1(Qnn_Tensor_t & t) { return t.v1; }
const Qnn_TensorV1_t & v1(const Qnn_Tensor_t & t) { return t.v1; }

TensorSpec spec_of(const Qnn_Tensor_t & t) {
    const auto & v = v1(t);
    TensorSpec s;
    s.name = v.name ? v.name : "";
    s.type = v.dataType;
    s.dims.assign(v.dimensions, v.dimensions + v.rank);
    const auto & q = v.quantizeParams;
    if (q.encodingDefinition == QNN_DEFINITION_DEFINED && q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        s.quantized = true;
        s.scale = q.scaleOffsetEncoding.scale;
        s.offset = q.scaleOffsetEncoding.offset;
    }
    return s;
}

/** Read-only memory map of a whole file. */
struct Mapping {
    void * data = MAP_FAILED;
    size_t size = 0;
    int fd = -1;

    bool open(const std::string & path, std::string * error) {
        fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        struct stat st {};
        if (fd < 0 || fstat(fd, &st) != 0) {
            *error = "cannot open " + path;
            return false;
        }
        size = (size_t) st.st_size;
        data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (data == MAP_FAILED) {
            *error = "cannot map " + path;
            return false;
        }
        madvise(data, size, MADV_SEQUENTIAL);
        return true;
    }

    ~Mapping() {
        if (data != MAP_FAILED) munmap(data, size);
        if (fd >= 0) close(fd);
    }
};

}  // namespace

size_t TensorSpec::elements() const {
    size_t n = 1;
    for (uint32_t d : dims) n *= d;
    return n;
}

size_t TensorSpec::bytes() const { return elements() * type_size(type); }

std::string TensorSpec::json() const {
    std::ostringstream o;
    o << "{\"name\":\"" << json_escape(name) << "\",\"type\":\"" << type_name(type) << "\",\"dims\":[";
    for (size_t i = 0; i < dims.size(); i++) o << (i ? "," : "") << dims[i];
    o << "]";
    if (quantized) o << ",\"scale\":" << scale << ",\"offset\":" << offset;
    o << "}";
    return o.str();
}

uint16_t f32_to_f16(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = (int32_t) ((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (((x >> 23) & 0xff) == 0xff) return (uint16_t) (sign | 0x7c00 | (mant ? 0x200 : 0));
    if (exp >= 31) return (uint16_t) (sign | 0x7c00);
    if (exp <= 0) {
        if (exp < -10) return (uint16_t) sign;
        mant |= 0x800000;
        uint32_t shift = (uint32_t) (14 - exp);
        uint32_t half = mant >> shift;
        uint32_t rest = mant & ((1u << shift) - 1);
        uint32_t mid = 1u << (shift - 1);
        if (rest > mid || (rest == mid && (half & 1))) half++;
        return (uint16_t) (sign | half);
    }
    uint32_t half = sign | ((uint32_t) exp << 10) | (mant >> 13);
    uint32_t rest = mant & 0x1fff;
    if (rest > 0x1000 || (rest == 0x1000 && (half & 1))) half++;
    return (uint16_t) half;
}

float f16_to_f32(uint16_t h) {
    uint32_t sign = (uint32_t) (h & 0x8000) << 16;
    uint32_t exp = (h >> 10) & 0x1f;
    uint32_t mant = h & 0x3ff;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) {
            x = sign;
        } else {
            exp = 127 - 15 + 1;
            while ((mant & 0x400) == 0) { mant <<= 1; exp--; }
            mant &= 0x3ff;
            x = sign | (exp << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        x = sign | 0x7f800000 | (mant << 13);
    } else {
        x = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &x, 4);
    return f;
}

void to_native(const TensorSpec & s, const float * src, void * dst) {
    const size_t n = s.elements();
    auto quant = [&](float v, float lo, float hi) {
        float q = std::nearbyint(v / s.scale - (float) s.offset);
        return std::fmin(hi, std::fmax(lo, q));
    };
    switch (s.type) {
        case QNN_DATATYPE_FLOAT_32: std::memcpy(dst, src, n * 4); break;
        case QNN_DATATYPE_FLOAT_16: { auto * d = (uint16_t *) dst; for (size_t i = 0; i < n; i++) d[i] = f32_to_f16(src[i]); break; }
        case QNN_DATATYPE_UFIXED_POINT_8: { auto * d = (uint8_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (uint8_t) quant(src[i], 0, 255); break; }
        case QNN_DATATYPE_UFIXED_POINT_16: { auto * d = (uint16_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (uint16_t) quant(src[i], 0, 65535); break; }
        case QNN_DATATYPE_SFIXED_POINT_8: { auto * d = (int8_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (int8_t) quant(src[i], -128, 127); break; }
        case QNN_DATATYPE_SFIXED_POINT_16: { auto * d = (int16_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (int16_t) quant(src[i], -32768, 32767); break; }
        case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32: { auto * d = (int32_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (int32_t) std::lround(src[i]); break; }
        case QNN_DATATYPE_INT_64: { auto * d = (int64_t *) dst; for (size_t i = 0; i < n; i++) d[i] = (int64_t) std::llround(src[i]); break; }
        default: std::memset(dst, 0, s.bytes()); break;
    }
}

void from_native(const TensorSpec & s, const void * src, float * dst) {
    const size_t n = s.elements();
    auto deq = [&](float q) { return (q + (float) s.offset) * s.scale; };
    switch (s.type) {
        case QNN_DATATYPE_FLOAT_32: std::memcpy(dst, src, n * 4); break;
        case QNN_DATATYPE_FLOAT_16: { auto * p = (const uint16_t *) src; for (size_t i = 0; i < n; i++) dst[i] = f16_to_f32(p[i]); break; }
        case QNN_DATATYPE_UFIXED_POINT_8: { auto * p = (const uint8_t *) src; for (size_t i = 0; i < n; i++) dst[i] = deq(p[i]); break; }
        case QNN_DATATYPE_UFIXED_POINT_16: { auto * p = (const uint16_t *) src; for (size_t i = 0; i < n; i++) dst[i] = deq(p[i]); break; }
        case QNN_DATATYPE_SFIXED_POINT_8: { auto * p = (const int8_t *) src; for (size_t i = 0; i < n; i++) dst[i] = deq(p[i]); break; }
        case QNN_DATATYPE_SFIXED_POINT_16: { auto * p = (const int16_t *) src; for (size_t i = 0; i < n; i++) dst[i] = deq(p[i]); break; }
        case QNN_DATATYPE_INT_32: { auto * p = (const int32_t *) src; for (size_t i = 0; i < n; i++) dst[i] = (float) p[i]; break; }
        default: std::memset(dst, 0, n * 4); break;
    }
}

QnnRuntime::~QnnRuntime() {
    if (have_power_) set_performance(false);
    if (device_ && api_.deviceFree) api_.deviceFree(device_);
    if (backend_ && api_.backendFree) api_.backendFree(backend_);
    if (log_ && api_.logFree) api_.logFree(log_);
    if (htp_lib_) dlclose(htp_lib_);
    if (sys_lib_) dlclose(sys_lib_);
}

bool QnnRuntime::init(const std::string & lib_dir, std::string * error) {
    if (backend_) return true;
    const std::string htp_path = lib_dir + "/libQnnHtp.so";
    const std::string sys_path = lib_dir + "/libQnnSystem.so";
    htp_lib_ = dlopen(htp_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    sys_lib_ = dlopen(sys_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!htp_lib_ || !sys_lib_) {
        *error = std::string("QNN libraries missing: ") + dlerror();
        return false;
    }
    using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    using GetSysProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);
    auto get = (GetProviders) dlsym(htp_lib_, "QnnInterface_getProviders");
    auto get_sys = (GetSysProviders) dlsym(sys_lib_, "QnnSystemInterface_getProviders");
    const QnnInterface_t ** providers = nullptr;
    const QnnSystemInterface_t ** sys_providers = nullptr;
    uint32_t n = 0, n_sys = 0;
    if (!get || !get_sys || get(&providers, &n) != QNN_SUCCESS || get_sys(&sys_providers, &n_sys) != QNN_SUCCESS || n == 0 || n_sys == 0) {
        *error = "QNN interface not available";
        return false;
    }
    bool found = false;
    for (uint32_t i = 0; i < n && !found; i++) {
        if (providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            providers[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            api_ = providers[i]->QNN_INTERFACE_VER_NAME;
            found = true;
        }
    }
    bool found_sys = false;
    for (uint32_t i = 0; i < n_sys && !found_sys; i++) {
        if (sys_providers[i]->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR &&
            sys_providers[i]->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            sys_ = sys_providers[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
            found_sys = true;
        }
    }
    if (!found || !found_sys) {
        *error = "QNN runtime is older than the headers this app was built with";
        return false;
    }
    api_.logCreate(qnn_log, QNN_LOG_LEVEL_WARN, &log_);
    Qnn_ErrorHandle_t e = api_.backendCreate(log_, nullptr, &backend_);
    if (e != QNN_SUCCESS) {
        backend_ = nullptr;
        *error = err_text("backendCreate", e);
        return false;
    }

    // Which HTP generation is this? Used to pick per-chip model files.
    const QnnDevice_PlatformInfo_t * platform = nullptr;
    if (api_.deviceGetPlatformInfo && api_.deviceGetPlatformInfo(log_, &platform) == QNN_SUCCESS && platform &&
        platform->v1.numHwDevices > 0) {
        auto * ext = reinterpret_cast<QnnHtpDevice_DeviceInfoExtension_t *>(platform->v1.hwDevices[0].v1.deviceInfoExtension);
        if (ext && ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
            arch_ = "v" + std::to_string((int) ext->onChipDevice.arch);
            LOGI("HTP arch %s, soc model %u", arch_.c_str(), ext->onChipDevice.socModel);
        }
        if (api_.deviceFreePlatformInfo) api_.deviceFreePlatformInfo(log_, platform);
    }

    e = api_.deviceCreate(log_, nullptr, &device_);
    if (e != QNN_SUCCESS) {
        device_ = nullptr;
        *error = err_text("deviceCreate", e);
        return false;
    }
    return true;
}

void QnnRuntime::set_performance(bool burst) {
    QnnDevice_Infrastructure_t infra = nullptr;
    if (!api_.deviceGetInfrastructure || api_.deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) return;
    auto * htp = reinterpret_cast<QnnHtpDevice_Infrastructure_t *>(infra);
    if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) return;
    auto & perf = htp->perfInfra;
    if (!have_power_) {
        if (perf.createPowerConfigId(0, 0, &power_id_) != QNN_SUCCESS) return;
        have_power_ = true;
    }
    QnnHtpPerfInfrastructure_PowerConfig_t cfg{};
    cfg.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    auto & d = cfg.dcvsV3Config;
    d.contextId = power_id_;
    d.setDcvsEnable = 1;
    d.dcvsEnable = burst ? 0 : 1;
    d.powerMode = burst ? QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE : QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN;
    d.setSleepLatency = 1;
    d.sleepLatency = burst ? 40 : 1000;
    d.setSleepDisable = 1;
    d.sleepDisable = burst ? 1 : 0;
    d.setBusParams = 1;
    d.busVoltageCornerMin = burst ? DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER : DCVS_VOLTAGE_VCORNER_SVS;
    d.busVoltageCornerTarget = burst ? DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER : DCVS_VOLTAGE_VCORNER_NOM;
    d.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    d.setCoreParams = 1;
    d.coreVoltageCornerMin = burst ? DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER : DCVS_VOLTAGE_VCORNER_SVS;
    d.coreVoltageCornerTarget = burst ? DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER : DCVS_VOLTAGE_VCORNER_NOM;
    d.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    const QnnHtpPerfInfrastructure_PowerConfig_t * list[] = {&cfg, nullptr};
    perf.setPowerConfig(power_id_, list);
}

bool QnnRuntime::binary_info(const void * data, size_t size, std::vector<GraphSpec> * graphs, std::string * meta,
                             QnnModel * owner, std::string * error) {
    QnnSystemContext_Handle_t sys = nullptr;
    if (sys_.systemContextCreate(&sys) != QNN_SUCCESS) {
        *error = "systemContextCreate failed";
        return false;
    }
    const QnnSystemContext_BinaryInfo_t * info = nullptr;
    Qnn_ContextBinarySize_t info_size = 0;
    Qnn_ErrorHandle_t e = sys_.systemContextGetBinaryInfo(sys, const_cast<void *>(data), size, &info, &info_size);
    if (e != QNN_SUCCESS || !info) {
        sys_.systemContextFree(sys);
        *error = err_text("reading the context binary", e);
        return false;
    }
    uint32_t n_graphs = 0;
    const QnnSystemContext_GraphInfo_t * list = nullptr;
    const char * soc = nullptr;
    const char * build = nullptr;
    switch (info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            n_graphs = info->contextBinaryInfoV1.numGraphs; list = info->contextBinaryInfoV1.graphs;
            soc = info->contextBinaryInfoV1.socVersion; build = info->contextBinaryInfoV1.buildId; break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            n_graphs = info->contextBinaryInfoV2.numGraphs; list = info->contextBinaryInfoV2.graphs;
            soc = info->contextBinaryInfoV2.socVersion; build = info->contextBinaryInfoV2.buildId; break;
        default:
            n_graphs = info->contextBinaryInfoV3.numGraphs; list = info->contextBinaryInfoV3.graphs;
            build = info->contextBinaryInfoV3.buildId; break;
    }
    if (meta) {
        std::ostringstream o;
        o << "\"soc\":\"" << json_escape(soc ? soc : "") << "\",\"build\":\"" << json_escape(build ? build : "") << "\"";
        *meta = o.str();
    }
    for (uint32_t g = 0; g < n_graphs; g++) {
        const auto & gi = list[g];
        const char * name = nullptr;
        uint32_t n_in = 0, n_out = 0;
        const Qnn_Tensor_t * in = nullptr;
        const Qnn_Tensor_t * out = nullptr;
        switch (gi.version) {
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
                name = gi.graphInfoV1.graphName; n_in = gi.graphInfoV1.numGraphInputs; in = gi.graphInfoV1.graphInputs;
                n_out = gi.graphInfoV1.numGraphOutputs; out = gi.graphInfoV1.graphOutputs; break;
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
                name = gi.graphInfoV2.graphName; n_in = gi.graphInfoV2.numGraphInputs; in = gi.graphInfoV2.graphInputs;
                n_out = gi.graphInfoV2.numGraphOutputs; out = gi.graphInfoV2.graphOutputs; break;
            default:
                name = gi.graphInfoV3.graphName; n_in = gi.graphInfoV3.numGraphInputs; in = gi.graphInfoV3.graphInputs;
                n_out = gi.graphInfoV3.numGraphOutputs; out = gi.graphInfoV3.graphOutputs; break;
        }
        GraphSpec spec;
        spec.name = name ? name : "";
        auto keep = [&](const Qnn_Tensor_t & t, std::vector<TensorSpec> & specs, std::vector<Qnn_Tensor_t> & templates) {
            specs.push_back(spec_of(t));
            if (!owner) return;
            Qnn_Tensor_t copy = t;
            auto & v = v1(copy);
            const std::string & nm = specs.back().name;
            owner->names_.emplace_back(new char[nm.size() + 1]);
            std::memcpy(owner->names_.back().get(), nm.c_str(), nm.size() + 1);
            v.name = owner->names_.back().get();
            owner->dims_.emplace_back(new uint32_t[v.rank > 0 ? v.rank : 1]);
            std::memcpy(owner->dims_.back().get(), v1(t).dimensions, v.rank * sizeof(uint32_t));
            v.dimensions = owner->dims_.back().get();
            v.memType = QNN_TENSORMEMTYPE_RAW;
            v.clientBuf = {nullptr, 0};
            if (copy.version == QNN_TENSOR_VERSION_2) {
                copy.v2.isDynamicDimensions = nullptr;
                copy.v2.sparseParams = QNN_SPARSE_PARAMS_INIT;
            }
            templates.push_back(copy);
        };
        for (uint32_t i = 0; i < n_in; i++) keep(in[i], spec.inputs, spec.input_templates);
        for (uint32_t i = 0; i < n_out; i++) keep(out[i], spec.outputs, spec.output_templates);
        graphs->push_back(std::move(spec));
    }
    sys_.systemContextFree(sys);
    return true;
}

std::string QnnRuntime::inspect(const std::string & path, std::string * error) {
    Mapping map;
    if (!map.open(path, error)) return {};
    std::vector<GraphSpec> graphs;
    std::string meta;
    if (!binary_info(map.data, map.size, &graphs, &meta, nullptr, error)) return {};
    std::ostringstream o;
    o << "{" << meta << ",\"graphs\":[";
    for (size_t g = 0; g < graphs.size(); g++) {
        o << (g ? "," : "") << "{\"name\":\"" << json_escape(graphs[g].name) << "\",\"inputs\":[";
        for (size_t i = 0; i < graphs[g].inputs.size(); i++) o << (i ? "," : "") << graphs[g].inputs[i].json();
        o << "],\"outputs\":[";
        for (size_t i = 0; i < graphs[g].outputs.size(); i++) o << (i ? "," : "") << graphs[g].outputs[i].json();
        o << "]}";
    }
    o << "]}";
    return o.str();
}

std::unique_ptr<QnnModel> QnnRuntime::load(const std::string & path, std::string * error) {
    if (!backend_) {
        *error = "NPU not initialised";
        return nullptr;
    }
    Mapping map;
    if (!map.open(path, error)) return nullptr;
    auto model = std::unique_ptr<QnnModel>(new QnnModel());
    model->rt_ = this;
    if (!binary_info(map.data, map.size, &model->graphs_, nullptr, model.get(), error)) return nullptr;
    Qnn_ErrorHandle_t e = api_.contextCreateFromBinary(backend_, device_, nullptr, map.data, map.size, &model->context_, nullptr);
    if (e != QNN_SUCCESS) {
        model->context_ = nullptr;
        *error = err_text("loading the model on the NPU", e) + " — this file may be built for another chip or QNN version";
        return nullptr;
    }
    for (auto & g : model->graphs_) {
        e = api_.graphRetrieve(model->context_, g.name.c_str(), &g.handle);
        if (e != QNN_SUCCESS) {
            *error = err_text(("graphRetrieve " + g.name).c_str(), e);
            return nullptr;
        }
    }
    LOGI("loaded %s: %zu graph(s)", path.c_str(), model->graphs_.size());
    return model;
}

QnnModel::~QnnModel() {
    if (context_ && rt_ && rt_->api_.contextFree) rt_->api_.contextFree(context_, nullptr);
}

int QnnModel::find_input(int graph, const std::string & name) const {
    const auto & ins = graphs_[graph].inputs;
    for (size_t i = 0; i < ins.size(); i++) if (ins[i].name == name) return (int) i;
    return -1;
}

bool QnnModel::execute(int graph, const std::vector<void *> & inputs, const std::vector<void *> & outputs, std::string * error) {
    auto & g = graphs_[graph];
    if (inputs.size() != g.inputs.size() || outputs.size() != g.outputs.size()) {
        *error = "wrong number of tensors for graph " + g.name;
        return false;
    }
    std::vector<Qnn_Tensor_t> in = g.input_templates;
    std::vector<Qnn_Tensor_t> out = g.output_templates;
    for (size_t i = 0; i < in.size(); i++) v1(in[i]).clientBuf = {inputs[i], (uint32_t) g.inputs[i].bytes()};
    for (size_t i = 0; i < out.size(); i++) v1(out[i]).clientBuf = {outputs[i], (uint32_t) g.outputs[i].bytes()};
    Qnn_ErrorHandle_t e = rt_->api_.graphExecute(g.handle, in.data(), (uint32_t) in.size(), out.data(), (uint32_t) out.size(), nullptr, nullptr);
    if (e != QNN_SUCCESS) {
        *error = err_text(("running " + g.name).c_str(), e);
        return false;
    }
    return true;
}

std::string QnnModel::describe() const {
    std::ostringstream o;
    o << "[";
    for (size_t g = 0; g < graphs_.size(); g++) {
        o << (g ? "," : "") << "{\"name\":\"" << json_escape(graphs_[g].name) << "\",\"inputs\":[";
        for (size_t i = 0; i < graphs_[g].inputs.size(); i++) o << (i ? "," : "") << graphs_[g].inputs[i].json();
        o << "],\"outputs\":[";
        for (size_t i = 0; i < graphs_[g].outputs.size(); i++) o << (i ? "," : "") << graphs_[g].outputs[i].json();
        o << "]}";
    }
    o << "]";
    return o.str();
}

}  // namespace npu
