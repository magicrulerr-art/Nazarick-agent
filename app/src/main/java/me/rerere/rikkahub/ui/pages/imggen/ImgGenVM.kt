package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.runtime.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

/**
 * Pure selection logic backing the gallery orphan purge (#39): given every persisted
 * gen-media row and the images directory, returns the entities whose backing file no
 * longer exists on disk. Resolves each entity's file exactly like [toGeneratedImage] does.
 * Extracted as a top-level function so it's unit-testable without constructing the VM.
 */
internal fun selectOrphanedGenMedia(
    entities: List<GenMediaEntity>,
    imagesDir: File,
): List<GenMediaEntity> =
    entities.filter { entity -> !File(imagesDir, entity.path.removePrefix("images/")).exists() }

/**
 * Makes a model display name safe to use as a single filename component (#39). Model
 * display names can contain path separators (e.g. an OpenRouter id like
 * "google/gemini-2.5-flash-image-preview"), which would otherwise make `File(dir, name)`
 * write into a subdirectory the DB never records.
 */
internal fun sanitizeFilenameComponent(name: String): String =
    name.map { c -> if (c in FILENAME_UNSAFE_CHARS || c.isISOControl()) '_' else c }.joinToString("")

private val FILENAME_UNSAFE_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * v0.8 lever validation problem (owner request 2026-11-10, the full sampling panel).
 * The page maps these to localized strings; the VM stays resource-free by design.
 */
internal enum class ImgGenLeverProblem { STEPS, CFG, SEED }

/** Parsed text levers; a null field means "leave it to the model/engine default". */
internal data class ParsedImgGenLevers(
    val steps: Int?,
    val guidanceScale: Double?,
    val seed: Long?,
)

/**
 * Parse the sheet's three text levers. Blank is always legal (= default). Seed follows the
 * engine convention: blank or anything <= 0 (the screenshot's -1) means random. Steps and CFG
 * are range-checked against the engine's sane envelope so a typo surfaces as a form error
 * instead of a silent coercion or a wasted on-device generation.
 *
 * Returns (problem, parsed); on a problem the parsed value is meaningless. Last valid input
 * wins per field independently — one bad lever does not discard the others.
 */
