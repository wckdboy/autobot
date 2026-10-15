# Creative Studio core (`:shared`, `dev.wckdboy.autobot.shared.studio`)

The platform-independent half of the Studio: data model, prompt tools, the job queue, ComfyUI
protocol and engine, workflow import / parameter detection, multi-step pipelines and the agent's
`run_workflow` tool. The UI and the platform engines live in each app and plug in through the
interfaces below. Spec: Creative Studio spec, sections 1, 5 and 6.

## Model (`Model.kt`)

| Type | What |
|---|---|
| `StudioProject(id, name, coverAssetId, createdAt, updatedAt)` | a group of assets |
| `StudioAsset(id, projectId, type, recipe, createdAt, mimeType, sizeBytes, durationMs, width, height, favorite, parentId, label)` | one generation; bytes stay in the platform's encrypted gallery store under `id`; `parentId` = "use as input" source |
| `AssetType` | `IMAGE`, `AUDIO`, `VIDEO` |
| `Recipe(engine, backendId, model, params, inputs, workflowId, workflowVersion, seed, output)` | everything needed to remake an asset. `engine` is an `EngineKinds` string (`diffusion`, `upscale`, `audio`, `speech`, `comfyui`); `backendId` null = on device |
| `ParamValue` | typed values: `IntValue`, `FloatValue`, `StringValue`, `BoolValue`, `AssetValue(AssetRef)`; JSON `{"type":"int","value":20}` |
| `AssetFilter(projectId, types, favoritesOnly)` | library filter |

JSON is `StudioJson` / `StudioCodec` (stable names, unknown keys ignored, every new field has a
default — additive only). `.autobot` exports carry `studioProjects`, `studioAssets`,
`studioWorkflows`, `studioSnippets` in the manifest and asset files at
`BackupManifest.studioEntry(asset)` (`studio/<id>.<ext>`); older exports import with none.

Storage: implement `StudioStore` (Kotlin, suspend) or the Swift-friendly `StudioStoreBridge`
(blocking, JSON rows by kind; wrap with `BridgedStudioStore`). `InMemoryStudioStore` is for tests
and previews. Recipe helpers: `recipe.with(name, value)` (`seed` / `model` set their fields),
`recipe.withInput(assetId, role)` for "use as input", reuse a recipe by submitting `asset.recipe`.

## Prompt tools (`PromptTools.kt`)

- `Wildcards.expand(template, cap = 64)` → `Expansion(prompts, total)`; `{a|b}`, nesting,
  `{red:3|blue}` weights (copies; `:0` drops), `\{ \| \}` literal, `(word:1.2)` untouched.
  Order: left-most choice varies slowest. `Wildcards.sample(template, count, seed)` draws by weight.
- `Seeds.plan(SeedMode.RANDOM|LOCKED|WALK, base, count)`; seeds are 0..`Seeds.MAX` (2³²−1).
- `BatchPlanner.plan(recipe, batchCount, seedMode)` → `PlannedJob(recipe, label)` list.
- `GridPlanner.plan(recipe, GridAxis(param, values, label), y?)` → `GridPlan(jobs, columns, rows,
  truncated)`; any param plus `seed` / `model`; `GridPlanner.parseValues("10, 20" | "5-9" | "1-2 (+0.25)")`.
- `PromptLibrary.insert(prompt, cursor, snippet)`, `applyStyle(prompt, negative, style)`
  (`{prompt}` placeholder), `joinNegative`, `adjustWeight(prompt, start, end, ±0.1)`,
  `builtInStyles`, `negativePresets`. User snippets/styles: `PromptSnippet` in the store.

## Queue (`Queue.kt`)

```kotlin
val engines = EngineRegistry().apply { register(myDiffusionEngine); register(comfyEngine) }
val queue = JobQueue(appScope, engines, StoreOutputSink(store, writeBytes = { id, out -> gallery.write(id, out) }), persistence)
queue.restore()                      // once at launch
queue.submit(projectId, recipe, label)        // or submitAll(projectId, BatchPlanner.plan(...))
queue.jobs                           // StateFlow<List<StudioJob>> for the queue strip
queue.cancel(id); queue.retry(id); queue.clearFinished(); queue.await(id)
```

- `StudioJob(id, projectId, recipe, label, state, progress, step, steps, status, outputs,
  error, parentAssetId, …, preview)`; states `QUEUED → RUNNING → DONE | FAILED | CANCELLED`.
  `preview` (latest frame) is transient. `outputs` are asset ids.
- Concurrency: `EngineCapabilities.slot` — every on-device engine shares `on-device` (1 job);
  each remote backend has `backend:<id>` (1 job by default, `slotLimits` overrides). FIFO per
  slot; a busy slot never blocks another.
- `StudioEngine { capabilities; suspend fun run(job, onEvent): List<EngineOutput> }`; events
  `Progress(value, max)`, `Preview(mime, bytes)`, `Status(text)`; throw `StudioEngineException`
  to fail with a message; honour cancellation. Swift engines implement `HostStudioEngine`
  (callbacks, job as JSON) and register `HostEngineAdapter(host)`.
