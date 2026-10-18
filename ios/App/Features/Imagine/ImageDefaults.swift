import Foundation

/// Request settings the user can change by hand; once changed in a session, choosing another
/// model keeps the user's value instead of the model's recommendation.
enum ImageSetting: Hashable, CaseIterable, Sendable {
    case steps, cfg, sampler, size
}

/**
 A model's recommended image settings: the catalog entry's (`ModelManifest.recommended`, filled by
 the shared catalogs: Core ML, Local Dream, Civitai), else what its architecture needs. SDXL-class
 models never start at 512² (they produce broken images there).
 */
enum ImageDefaults {
    /// SD 1.x / 2.x without a catalog entry.
    static let sd = Recommended(steps: 25, cfg: 7, sampler: "DPM++ 2M", scheduler: "Karras", width: 512, height: 512)
    /// SDXL-class models without a catalog entry (base, Pony, Illustrious, NoobAI, Juggernaut XL…).
    static let sdxl = Recommended(steps: 25, cfg: 6, sampler: "DPM++ 2M", scheduler: "Karras", width: 1024, height: 1024)
    /// Flux and SD3-class models: native at 1024²; the other settings stay the backend's.
    static let large = Recommended(width: 1024, height: 1024)

    /// An installed model (on-device Core ML, or downloaded for a backend).
    static func recommended(for model: InstalledModel) -> Recommended {
        let arch = archOf(name: [model.id, model.title, model.baseModel ?? ""].joined(separator: " "),
                          sdxlFiles: model.manifest.files.contains { $0.name.hasPrefix("TextEncoder2") })
        return merge(model.manifest.recommended, over: arch)
    }

    /// A model a remote backend lists by name (A1111 / Forge checkpoint titles).
    static func recommended(remoteModel name: String) -> Recommended { archOf(name: name, sdxlFiles: false) }

    /// Applies [rec] to [request], skipping settings in [kept] (the user changed them by hand).
    static func apply(_ rec: Recommended, to request: inout DiffusionRequest, keeping kept: Set<ImageSetting> = []) {
        if !kept.contains(.steps), let steps = rec.steps { request.steps = steps }
        if !kept.contains(.cfg), let cfg = rec.cfg { request.cfgScale = cfg }
        if !kept.contains(.sampler), let sampler = rec.sampler {
            request.sampler = sampler
            request.scheduler = rec.scheduler
        }
        if !kept.contains(.size), let w = rec.width, let h = rec.height {
            // The model's pixel budget in the shape already chosen (16:9 stays 16:9).
            let s = ImageSizing.keepingShape(width: request.width, height: request.height, budget: (w, h))
            request.width = s.width
            request.height = s.height
        }
        if request.negativePrompt.isEmpty, let negative = rec.negativePrompt { request.negativePrompt = negative }
    }

    /// The settings of [request] as a recommendation (what a model selection left in the form).
    static func snapshot(_ request: DiffusionRequest) -> Recommended {
        Recommended(steps: request.steps, cfg: request.cfgScale, sampler: request.sampler, scheduler: request.scheduler,
                    width: request.width, height: request.height)
    }

    /// The settings the user changed since the last model selection left [applied] in the form.
    static func edited(_ request: DiffusionRequest, since applied: Recommended?) -> Set<ImageSetting> {
        guard let a = applied else { return [] }
        var out: Set<ImageSetting> = []
        if a.steps != request.steps { out.insert(.steps) }
        if a.cfg != request.cfgScale { out.insert(.cfg) }
        if a.sampler != request.sampler || a.scheduler != request.scheduler { out.insert(.sampler) }
        if a.width != request.width || a.height != request.height { out.insert(.size) }
        return out
    }

    private static func archOf(name: String, sdxlFiles: Bool) -> Recommended {
        let n = name.lowercased()
        if n.contains("flux") || n.contains("sd3") || n.contains("stable-diffusion-3") || n.contains("sd 3") { return large }
        let xlWords = ["sdxl", "xl", "pony", "illustrious", "noobai", "playground-v2", "animagine"]
        if sdxlFiles || xlWords.contains(where: { n.contains($0) }) { return sdxl }
        return sd
    }

    /// The catalog's values, with the architecture's filling what the catalog leaves out.
    private static func merge(_ catalog: Recommended?, over arch: Recommended) -> Recommended {
        guard let c = catalog else { return arch }
        let sized = c.width != nil && c.height != nil
        return Recommended(
            steps: c.steps ?? arch.steps,
            cfg: c.cfg ?? arch.cfg,
            sampler: c.sampler ?? arch.sampler,
            scheduler: c.sampler != nil ? c.scheduler : arch.scheduler,
            width: sized ? c.width : arch.width,
            height: sized ? c.height : arch.height,
            contextLength: c.contextLength,
            negativePrompt: c.negativePrompt,
        )
    }
}
