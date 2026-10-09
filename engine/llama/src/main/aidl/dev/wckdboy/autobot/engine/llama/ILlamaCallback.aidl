package dev.wckdboy.autobot.engine.llama;

/** Streaming events of one completion. Delivered in order on a binder thread. */
oneway interface ILlamaCallback {
    /** Phase change: "loading", "prefill", "generating". */
    void onStatus(String status);

    /** Text delta: kind 0 = answer content, 1 = reasoning. */
    void onDelta(int kind, String text);

    /** Final JSON result (content, reasoning, tool_calls, usage) or {error:{code,message}}. */
    void onResult(String json);
}
