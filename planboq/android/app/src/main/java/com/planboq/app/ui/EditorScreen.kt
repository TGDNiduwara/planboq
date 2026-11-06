package com.planboq.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.planboq.app.PlanBoqApp
import com.planboq.app.Routes
import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.OpeningType
import com.planboq.app.data.geometry.GeometrySnap
import com.planboq.app.data.geometry.Layer
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.PolygonMath
import com.planboq.app.data.geometry.Room
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.domain.CalibrateScaleUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class EditorMode { SELECT, ADD_LINE, CALIBRATE, VERIFY, TAG_DOOR, TAG_WINDOW }

/** Holds editor state and applies document mutations with undo + persistence. */
class EditorViewModel(private val app: PlanBoqApp) : ViewModel() {

    private val _document = MutableStateFlow<PlanDocument?>(null)
    val document: StateFlow<PlanDocument?> = _document

    private val _currentLayerId = MutableStateFlow("")
    val currentLayerId: StateFlow<String> = _currentLayerId

    private val _openingTypes = MutableStateFlow(BoqSettings.defaultOpeningTypes())
    val openingTypes: StateFlow<List<OpeningType>> = _openingTypes

    private val undoStack = ArrayDeque<PlanDocument>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    private var loadedId: String? = null

    suspend fun load(id: String): Boolean {
        val doc = app.documentStore.load(id) ?: return false
        _document.value = doc
        _currentLayerId.value =
            doc.layers.firstOrNull { it.id == _currentLayerId.value }?.id
                ?: PlanDocument.defaultLayerId(doc.layers)
        _openingTypes.value = app.boqRepository.load(id)?.settings?.openingTypes
            ?: BoqSettings.defaultOpeningTypes()
        loadedId = id
        detectRoomsIfNeeded()
        return true
    }

