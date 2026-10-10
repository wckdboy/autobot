package dev.wckdboy.autobot.core.diffusion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.diffusion.engine.NpuImages
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.npu.LocalNpu
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 4× upscaling on the Hexagon NPU with an installed upscaler model (Models → upscale · npu). */
@Singleton
class Upscaler @Inject constructor(
    @ApplicationContext context: Context,
    private val library: ModelLibrary,
    npu: LocalNpu,
) {
    private val images = NpuImages(npu, File(context.cacheDir, "npu-io"), System::currentTimeMillis)

    suspend fun available(): List<InstalledModel> =
        library.all().filter { it.isReady && it.kind == ModelKind.UPSCALER && it.engine == EngineKind.NPU }

    /** Returns the upscaled image as PNG. [onProgress] reports finished tiles. */
    suspend fun upscale(image: ByteArray, modelId: String? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        val all = available()
        val model = all.firstOrNull { it.id == modelId } ?: all.firstOrNull()
            ?: throw DiffusionException("No upscaler installed — get one in Models → upscale · npu", DiffusionException.Code.UNSUPPORTED)
        return withContext(Dispatchers.IO) { images.upscale(model, image, onProgress) }
    }
}