- Persistence: `JobPersistence` (load/save/remove; rows via `StudioCodec.encodeJob`). Jobs that
  were running when the app died restore as FAILED (`JobQueue.INTERRUPTED`) — retry is explicit.
- iOS: on-device GPU/NPU jobs cannot continue in the background; the queue simply resumes when
  the app is foregrounded (remote jobs keep running on the PC).

## ComfyUI (`comfy/`)

`ComfyClient(ComfyBackend(id, label, baseUrl, route, authHeader), http, bytes, sockets)`:
`objectInfo()`, `systemStats()`, `queue()`, `submit(graph)`, `history(id)`, `view(file)`,
`upload(name, mime, bytes)`, `interrupt(id)`, `dequeue(id)`, and `execute(graph, onEvent)` which
opens `/ws?clientId=` first, queues the prompt, follows progress / node names / binary previews,
falls back to polling `/history` (with `/queue` position) when the socket is missing or drops,
and on cancellation dequeues + interrupts. `ComfyEngine(backend, client, workflows, assets)` is
the `StudioEngine` for saved workflows (uploads input assets, applies values, downloads saved
outputs; animated WEBP → video). Wire format in `ComfyProtocol.kt` (`ObjectInfo`, `SubmitResult`,
`HistoryEntry`, `ComfyMessage`, `ComfyBinary`, `ComfyRequests`).

Transports (`shared/MediaTransport.kt`): `BinaryHttpTransport` (whole binary bodies) and
`WebSocketTransport` next to `HttpTransport`. Implemented by Android `OkHttpTransport` and iOS
`URLSessionTransport` with the same kill switch (ws = cleartext, wss = TLS), route, header scrub
and audit (the upgrade is one entry; message bytes are added when the socket ends). Every Studio
request is tagged `studio:comfyui`.

## Workflows (`Workflow.kt`)

- `WorkflowImport.parse(json)` → `Graph` | `Invalid(message)`; API format (also wrapped in
  `{"prompt": …}`); the editor format is refused with `UI_FORMAT_MESSAGE` ("Save (API Format)").
- `WorkflowImport.create(name, json, objectInfo?, id, now, backendId)` → `StudioWorkflow(id, name,
  graph, params, output, outputNodes, version, …)`, parameters detected by `WorkflowDetector`:
  prompt / negative (text encoders traced back from the sampler's positive / negative, through
  conditioning nodes such as LTXVConditioning, ConditioningStableAudio, and primitive string
  nodes), seed (all samplers / RandomNoise), steps, cfg, sampler, scheduler, denoise, checkpoint,
  unet, LoRAs + strength, width / height / batch / frames (latent and image-to-video nodes),
  seconds, fps, input images / audio; outputs (image / audio / video save nodes).
- `WorkflowParam(name, label, type, targets, default, min, max, step, options, multiline, role,
  advanced)` with `ParamType` `INT FLOAT STRING ENUM IMAGE AUDIO SEED BOOL` — render controls from
  this list, in order; `advanced` ones under "More".
- Schema editing (`WorkflowSchema`): `exposable`, `expose`, `rename`, `remove`, `move`, `update`,
  `refreshOptions(workflow, objectInfo)`; each edit bumps `version`.
- `WorkflowValidator.validate(graph, objectInfo)` → `Validation(missingNodes, missingValues,
  message)`; show `message` before running.
- `WorkflowApplier.apply(workflow, values, randomizeSeed, random, uploads)` → `AppliedWorkflow(graph,
  values)`; never mutates the saved graph; clamps numbers, checks enum choices, explains errors.

## Pipelines (`Pipeline.kt`)

`Pipeline(id, name, variables, input, steps)`; `PipelineStep(id, label, engine, output, params
(with {{var}} placeholders), model, backendId, workflowId, inputs = [StepInput(from, role, each)],
repeat, seedMode)`. `PipelineRunner(queue).run(pipeline, projectId, vars, input, seed, onStep)`
→ `PipelineResult(steps)`; independent steps run side by side, a failed step skips its
dependents, cancelling cancels the queued jobs. Built-ins: `BuiltInPipelines.TXT2IMG_UPSCALE`,
`IMAGE_VARIATIONS`, `IMAGE_WITH_AMBIENCE`, `VOICEOVER`.

## Agent tool (`RunWorkflowTool.kt`)

`run_workflow` (`ToolKind.GENERATE`): `{workflow, params?}`; without `params` it describes the
workflow, unknown names list the saved ones. Load with `studioPlugin(runner)` (Android: the
harness root; iOS: through the bridge). `QueueStudioRunner(store, queue, projectId = {
QueueStudioRunner.agentProject(store) }, backendLabel)` is the default runner. The result's meta
is `{"studio": {"type", "assets"}}` plus `gallery` for images.
