package dev.wckdboy.autobot.shared.studio

/**
 * What an empty Studio room says, the same on Android and iOS: a title, one sentence and one
 * action. How to set a PC up lives where the PC is added (Workflows), not in every room.
 */
object StudioRoomWords {
    data class Empty(val title: String, val text: String, val action: String?)

    val imageNoEngine = Empty("No image engine yet", "Run one on this phone or on a PC.", "Get a model")
    val audioNoModel = Empty("No audio model yet", "Sound is made on this phone with Stable Audio Open Small (about 600 MB).", "Get the audio model")
    val voiceNoModel = Empty("No voice yet", "Voiceover reads text aloud on this phone with Kokoro.", "Get the voice")
    val videoNoPc = Empty("Video runs on your PC", "Phones cannot run today's open video models; a PC with ComfyUI can.", "Add a ComfyUI PC")
    val videoNoWorkflow = Empty("No video workflow yet", "Import a ComfyUI workflow that saves video.", "Import a workflow")
    val workflowsNoPc = Empty("Workflows run on your PC", "Add your ComfyUI PC by its https address, or 127.0.0.1 through a tunnel.", null)
    val libraryEmpty = Empty("Nothing here yet", "What the Studio makes lands here, encrypted, with the recipe that made it.", null)
    val libraryNoFavourites = Empty("No favourites here", "Star an asset to keep it here.", null)

    /** Every empty room's words, for the one-sentence rule in tests. */
    val all: List<Empty> = listOf(imageNoEngine, audioNoModel, voiceNoModel, videoNoPc, videoNoWorkflow, workflowsNoPc, libraryEmpty, libraryNoFavourites)
}
