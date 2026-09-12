package com.guanyi.mirra.feature.knowledge

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.repository.ImageRepository
import com.guanyi.mirra.data.repository.ImportBatchResult
import com.guanyi.mirra.data.repository.LearningItemRepository
import com.guanyi.mirra.data.repository.NoteRepository
import com.guanyi.mirra.data.repository.TopicRepository
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.domain.TopicSuggestion
import com.guanyi.mirra.data.storage.CameraTarget
import com.guanyi.mirra.domain.RuleBasedNoteTypeSuggester
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModel(
    initialNoteId: String?,
    initialLearningItemId: String?,
    private val notes: NoteRepository,
    private val imageRepository: ImageRepository,
    learningItems: LearningItemRepository,
    private val topicRepository: TopicRepository,
    private val noteTypeSuggester: RuleBasedNoteTypeSuggester = RuleBasedNoteTypeSuggester(),
    newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    val learningItems = learningItems.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    private val noteIdState = MutableStateFlow(initialNoteId)
    val images = noteIdState.flatMapLatest { noteId ->
        if (noteId == null) flowOf(emptyList()) else imageRepository.observeForNote(noteId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val linkedTopics = noteIdState.flatMapLatest { noteId ->
        if (noteId == null) flowOf(emptyList()) else topicRepository.observeForNote(noteId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allTopics = topicRepository.observeAllTopics().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    var topicSuggestions by mutableStateOf<List<TopicSuggestion>>(emptyList())
        private set
    var topicMessage by mutableStateOf<String?>(null)
        private set
    var content by mutableStateOf("")
        private set
    var pageText by mutableStateOf("")
        private set
    var semanticType by mutableStateOf(NoteSemanticType.UNDERSTANDING)
        private set
    var selectedLearningItemId by mutableStateOf(initialLearningItemId)
        private set
    var savedMessage by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var isImageBusy by mutableStateOf(false)
        private set
    var imageMessage by mutableStateOf("")
        private set
    var canDelete by mutableStateOf(initialNoteId != null)
        private set
    var isLoaded by mutableStateOf(initialNoteId == null)
        private set
    val canChooseLearningItem = initialNoteId == null

    private val draftId = initialNoteId ?: newId()
    private var persistedNoteId = initialNoteId
    private var typeManuallySelected = false
    private var saveJob: Job? = null
    private var revision = 0
    private var deleted = false
    private val saveMutex = Mutex()
    private val captionJobs = mutableMapOf<String, Job>()
    private val pendingCaptions = mutableMapOf<String, String>()

    init {
        if (initialNoteId != null) {
            viewModelScope.launch {
                val note = notes.observe(initialNoteId).first()
                if (note == null) {
                    error = "Note 不存在"
                } else {
                    content = note.content
                    pageText = note.pageNumber?.toString().orEmpty()
                    semanticType = note.semanticType
                    selectedLearningItemId = note.learningItemId
                    typeManuallySelected = true
                }
                isLoaded = true
            }
        }
    }

    fun changeContent(value: String) {
        content = value
        if (!typeManuallySelected) semanticType = noteTypeSuggester.suggest(value)
        changed()
    }

    fun changePage(value: String) {
        pageText = value.filter(Char::isDigit)
        changed()
    }

    fun changeType(value: NoteSemanticType) {
        semanticType = value
        typeManuallySelected = true
        changed()
    }

    fun selectLearningItem(id: String) {
        if (!canChooseLearningItem || persistedNoteId != null) return
        selectedLearningItemId = id
        changed()
    }

    fun flushDraft() {
        if (deleted) return
        saveJob?.cancel()
        viewModelScope.launch {
            flushCaptionsNow()
            runCatching { saveDraft(requireValid = false) }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun leave(onLeft: () -> Unit) {
        if (deleted) {
            onLeft()
            return
        }
        saveJob?.cancel()
        if (persistedNoteId == null && content.isBlank()) {
            onLeft()
            return
        }
        viewModelScope.launch {
            runCatching {
                check(!isImageBusy) { "图片正在处理中，请稍候" }
                flushCaptionsNow()
                saveDraft(requireValid = true)
            }
                .onSuccess { onLeft() }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = persistedNoteId ?: return
        saveJob?.cancel()
        captionJobs.values.forEach(Job::cancel)
        viewModelScope.launch {
            isSaving = true
            error = null
            runCatching { notes.delete(id) }
                .onSuccess {
                    deleted = true
                    onDeleted()
                }
                .onFailure { error = it.message ?: "删除失败" }
            isSaving = false
        }
    }

    fun importGallery(uris: List<Uri>) {
        val noteId = persistedNoteId ?: return
        if (isImageBusy) return
        viewModelScope.launch {
            isImageBusy = true
            imageMessage = "正在处理图片…"
            error = null
            runCatching { imageRepository.importFromGallery(noteId, uris) }
                .onSuccess { result ->
                    imageMessage = imageImportMessage(result)
                }
                .onFailure { error = it.message ?: "图片导入失败" }
            isImageBusy = false
        }
    }

    suspend fun createCameraTarget(): CameraTarget? {
        if (persistedNoteId == null || isImageBusy) return null
        isImageBusy = true
        imageMessage = "正在打开相机…"
        return runCatching { imageRepository.createCameraTarget() }
            .onFailure {
                isImageBusy = false
                error = it.message ?: "无法打开相机"
            }
            .getOrNull()
    }

    fun finishCamera(target: CameraTarget, succeeded: Boolean) {
        val noteId = persistedNoteId
        viewModelScope.launch {
            if (!succeeded || noteId == null) {
                runCatching { imageRepository.cancelCameraTarget(target) }
                imageMessage = ""
                isImageBusy = false
                return@launch
            }
            imageMessage = "正在处理照片…"
            runCatching { imageRepository.completeCameraImport(noteId, target) }
                .onSuccess { imageMessage = "照片已添加" }
                .onFailure { error = it.message ?: "照片处理失败" }
            isImageBusy = false
        }
    }

    fun cameraUnavailable(target: CameraTarget) {
        viewModelScope.launch {
            runCatching { imageRepository.cancelCameraTarget(target) }
            error = "此设备没有可用相机应用"
            imageMessage = ""
            isImageBusy = false
        }
    }

    fun changeCaption(imageId: String, value: String) {
        pendingCaptions[imageId] = value
        captionJobs.remove(imageId)?.cancel()
        captionJobs[imageId] = viewModelScope.launch {
            delay(500)
            saveCaption(imageId)
        }
    }

    fun deleteImage(imageId: String) {
        if (isImageBusy) return
        captionJobs.remove(imageId)?.cancel()
        pendingCaptions.remove(imageId)
        viewModelScope.launch {
            isImageBusy = true
            runCatching { imageRepository.deleteImage(imageId) }
                .onSuccess { imageMessage = "图片已删除" }
                .onFailure { error = it.message ?: "图片删除失败" }
            isImageBusy = false
        }
    }

    fun imageFile(localPath: String) = imageRepository.displayFile(localPath)

    fun refreshTopicSuggestions() {
        val noteId = persistedNoteId ?: return
        viewModelScope.launch {
            topicSuggestions = runCatching { topicRepository.suggestions(noteId) }.getOrDefault(emptyList())
        }
    }

    fun createTopicAndLink(name: String) {
        val noteId = persistedNoteId ?: return
        viewModelScope.launch {
            runCatching { topicRepository.createAndLink(noteId, name) }
                .onSuccess { topicMessage = "Topic 已关联"; refreshTopicSuggestions() }
                .onFailure { topicMessage = it.message ?: "Topic 创建失败" }
        }
    }

    fun linkTopic(topicId: String) {
        val noteId = persistedNoteId ?: return
        viewModelScope.launch {
            runCatching { topicRepository.link(noteId, topicId) }
                .onSuccess { topicMessage = "Topic 已关联"; refreshTopicSuggestions() }
                .onFailure { topicMessage = it.message ?: "关联失败" }
        }
    }

    fun unlinkTopic(topicId: String) {
        val noteId = persistedNoteId ?: return
        viewModelScope.launch {
            runCatching { topicRepository.unlink(noteId, topicId) }
                .onSuccess { topicMessage = "已解除关联"; refreshTopicSuggestions() }
                .onFailure { topicMessage = it.message ?: "解除关联失败" }
        }
    }

    private fun changed() {
        revision += 1
        savedMessage = ""
        error = null
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        saveJob?.cancel()
        if (content.isBlank() || selectedLearningItemId == null) return
        saveJob = viewModelScope.launch {
            delay(500)
            runCatching { saveDraft(requireValid = false) }
                .onFailure { error = it.message ?: "笔记保存失败" }
        }
    }

    private suspend fun saveDraft(requireValid: Boolean): Boolean = saveMutex.withLock {
        if (!isLoaded) return@withLock false
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) {
            if (requireValid && persistedNoteId != null) kotlin.error("笔记内容不能为空。如需移除，请删除这条笔记。")
            return@withLock false
        }
        val itemId = selectedLearningItemId
        if (itemId == null) {
            if (requireValid) kotlin.error("请选择学习内容")
            return@withLock false
        }
        val page = pageText.toIntOrNull()
        if (pageText.isNotBlank() && page == null) kotlin.error("页码格式不正确")
        val savingRevision = revision
        isSaving = true
        error = null
        val saved = try {
            if (persistedNoteId == null) {
                notes.createStandalone(itemId, cleanContent, semanticType, page, draftId)
            } else {
                notes.update(checkNotNull(persistedNoteId), cleanContent, semanticType, page)
            }
        } finally {
            isSaving = false
        }
        persistedNoteId = saved.id
        noteIdState.value = saved.id
        canDelete = true
        if (savingRevision == revision) savedMessage = "已自动保存"
        true
    }

    private suspend fun saveCaption(imageId: String) {
        val value = pendingCaptions.remove(imageId) ?: return
        runCatching { imageRepository.updateCaption(imageId, value) }
            .onFailure { error = it.message ?: "Caption 保存失败" }
        captionJobs.remove(imageId)
    }

    private suspend fun flushCaptionsNow() {
        captionJobs.values.forEach(Job::cancel)
        captionJobs.clear()
        pendingCaptions.keys.toList().forEach { saveCaption(it) }
    }
}

internal fun imageImportMessage(result: ImportBatchResult): String = buildString {
    append("已添加 ${result.added.size} 张")
    if (result.failedCount > 0) append("，${result.failedCount} 张处理失败")
    if (result.rejectedCount > 0) append("；单次最多 20 张，已忽略 ${result.rejectedCount} 张")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenImage: (String, String) -> Unit,
    onOpenTopic: (String) -> Unit,
) {
    val learningItems by viewModel.learningItems.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val linkedTopics by viewModel.linkedTopics.collectAsStateWithLifecycle()
    val allTopics by viewModel.allTopics.collectAsStateWithLifecycle()
    var itemMenuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTopics by remember { mutableStateOf(false) }
    var confirmUnlinkTopic by remember { mutableStateOf<TopicEntity?>(null) }
    val selectedItemName = learningItems.firstOrNull { it.id == viewModel.selectedLearningItemId }?.name
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushDraft()
        }
    }
    BackHandler { viewModel.leave(onBack) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (viewModel.canChooseLearningItem) "新建笔记" else "笔记内容", style = MaterialTheme.typography.headlineMedium)
        if (!viewModel.isLoaded) {
            Text("正在读取笔记…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
            return@Column
        }
        if (viewModel.canChooseLearningItem && !viewModel.canDelete) {
            Box {
                OutlinedButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedItemName ?: "选择学习内容")
                }
                DropdownMenu(expanded = itemMenuExpanded, onDismissRequest = { itemMenuExpanded = false }) {
                    learningItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                itemMenuExpanded = false
                                viewModel.selectLearningItem(item.id)
                            },
                        )
                    }
                }
            }
        } else {
            Text("学习内容：${selectedItemName.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoteSemanticType.entries.forEach { type ->
                FilterChip(
                    selected = viewModel.semanticType == type,
                    onClick = { viewModel.changeType(type) },
                    label = { Text(type.displayName) },
                )
            }
        }
        OutlinedTextField(
            value = viewModel.content,
            onValueChange = viewModel::changeContent,
            label = { Text("笔记内容") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (viewModel.canDelete) {
            if (linkedTopics.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    linkedTopics.forEach { topic ->
                        OutlinedButton(onClick = { onOpenTopic(topic.id) }) { Text(topic.name) }
                        TextButton(onClick = { confirmUnlinkTopic = topic }) { Text("解除") }
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.refreshTopicSuggestions(); showTopics = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("关联 Topic") }
        }
        if (viewModel.canDelete) {
            NoteImageSection(
                images = images,
                imageFile = viewModel::imageFile,
                isBusy = viewModel.isImageBusy,
                message = viewModel.imageMessage,
                onGalleryResult = viewModel::importGallery,
                onCreateCameraTarget = viewModel::createCameraTarget,
                onCameraResult = viewModel::finishCamera,
                onCameraUnavailable = viewModel::cameraUnavailable,
                onCaptionChanged = viewModel::changeCaption,
                onDelete = viewModel::deleteImage,
                onOpen = { image -> onOpenImage(image.noteId, image.id) },
            )
        } else if (viewModel.content.isNotBlank()) {
            Text("笔记首次自动保存后即可添加图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = viewModel.pageText,
            onValueChange = viewModel::changePage,
            label = { Text("页码（可选）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (viewModel.isSaving) Text("正在保存…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (viewModel.savedMessage.isNotEmpty()) Text(viewModel.savedMessage, color = MaterialTheme.colorScheme.primary)
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (viewModel.canDelete) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !viewModel.isSaving && !viewModel.isImageBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除笔记") }
        }
        OutlinedButton(onClick = { viewModel.leave(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条笔记？") },
            text = {
                Text(if (images.isEmpty()) "删除后无法恢复。" else "删除后无法恢复，并将同时删除 ${images.size} 张图片。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDeleted)
                    },
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
    if (showTopics) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showTopics = false }) {
            var newTopic by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("关联 Topic", style = MaterialTheme.typography.titleLarge)
                viewModel.topicSuggestions.forEach { suggestion ->
                    TextButton(onClick = { viewModel.linkTopic(suggestion.topic.id) }) { Text("建议：${suggestion.topic.name}") }
                }
                allTopics.forEach { topic ->
                    val linked = linkedTopics.any { it.id == topic.id }
                    OutlinedButton(
                        onClick = {
                            if (linked) {
                                showTopics = false
                                confirmUnlinkTopic = topic
                            } else {
                                viewModel.linkTopic(topic.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (linked) "✓ ${topic.name}（解除）" else topic.name) }
                }
                OutlinedTextField(newTopic, { newTopic = it }, label = { Text("新 Topic") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = { viewModel.createTopicAndLink(newTopic); newTopic = "" },
                    enabled = newTopic.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("创建并关联") }
                viewModel.topicMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    confirmUnlinkTopic?.let { topic ->
        AlertDialog(
            onDismissRequest = { confirmUnlinkTopic = null },
            title = { Text("解除 Topic 关联？") },
            text = { Text("只解除与“${topic.name}”的关联，不会删除 Topic 或笔记。") },
            confirmButton = { TextButton(onClick = { confirmUnlinkTopic = null; viewModel.unlinkTopic(topic.id) }) { Text("确认解除") } },
            dismissButton = { TextButton(onClick = { confirmUnlinkTopic = null }) { Text("取消") } },
        )
    }
}
