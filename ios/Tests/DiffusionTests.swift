import Foundation
import Testing
@testable import Autobot

struct DiffusionTests {
    // MARK: PromptSyntax

    @Test func appendsMissingLoraTags() {
        let out = PromptSyntax.withLoraTags("a cat <lora:style:0.5>  ", [LoraRef(name: "style", weight: 0.8), LoraRef(name: "detail", weight: 0.75), LoraRef(name: "off", weight: 0)])
        #expect(out == "a cat <lora:style:0.5> <lora:detail:0.75>")
        #expect(PromptSyntax.withLoraTags("a cat", []) == "a cat")
    }

    @Test func stripsLoraTags() {
        #expect(PromptSyntax.stripLoraTags("a <lora:x:1> cat  <lora:y> on a mat") == "a cat on a mat")
    }

    @Test func estimatesTokens() {
        #expect(PromptSyntax.estimateTokens("a cat") == 2)
        #expect(PromptSyntax.estimateTokens("photorealistic, 8k") == 2 + 1 + 1 + 1)
        #expect(PromptSyntax.estimateTokens("<lora:x:1>") == 0)
    }

    @Test func snapsToMultiple() {
        #expect(PromptSyntax.snap(515) == 512)
        #expect(PromptSyntax.snap(517) == 520)
        #expect(PromptSyntax.snap(10) == 64)
        #expect(PromptSyntax.snap(5000) == 2048)
        #expect(PromptSyntax.snap(700, multiple: 64) == 704)
    }

    // MARK: SD API

    @Test func sdApiTxt2imgBody() {
        var r = DiffusionRequest()
        r.prompt = "a cat"
        r.loras = [LoraRef(name: "style", weight: 0.8)]
        r.model = "sdxl.safetensors"
        r.clipSkip = 2
        r.batchCount = 3
        let body = SdApiEngine.requestBody(r, DiffusionInputs())
        #expect(body["prompt"] as? String == "a cat <lora:style:0.80>")
        #expect(body["n_iter"] as? Int == 3)
        #expect(body["batch_size"] as? Int == 1)
        #expect(body["scheduler"] as? String == "Karras")
        let overrides = body["override_settings"] as? [String: Any]
        #expect(overrides?["sd_model_checkpoint"] as? String == "sdxl.safetensors")
        #expect(overrides?["CLIP_stop_at_last_layers"] as? Int == 2)
        #expect(body["init_images"] == nil)
        #expect(body["mask"] == nil)
    }

    @Test func sdApiInpaintBody() {
        var r = DiffusionRequest()
        r.mode = .inpaint
        r.maskBlur = 8
        r.inpaintOnlyMasked = false
        let body = SdApiEngine.requestBody(r, DiffusionInputs(initImage: Data([1, 2]), mask: Data([3])))
        #expect((body["init_images"] as? [String]) == [Data([1, 2]).base64EncodedString()])
        #expect(body["mask"] as? String == Data([3]).base64EncodedString())
        #expect(body["mask_blur"] as? Int == 8)
        #expect(body["inpainting_fill"] as? Int == 1)
        #expect(body["inpaint_full_res"] as? Bool == false)
        #expect(body["denoising_strength"] as? Double == 0.6)
    }

    @Test func errorDetailPicksTheMessage() {
        #expect(SdApiEngine.errorDetail(#"{"detail":"Not Found"}"#) == "Not Found")
        #expect(SdApiEngine.errorDetail(#"{"detail":[{"msg":"field required"}]}"#) == "field required")
        #expect(SdApiEngine.errorDetail(#"{"error":"OutOfMemory","errors":"CUDA out of memory"}"#) == "CUDA out of memory")
        #expect(SdApiEngine.errorDetail("Internal Server Error") == "Internal Server Error")
        #expect(SdApiEngine.errorDetail("   ") == "error")
    }

    @Test func decodesBase64DataUrls() {
        #expect(SdApiEngine.decodeBase64("data:image/png;base64,AQID") == Data([1, 2, 3]))
        #expect(SdApiEngine.decodeBase64("AQID") == Data([1, 2, 3]))
        #expect(SdApiEngine.decodeBase64("") == nil)
    }

    // MARK: Local SSE

    @Test func schedulerIds() {
        #expect(LocalSseEngine.schedulerId("DPM++ 2M", "Karras") == "dpm_karras")
        #expect(LocalSseEngine.schedulerId("DPM++ SDE", nil) == "dpm_sde")
        #expect(LocalSseEngine.schedulerId("Euler a", nil) == "euler_a")
        #expect(LocalSseEngine.schedulerId("Euler", "karras") == "euler_karras")
        #expect(LocalSseEngine.schedulerId("LCM", "Karras") == "lcm")
        #expect(LocalSseEngine.schedulerId("euler_a_karras", nil) == "euler_a_karras")
        #expect(LocalSseEngine.schedulerId("UniPC", nil) == "dpm")
    }

    @Test func localSseBody() {
        var r = DiffusionRequest()
        r.prompt = "a <lora:x:1> cat"
        r.mode = .img2img
        let body = LocalSseEngine.requestBody(r, DiffusionInputs(initImage: Data([9])), seed: 42)
        #expect(body["prompt"] as? String == "a cat")
        #expect(body["seed"] as? Int64 == 42)
        #expect(body["scheduler"] as? String == "dpm_karras")
        #expect(body["image"] as? String == Data([9]).base64EncodedString())
        #expect(body["mask"] == nil)
        #expect(LocalSseEngine.requestBody(DiffusionRequest(), DiffusionInputs(), seed: -1)["seed"] == nil)
    }

    // MARK: JSON

    @Test func decodesKotlinShapedRequest() throws {
        // kotlinx.serialization with encodeDefaults = true (Android's gallery paramsJson).
        let json = #"{"mode":"INPAINT","prompt":"a cat","negativePrompt":"","width":768,"height":512,"steps":8,"cfgScale":1.0,"seed":123,"sampler":"euler","scheduler":null,"batchCount":1,"denoiseStrength":0.75,"maskBlur":4,"inpaintOnlyMasked":true,"loras":[{"name":"s","weight":0.8}],"clipSkip":null,"model":"catalog:z"}"#
        let r = try JSONDecoder().decode(DiffusionRequest.self, from: Data(json.utf8))
        #expect(r.mode == .inpaint)
        #expect(r.width == 768 && r.height == 512)
        #expect(r.scheduler == nil)
        #expect(r.loras == [LoraRef(name: "s", weight: 0.8)])
        #expect(r.model == "catalog:z")
        let again = try JSONDecoder().decode(DiffusionRequest.self, from: JSONEncoder().encode(r))
        #expect(again == r)
    }

    @Test func missingFieldsUseDefaults() throws {
        let r = try JSONDecoder().decode(DiffusionRequest.self, from: Data(#"{"prompt":"x"}"#.utf8))
        #expect(r.prompt == "x")
        #expect(r.scheduler == "Karras")
        #expect(r.steps == 20)
        #expect(r.mode == .txt2img)
    }

    @Test func shortModelNames() {
        #expect(shortModelName("sd_xl_turbo_1.0_fp16.safetensors [abc]") == "sd xl turbo 1.0 fp16")
        #expect(shortModelName("models/foo.ckpt") == "foo")
    }
}