internal fun parseImgGenLevers(
    stepsText: String,
    cfgText: String,
    seedText: String,
): Pair<ImgGenLeverProblem?, ParsedImgGenLevers> {
    val steps: Int?
    if (stepsText.isBlank()) {
        steps = null
    } else {
        val s = stepsText.trim().toIntOrNull()
        if (s == null || s !in 1..150) return ImgGenLeverProblem.STEPS to ParsedImgGenLevers(null, null, null)
        steps = s
    }
    val cfg: Double?
    if (cfgText.isBlank()) {
        cfg = null
    } else {
        val c = cfgText.trim().toDoubleOrNull()
        if (c == null || c !in 0.0..30.0) return ImgGenLeverProblem.CFG to ParsedImgGenLevers(steps, null, null)
        cfg = c
    }
    val seed: Long?
    if (seedText.isBlank()) {
        seed = null
    } else {
        val s = seedText.trim().toLongOrNull()
        if (s == null) return ImgGenLeverProblem.SEED to ParsedImgGenLevers(steps, cfg, null)
        seed = s
    }
    return null to ParsedImgGenLevers(steps, cfg, seed?.takeIf { it > 0 })
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.SQUARE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    // ---- v0.8 sampling levers (owner request 2026-11-10, the screenshot panel) ------------
    // Every default here reproduces v0.7 exactly: blank texts = model catalog defaults,
    // null sizes = aspect-derived, upscale 1 = off, backend "auto". The levers reach the
    // engine through ImageGenerationParams; providers without such knobs ignore them.

    private val _engineBackend = MutableStateFlow("auto")
    val engineBackend: StateFlow<String> = _engineBackend

    private val _stepsText = MutableStateFlow("")
    val stepsText: StateFlow<String> = _stepsText

    private val _cfgText = MutableStateFlow("")
    val cfgText: StateFlow<String> = _cfgText

    private val _seedText = MutableStateFlow("")
    val seedText: StateFlow<String> = _seedText

    /** null = follow the aspect ratio; explicit px otherwise. */
    private val _widthPx = MutableStateFlow<Int?>(null)
    val widthPx: StateFlow<Int?> = _widthPx

    private val _heightPx = MutableStateFlow<Int?>(null)
    val heightPx: StateFlow<Int?> = _heightPx

    /** 1 = off; 2/4 resize the finished image (see local-dream upscalePng). */
    private val _upscaleFactor = MutableStateFlow(1)
    val upscaleFactor: StateFlow<Int> = _upscaleFactor

    fun updateEngineBackend(backend: String) {
        require(backend == "auto" || backend == "qnn" || backend == "mnn")
        _engineBackend.value = backend
    }

    fun updateStepsText(text: String) { _stepsText.value = text }
    fun updateCfgText(text: String) { _cfgText.value = text }
    fun updateSeedText(text: String) { _seedText.value = text }
    fun updateWidthPx(px: Int?) { _widthPx.value = px }
    fun updateHeightPx(px: Int?) { _heightPx.value = px }
    fun updateUpscaleFactor(factor: Int) { _upscaleFactor.value = factor.coerceIn(1, 4) }

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    init {
        purgeOrphanedGenMedia()
    }

    // One-shot purge of gallery entries whose backing file is missing (#39). Room
    // invalidation refreshes the paging flow automatically, so this needs no extra wiring.
    private fun purgeOrphanedGenMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entities = genMediaRepository.getAllMediaList()
                val orphans = selectOrphanedGenMedia(entities, filesManager.getImagesDir())
                orphans.forEach { genMediaRepository.deleteMedia(it.id) }
                if (orphans.isNotEmpty()) {
                    Log.i(TAG, "Purged ${orphans.size} orphaned gallery entries")
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to purge orphaned gallery entries", e)
            }
        }
    }

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    /** Localized message for a lever validation problem (the page shows [_error] verbatim). */
    private fun leverProblemMessage(problem: ImgGenLeverProblem): String = when (problem) {
        ImgGenLeverProblem.STEPS -> getApplication<Application>().getString(
            R.string.imggen_page_steps_invalid, 1, 150,
        )

        ImgGenLeverProblem.CFG -> getApplication<Application>().getString(
            R.string.imggen_page_cfg_invalid, 0, 30,
        )

        ImgGenLeverProblem.SEED -> getApplication<Application>().getString(
            R.string.imggen_page_seed_invalid,
        )
    }

    fun generateImage() {
        if(prompt.value.isBlank()) return
        val (problem, levers) = parseImgGenLevers(_stepsText.value, _cfgText.value, _seedText.value)
        if (problem != null) {
            _error.value = leverProblemMessage(problem)
            return
        }
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val requestPrompt = _prompt.value
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    // v0.8 levers — nulls and "auto"/1 are exactly what v0.7 sent implicitly.
                    steps = levers.steps,
                    guidanceScale = levers.guidanceScale,
                    seed = levers.seed,
                    width = _widthPx.value,
                    height = _heightPx.value,
                    upscaleFactor = _upscaleFactor.value.takeIf { it > 1 },
                    engineBackend = _engineBackend.value.takeIf { it != "auto" },
                )

                val images = providerManager.getProviderByType(provider)
                    .generateImage(provider, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                )
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .editImage(provider, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        var previewFile: File? = null
        var finalIndex = 0

        images.collect { item ->
            if (item.partial) {
                previewFile?.delete()
                val imageFile = saveImagePreview(
                    item = item,
                    modelName = modelName,
                    index = item.partialImageIndex ?: finalIndex,
                )
                previewFile = imageFile
                _currentGeneratedImages.value = finalImages + GeneratedImage(
                    id = 0,
                    prompt = prompt,
                    filePath = imageFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    model = modelName
                )
            } else {
                previewFile?.delete()
                previewFile = null
                val imageFile = saveImageToStorage(
                    item = item,
                    prompt = prompt,
                    modelName = modelName,
                    index = finalIndex,
                    type = type,
                    sourcePaths = sourcePaths,
                )
                finalImages.add(
                    GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = prompt,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = modelName
                    )
                )
                finalIndex++
                _currentGeneratedImages.value = finalImages.toList()
            }
        }
    }

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val safeModelName = sanitizeFilenameComponent(modelName)
        val imageFile = File(getApplication<Application>().appTempFolder, "imggen_${timestamp}_${safeModelName}_$index.png")
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        val filename = "${timestamp}_${sanitizeFilenameComponent(modelName)}_$index.png"
        val imageFile = File(imagesDir, filename)

        val createdFile = filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)

        // Save to database with relative path
        val relativePath = "images/${imageFile.name}"
        val entity = GenMediaEntity(
            path = relativePath,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        genMediaRepository.insertMedia(entity)

        return createdFile
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    suspend fun deleteImages(images: List<GeneratedImage>): List<GeneratedImage> =
        withContext(Dispatchers.IO) {
            images.filter { image ->
                try {
                    val file = File(image.filePath)
                    check(!file.exists() || file.delete()) { "Failed to delete image file" }
                    genMediaRepository.deleteMedia(image.id)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete image ${image.id}", e)
                    true
                }
            }
        }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
