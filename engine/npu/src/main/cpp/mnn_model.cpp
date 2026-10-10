#include "mnn_model.h"

#include <sys/stat.h>

#include <cstring>

#include <MNN/Interpreter.hpp>
#include <MNN/MNNForwardType.h>
#include <MNN/Tensor.hpp>

namespace npu {
namespace {

bool exists(const std::string & path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0;
}

std::string base_name(const std::string & path) {
    const auto slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

}  // namespace

MnnModel::~MnnModel() {
    if (net_) {
        if (session_) net_->releaseSession(session_);
        MNN::Interpreter::destroy(net_);
    }
}

bool MnnModel::load(const std::string & path, bool gpu, int threads, const std::string & cache_dir, std::string * error) {
    net_ = MNN::Interpreter::createFromFile(path.c_str());
    if (!net_) {
        *error = "cannot load MNN model " + path;
        return false;
    }
    const std::string weights = path + ".weight";
    if (exists(weights)) net_->setExternalFile(weights.c_str());
    net_->setSessionMode(MNN::Interpreter::Session_Release);

    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Low;  // fp16 where the hardware has it
    backend.memory = MNN::BackendConfig::Memory_Low;
    backend.power = MNN::BackendConfig::Power_High;
    MNN::ScheduleConfig config;
    config.backendConfig = &backend;
    if (gpu) {
        config.type = MNN_FORWARD_OPENCL;
        config.backupType = MNN_FORWARD_CPU;
        config.mode = MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_BUFFER;
        if (!cache_dir.empty()) {
            cache_file_ = cache_dir + "/" + base_name(path) + ".mnncache";
            net_->setCacheFile(cache_file_.c_str());
        }
    } else {
        config.type = MNN_FORWARD_CPU;
        config.numThread = threads;
    }
    session_ = net_->createSession(config);
    if (!session_) {
        *error = "cannot create an MNN session for " + path;
        return false;
    }
    int forward = MNN_FORWARD_CPU;
    net_->getSessionInfo(session_, MNN::Interpreter::BACKENDS, &forward);
    gpu_ = gpu && forward == MNN_FORWARD_OPENCL;
    if (gpu_ && !cache_file_.empty()) net_->updateCacheFile(session_);
    return true;
}

std::vector<std::string> MnnModel::input_names() const {
    std::vector<std::string> names;
    for (const auto & kv : net_->getSessionInputAll(session_)) names.push_back(kv.first);
    return names;
}

bool MnnModel::prepare_input(const std::string & name, const std::vector<int> & shape, std::string * error) {
    MNN::Tensor * t = net_->getSessionInput(session_, name.c_str());
    if (!t) {
        *error = "model has no input '" + name + "'";
        return false;
    }
    if (t->shape() != shape) {
        net_->resizeTensor(t, shape);
        resized_ = true;
    }
    return true;
}

bool MnnModel::set_input(const std::string & name, const std::vector<int> & shape, const float * data, std::string * error) {
    if (!prepare_input(name, shape, error)) return false;
    if (resized_) {
        net_->resizeSession(session_);
        resized_ = false;
    }
    MNN::Tensor * t = net_->getSessionInput(session_, name.c_str());
    MNN::Tensor host(t, t->getDimensionType());
    std::memcpy(host.host<float>(), data, host.elementSize() * sizeof(float));
    t->copyFromHostTensor(&host);
    return true;
}

bool MnnModel::set_input_int(const std::string & name, const std::vector<int> & shape, const int * data, std::string * error) {
    if (!prepare_input(name, shape, error)) return false;
    if (resized_) {
        net_->resizeSession(session_);
        resized_ = false;
    }
    MNN::Tensor * t = net_->getSessionInput(session_, name.c_str());
    MNN::Tensor host(t, t->getDimensionType());
    std::memcpy(host.host<int>(), data, host.elementSize() * sizeof(int));
    t->copyFromHostTensor(&host);
    return true;
}

bool MnnModel::run(std::string * error) {
    if (net_->runSession(session_) != MNN::NO_ERROR) {
        *error = "MNN inference failed";
        return false;
    }
    return true;
}

bool MnnModel::output(const std::string & name, std::vector<float> * out, std::vector<int> * shape, std::string * error) {
    MNN::Tensor * t = net_->getSessionOutput(session_, name.empty() ? nullptr : name.c_str());
    if (!t) {
        *error = "model has no output '" + name + "'";
        return false;
    }
    MNN::Tensor host(t, t->getDimensionType());
    t->copyToHostTensor(&host);
    out->assign(host.host<float>(), host.host<float>() + host.elementSize());
    if (shape) *shape = host.shape();
    return true;
}

}  // namespace npu