    fun updateProjectInfo(
        name: String, client: String, location: String, floorName: String, notes: String,
        preparedBy: String = "", checkedBy: String = "", revision: String = "",
    ) {
        val doc = _document.value ?: return
        pushUndo()
        commit(
            doc.copy(
                name = name.ifBlank { doc.name },
                client = client,
                location = location,
                floorName = floorName,
                notes = notes,
                preparedBy = preparedBy,
                checkedBy = checkedBy,
                revision = revision,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun updateRoom(updated: Room) {
        val doc = _document.value ?: return
        pushUndo()
        commit(doc.copyWithTouch(rooms = doc.rooms.map { if (it.id == updated.id) updated else it }))
    }

    fun deleteRoom(id: String) {
        val doc = _document.value ?: return
        pushUndo()
        commit(doc.copyWithTouch(rooms = doc.rooms.filterNot { it.id == id }))
    }

    fun setLayerThickness(layerId: String, thicknessMm: Double?) {
        val doc = _document.value ?: return
        pushUndo()
        commit(doc.copyWithTouch(layers = doc.layers.map {
            if (it.id == layerId) it.copy(thicknessMm = thicknessMm) else it
        }))
    }

    /** Two-tap door/window tagging: adds an opening segment on the kind's layer. */
    fun addOpening(a: Vec2, b: Vec2, kind: LayerKind) {
        val doc = _document.value ?: return
        pushUndo()
        val existingLayer = doc.layers.firstOrNull { it.kind == kind }
        val layer = existingLayer ?: com.planboq.app.data.geometry.Layer(
            name = if (kind == LayerKind.DOOR) "Doors" else "Windows",
            kind = kind,
            colorArgb = if (kind == LayerKind.DOOR) 0xFF0B7A3B else 0xFF0B5CD7,
        )
        val typeId = _openingTypes.value.firstOrNull { it.kind == kind }?.id
        val segment = Segment(
            a = snapToNearby(doc, a),
            b = snapToNearby(doc, b),
            layerId = layer.id,
            openingTypeId = typeId,
        )
        commit(
            doc.copyWithTouch(
                layers = if (existingLayer == null) doc.layers + layer else doc.layers,
                segments = doc.segments + segment,
            ),
        )
        _currentLayerId.value = layer.id
    }

    /**
     * Rooms are detected once the scale is known (DXF: at import; PDF/photo:
     * after calibration). Runs in the background and persists the polygons.
     */
    fun detectRoomsIfNeeded() {
        val doc = _document.value ?: return
        if (doc.rooms.isNotEmpty() || !doc.scale.calibrated) return
        viewModelScope.launch {
            val rooms = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                app.imageProcessor.ensureOpenCv()
                app.roomDetector.detect(doc)
            }
            val current = _document.value
            if (rooms.isNotEmpty() && current != null && current.rooms.isEmpty()) {
                commit(current.copyWithTouch(rooms = rooms))
            }
        }
    }

    private fun pushUndo() {
        val doc = _document.value ?: return
        undoStack.addLast(doc)
        while (undoStack.size > 20) undoStack.removeFirst()
    }

    private fun commit(newDoc: PlanDocument) {
        _document.value = newDoc
        val id = loadedId ?: return
        viewModelScope.launch {
            app.documentStore.save(newDoc)
            app.database.projectDao().upsert(
                com.planboq.app.data.repo.ProjectEntity(
                    id = newDoc.id,
                    name = newDoc.name,
                    sourceType = newDoc.sourceType.name,
                    createdAt = newDoc.createdAt,
                    updatedAt = newDoc.updatedAt,
                ),
            )
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        _document.value = prev
        loadedId?.let { viewModelScope.launch { app.documentStore.save(prev) } }
    }

    fun addSegment(a: Vec2, b: Vec2) {
        val doc = _document.value ?: return
        pushUndo()
        val snapped = GeometrySnap.snapToHV(Segment(a = snapToNearby(doc, a), b = snapToNearby(doc, b), layerId = _currentLayerId.value))
        commit(doc.copyWithTouch(segments = doc.segments + snapped))
    }

    fun translateSegment(id: String, delta: Vec2) {
        val doc = _document.value ?: return
        val idx = doc.segments.indexOfFirst { it.id == id }
        if (idx < 0) return
        pushUndo()
        val seg = doc.segments[idx]
        commit(doc.copyWithTouch(segments = doc.segments.toMutableList().also {
            it[idx] = seg.copy(a = seg.a + delta, b = seg.b + delta)
        }))
    }

    fun moveSegmentEndpoint(id: String, start: Boolean, to: Vec2) {
        val doc = _document.value ?: return
        val idx = doc.segments.indexOfFirst { it.id == id }
        if (idx < 0) return
        pushUndo()
        val seg = doc.segments[idx]
        val target = snapToNearby(doc, to)
        commit(doc.copyWithTouch(segments = doc.segments.toMutableList().also {
            it[idx] = if (start) seg.copy(a = target) else seg.copy(b = target)
        }))
    }

    fun deleteSegment(id: String) {
        val doc = _document.value ?: return
        pushUndo()
        commit(doc.copyWithTouch(segments = doc.segments.filterNot { it.id == id }))
    }

    fun calibrate(p1: Vec2, p2: Vec2, knownLengthMm: Double) {
        val doc = _document.value ?: return
        pushUndo()
        val scale = try {
            CalibrateScaleUseCase.calibrate(doc, p1, p2, knownLengthMm)
        } catch (t: IllegalArgumentException) {
            return
        }
        commit(doc.copy(scale = scale, updatedAt = System.currentTimeMillis()))
        detectRoomsIfNeeded()
    }

    fun changeLayerKind(layerId: String, kind: LayerKind) {
        val doc = _document.value ?: return
        pushUndo()
        commit(doc.copyWithTouch(layers = doc.layers.map {
            if (it.id == layerId) it.copy(kind = kind) else it
        }))
    }

    fun addLayer() {
        val doc = _document.value ?: return
        pushUndo()
        val layer = com.planboq.app.data.geometry.Layer(
            name = "Layer ${doc.layers.size + 1}",
            colorArgb = 0xFF7B1FA2,
        )
        commit(doc.copyWithTouch(layers = doc.layers + layer))
        _currentLayerId.value = layer.id
    }

    fun setCurrentLayer(id: String) {
        _currentLayerId.value = id
    }

    private fun snapToNearby(doc: PlanDocument, p: Vec2, tol: Double = 5.0): Vec2 {
        var best: Vec2? = null
        var bestD = tol
        for (seg in doc.segments) {
            val da = seg.a.distanceTo(p)
            if (da < bestD) { bestD = da; best = seg.a }
            val db = seg.b.distanceTo(p)
            if (db < bestD) { bestD = db; best = seg.b }
        }
        return best ?: p
    }
}

/**
 * Vector plan editor: pan/zoom canvas, select/move/delete/add lines,
 * layer assignment, scale calibration with verification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(nav: NavController, projectId: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val vm = remember(projectId) { EditorViewModel(app) }
    val doc by vm.document.collectAsState()
    val currentLayerId by vm.currentLayerId.collectAsState()

    LaunchedEffect(projectId) {
        if (!vm.load(projectId)) {
            snackbar.showSnackbar("Project not found")
            nav.popBackStack()
        }
    }

    var viewScale by remember { mutableStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    var fitted by remember { mutableStateOf(false) }

    var mode by remember { mutableStateOf(EditorMode.SELECT) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedRoomId by remember { mutableStateOf<String?>(null) }
    var editingRoom by remember { mutableStateOf<Room?>(null) }
    var pendingPoints by remember { mutableStateOf(listOf<Vec2>()) }
    var showLayers by remember { mutableStateOf(false) }
    var showRooms by remember { mutableStateOf(false) }
    var showProjectInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var thicknessLayer by remember { mutableStateOf<Layer?>(null) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showOverlay by remember(doc?.id) { mutableStateOf(true) }
    val textMeasurer = rememberTextMeasurer()
    val background by produceState<Bitmap?>(null, doc?.id, doc?.backgroundImagePath) {
        val d = doc
        value = d?.backgroundImagePath?.let { app.documentStore.loadBackground(d.id, it) }
    }

    fun toPlan(screen: Offset): Vec2 = Vec2(
        ((screen.x - viewOffset.x) / viewScale).toDouble(),
        ((screen.y - viewOffset.y) / viewScale).toDouble(),
    )

    fun toScreen(p: Vec2): Offset = Offset(
        p.x.toFloat() * viewScale + viewOffset.x,
        p.y.toFloat() * viewScale + viewOffset.y,
    )

    LaunchedEffect(doc, canvasSize) {
        val d = doc ?: return@LaunchedEffect
        if (!fitted && canvasSize != Offset.Zero) {
            val b = d.bounds()
            if (b != null) {
                val w = (b.second.x - b.first.x).toFloat().coerceAtLeast(1f)
                val h = (b.second.y - b.first.y).toFloat().coerceAtLeast(1f)
                viewScale = minOf(canvasSize.x / w, canvasSize.y / h) * 0.9f
                viewOffset = Offset(
                    canvasSize.x / 2f - (b.first.x.toFloat() + w / 2f) * viewScale,
                    canvasSize.y / 2f - (b.first.y.toFloat() + h / 2f) * viewScale,
                )
            }
            fitted = true
        }
    }

    fun finishPoints() {
        when (mode) {
            EditorMode.ADD_LINE -> {
                if (pendingPoints.size == 2) {
                    vm.addSegment(pendingPoints[0], pendingPoints[1])
                    pendingPoints = emptyList()
                }
            }
            EditorMode.CALIBRATE -> if (pendingPoints.size == 2) showScaleDialog = true
            EditorMode.VERIFY -> if (pendingPoints.size == 2) showVerifyDialog = true
            EditorMode.TAG_DOOR -> if (pendingPoints.size == 2) {
                vm.addOpening(pendingPoints[0], pendingPoints[1], LayerKind.DOOR)
                pendingPoints = emptyList()
            }
            EditorMode.TAG_WINDOW -> if (pendingPoints.size == 2) {
                vm.addOpening(pendingPoints[0], pendingPoints[1], LayerKind.WINDOW)
                pendingPoints = emptyList()
            }
            EditorMode.SELECT -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc?.name ?: "Editor", style = MaterialTheme.typography.titleMedium)
                        Text(
                            doc?.let {
                                "${it.segments.size} lines · ${it.rooms.size} rooms · scale ${it.scale.label}"
                            } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (background != null) {
                        IconButton(onClick = { showOverlay = !showOverlay }) {
                            Icon(
                                if (showOverlay) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle photo overlay",
                            )
                        }
                    }
                    IconButton(onClick = { doc?.let { nav.navigate(Routes.boq(it.id)) } }) {
                        Icon(Icons.Default.RequestQuote, contentDescription = "BOQ")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Undo") },
                            enabled = vm.canUndo,
                            onClick = { menuOpen = false; vm.undo() },
                        )
                        DropdownMenuItem(
                            text = { Text("Layers") },
                            onClick = { menuOpen = false; showLayers = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Rooms") },
                            onClick = { menuOpen = false; showRooms = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Project info") },
                            onClick = { menuOpen = false; showProjectInfo = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Export plan") },
                            onClick = {
                                menuOpen = false
                                doc?.let { nav.navigate(Routes.export(it.id)) }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = Offset(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (viewScale * zoom).coerceIn(0.02f, 80f)
                            viewOffset = Offset(
                                centroid.x - (centroid.x - viewOffset.x) * (newScale / viewScale),
                                centroid.y - (centroid.y - viewOffset.y) * (newScale / viewScale),
                            )
                            viewScale = newScale
                            viewOffset += pan
                        }
                    }
                    .pointerInput(mode) {
                        detectTapGestures(
                            onTap = { pos ->
                                val d = doc ?: return@detectTapGestures
                                val plan = toPlan(pos)
                                when (mode) {
                                    EditorMode.SELECT -> {
                                        val tol = 12f / viewScale
                                        val hit = d.segments.minByOrNull { seg ->
                                            pointToSegmentDistance(plan, seg.a, seg.b)
                                        }?.takeIf { seg ->
                                            pointToSegmentDistance(plan, seg.a, seg.b) < tol
                                        }
                                        selectedId = hit?.id
                                        if (hit == null) {
                                            val room = d.rooms.firstOrNull { PolygonMath.contains(it.points, plan) }
                                            if (room != null) {
                                                selectedRoomId = room.id
                                                editingRoom = room
                                            } else {
                                                selectedRoomId = null
                                            }
                                        } else {
                                            selectedRoomId = null
                                        }
                                    }
                                    else -> {
                                        pendingPoints = pendingPoints + plan
                                        if (pendingPoints.size == 2) finishPoints()
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(selectedId, doc?.segments) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                val id = selectedId ?: return@detectDragGestures
                                val d = doc ?: return@detectDragGestures
                                val seg = d.segments.firstOrNull { it.id == id } ?: return@detectDragGestures
                                val aScreen = toScreen(seg.a)
                                val bScreen = toScreen(seg.b)
                                val pos = change.position
                                val grabRadius = 30f
                                val nearA = hypot(pos.x - aScreen.x, pos.y - aScreen.y) < grabRadius
                                val nearB = hypot(pos.x - bScreen.x, pos.y - bScreen.y) < grabRadius
                                val delta = Vec2((amount.x / viewScale).toDouble(), (amount.y / viewScale).toDouble())
                                when {
                                    nearA -> vm.moveSegmentEndpoint(id, start = true, to = seg.a + delta)
                                    nearB -> vm.moveSegmentEndpoint(id, start = false, to = seg.b + delta)
                                    else -> vm.translateSegment(id, delta)
                                }
                            },
                        )
                    },
            ) {
                drawPlan(
                    doc = doc,
                    selectedId = selectedId,
                    selectedRoomId = selectedRoomId,
                    toScreen = ::toScreen,
                    pending = pendingPoints,
                    accent = Color(0xFFFFB300),
                    background = background,
                    overlayAlpha = if (showOverlay) 0.30f else 0f,
                    textMeasurer = textMeasurer,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { mode = EditorMode.SELECT; pendingPoints = emptyList(); selectedRoomId = null },
                        enabled = mode != EditorMode.SELECT,
                        modifier = Modifier.weight(1f),
                    ) { Text("Select") }
                    FilledTonalButton(
                        onClick = { mode = EditorMode.ADD_LINE; pendingPoints = emptyList(); selectedId = null },
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Add, contentDescription = null); Text("Line") }
                    FilledTonalButton(
                        onClick = { mode = EditorMode.CALIBRATE; pendingPoints = emptyList(); selectedId = null },
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Straighten, contentDescription = null); Text("Scale") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { mode = EditorMode.TAG_DOOR; pendingPoints = emptyList(); selectedId = null },
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.DoorFront, contentDescription = null); Text("Door") }
                    FilledTonalButton(
                        onClick = { mode = EditorMode.TAG_WINDOW; pendingPoints = emptyList(); selectedId = null },
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Window, contentDescription = null); Text("Window") }
                    IconButton(
                        onClick = { selectedId?.let { vm.deleteSegment(it); selectedId = null } },
                        enabled = selectedId != null,
                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete selected line") }
                }
            }

            val hint = when (mode) {
                EditorMode.ADD_LINE -> "Tap 2 points to add a line"
                EditorMode.CALIBRATE -> "Tap the 2 ends of a known dimension"
                EditorMode.VERIFY -> "Tap a second known dimension to verify"
                EditorMode.TAG_DOOR -> "Tap 2 points across the door opening"
                EditorMode.TAG_WINDOW -> "Tap 2 points across the window opening"
                EditorMode.SELECT -> null
            }
            hint?.let {
                AssistChip(
                    onClick = { mode = EditorMode.SELECT; pendingPoints = emptyList() },
                    label = { Text(it) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }
            if (showOverlay && background != null && mode == EditorMode.SELECT) {
                AssistChip(
                    onClick = { showOverlay = false },
                    label = { Text("Compare lines with the corrected image") },
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }

            if (showLayers) {
                doc?.let {
                    LayersSheet(
                        document = it,
                        currentLayerId = currentLayerId,
                        onDismiss = { showLayers = false },
                        onSelectCurrent = { id -> vm.setCurrentLayer(id) },
                        onChangeKind = { layerId, kind -> vm.changeLayerKind(layerId, kind) },
                        onAddLayer = { vm.addLayer() },
                        onEditThickness = { layer -> thicknessLayer = layer },
                    )
                }
            }

            if (showRooms) {
                doc?.let {
                    RoomsSheet(
                        document = it,
                        onDismiss = { showRooms = false },
                        onSelect = { room -> showRooms = false; editingRoom = room },
                    )
                }
            }

            editingRoom?.let { room ->
                RoomEditorSheet(
                    room = room,
                    scaleRatio = doc?.scale?.realMmPerPlanUnit ?: 1.0,
                    calibrated = doc?.scale?.calibrated == true,
                    onDismiss = { editingRoom = null },
                    onSave = { updated -> vm.updateRoom(updated); editingRoom = null },
                    onDelete = { vm.deleteRoom(room.id); editingRoom = null; selectedRoomId = null },
                )
            }

            if (showProjectInfo) {
                doc?.let {
                    ProjectInfoDialog(
                        document = it,
                        onDismiss = { showProjectInfo = false },
                        onSave = { name, client, location, floor, notes, prepared, checked, rev ->
                            vm.updateProjectInfo(name, client, location, floor, notes, prepared, checked, rev)
                            showProjectInfo = false
                        },
                    )
                }
            }

            thicknessLayer?.let { layer ->
                NumberInputDialog(
                    title = "Wall thickness",
                    label = "Thickness (mm) - leave blank for default",
                    initial = layer.thicknessMm?.toString() ?: "",
                    allowBlank = true,
                    onDismiss = { thicknessLayer = null },
                    onConfirm = { value ->
                        vm.setLayerThickness(layer.id, value)
                        thicknessLayer = null
                    },
                )
            }

            if (showScaleDialog && pendingPoints.size == 2) {
                ScaleDialog(
                    title = "Known length",
                    label = "Real length between the two tapped points (mm)",
                    onDismiss = { showScaleDialog = false; pendingPoints = emptyList(); mode = EditorMode.SELECT },
                    onConfirm = { mm ->
                        vm.calibrate(pendingPoints[0], pendingPoints[1], mm)
                        showScaleDialog = false
                        pendingPoints = emptyList()
                        mode = EditorMode.VERIFY
                        scope.launch {
                            snackbar.showSnackbar("Scale calibrated - now VERIFY with a second dimension.")
                        }
                    },
                )
            }
            if (showVerifyDialog && pendingPoints.size == 2) {
                ScaleDialog(
                    title = "Verify scale",
                    label = "Second known length (mm)",
                    onDismiss = { showVerifyDialog = false; pendingPoints = emptyList(); mode = EditorMode.SELECT },
                    onConfirm = { mm ->
                        val d = doc
                        val v = if (d != null) {
                            CalibrateScaleUseCase.verify(d.scale, pendingPoints[0], pendingPoints[1], mm)
                        } else null
                        showVerifyDialog = false
                        pendingPoints = emptyList()
                        mode = EditorMode.SELECT
                        scope.launch {
                            when {
                                v == null -> snackbar.showSnackbar("Could not verify")
                                v.pass -> snackbar.showSnackbar("Verified: %.1f%% deviation - OK".format(v.errorPct))
                                else -> snackbar.showSnackbar("WARNING: %.1f%% deviation - recheck your scale!".format(v.errorPct))
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun pointToSegmentDistance(p: Vec2, a: Vec2, b: Vec2): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val apx = p.x - a.x
    val apy = p.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq < 1e-12) return a.distanceTo(p)
    val t = ((apx * abx + apy * aby) / lenSq).coerceIn(0.0, 1.0)
    val cx = a.x + abx * t
    val cy = a.y + aby * t
    return hypot(p.x - cx, p.y - cy)
}

private fun DrawScope.drawPlan(
    doc: PlanDocument?,
    selectedId: String?,
    selectedRoomId: String?,
    toScreen: (Vec2) -> Offset,
    pending: List<Vec2>,
    accent: Color,
    background: Bitmap?,
    overlayAlpha: Float,
    textMeasurer: TextMeasurer,
) {
    doc ?: return

    // Review underlay: the corrected photo, mapped 1:1 to plan units.
    if (background != null && overlayAlpha > 0f) {
        val topLeft = toScreen(Vec2(0.0, 0.0))
        val unitToScreen = toScreen(Vec2(1.0, 0.0)).x - topLeft.x
        if (unitToScreen > 0f) {
            drawImage(
                image = background.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(background.width, background.height),
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(
                    (background.width * unitToScreen).roundToInt(),
                    (background.height * unitToScreen).roundToInt(),
                ),
                alpha = overlayAlpha,
            )
        }
    }

    // Rooms (detected floor areas) with live m² labels.
    doc.rooms.forEach { room ->
        if (room.points.size >= 3) {
            val path = Path()
            val first = toScreen(room.points.first())
            path.moveTo(first.x, first.y)
            room.points.drop(1).forEach {
                val p = toScreen(it)
                path.lineTo(p.x, p.y)
            }
            path.close()
            val selected = room.id == selectedRoomId
            val base = if (room.excluded) Color(0xFF9E9E9E) else accent
            drawPath(path, color = base.copy(alpha = if (selected) 0.18f else 0.08f))
            drawPath(
                path,
                color = base.copy(alpha = if (selected) 0.9f else 0.30f),
                style = Stroke(width = if (selected) 2.5f else 1.2f),
            )

            val center = com.planboq.app.data.geometry.PolygonMath.centroid(room.points)
            val area = if (doc.scale.calibrated) room.areaM2(doc.scale.realMmPerPlanUnit) else null
            val baseName = if (room.excluded) "${room.name} (excluded)" else room.name
            val label = if (area != null) "%s · %.1f m²".format(baseName, area) else baseName
            // measure explicitly: the String drawText overload produced invalid
            // constraints on the Compose version we build against
            val layout = textMeasurer.measure(
                text = androidx.compose.ui.text.AnnotatedString(label),
                style = TextStyle(color = accent.copy(alpha = 0.9f), fontSize = 11.sp),
            )
            drawText(layout, topLeft = toScreen(center) + Offset(8f, -6f))
        }
    }

    for (seg in doc.segments) {
        val layer = doc.layerById(seg.layerId) ?: continue
        if (!layer.visible) continue
        val selected = seg.id == selectedId
        val color = if (selected) accent else Color(layer.colorArgb)
        drawLine(
            color = color,
            start = toScreen(seg.a),
            end = toScreen(seg.b),
            strokeWidth = if (selected) 4f else 1.8f,
            cap = StrokeCap.Round,
        )
    }
    for (poly in doc.polylines) {
        val layer = doc.layerById(poly.layerId) ?: continue
        if (!layer.visible) continue
        val pts = if (poly.closed && poly.points.size > 2) poly.points + poly.points.first() else poly.points
        for (i in 0 until pts.size - 1) {
            drawLine(
                color = Color(layer.colorArgb),
                start = toScreen(pts[i]),
                end = toScreen(pts[i + 1]),
                strokeWidth = 1.2f,
            )
        }
    }
    pending.forEach { p ->
        drawCircle(accent, radius = 7f, center = toScreen(p), style = Stroke(width = 3f))
    }
}
