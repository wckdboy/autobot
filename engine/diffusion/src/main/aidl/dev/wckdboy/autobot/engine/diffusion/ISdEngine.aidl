package dev.wckdboy.autobot.engine.diffusion;

import android.os.Bundle;
import dev.wckdboy.autobot.engine.diffusion.ISdCallback;

interface ISdEngine {
    String systemInfo();

    /** Loads the model bundle in [request] if needed and generates. Keys: see SdRequest. */
    oneway void generate(in Bundle request, ISdCallback callback);

    oneway void cancel();

    oneway void unload();
}
