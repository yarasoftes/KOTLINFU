package playerGridMovement
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

enum class JourneyPhase {
    INITIAL,
    COLLECTING_FLORA,
    POSITIVE_OUTCOME,
    NEGATIVE_OUTCOME
}

enum class EntityCategory {
    WIZARD,
    FLORA_NODE,
    TREASURE_BOX
}

data class WorldEntitySpec(
    val uid: String,
    val category: EntityCategory,
    val locX: Float,
    val locY: Float,
    val proximityThreshold: Float
)

data class WizardMemoryData(
    val hasEncountered: Boolean,
    val conversationCounter: Int,
    val floraDelivered: Boolean,
    val witnessedNearFlora: Boolean = false,
    val isInactive: Boolean = false,
    val storedX: Float = 3f,
    val storedY: Float = 3f
)

data class EntityStatus(
    val entityUid: String,
    val locX: Float,
    val locY: Float,
    val currentPhase: JourneyPhase,
    val backpack: Map<String, Int>,
    val wizardMemory: WizardMemoryData,
    val activeZone: String?,
    val guidanceMessage: String,
    val treasury: Int
)

fun calculateFloraAmount(state: EntityStatus): Int {
    return state.backpack["greenLeaf"] ?: 0
}

fun computeEuclideanDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val deltaX = ax - bx
    val deltaY = ay - by
    return kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
}

fun generateDefaultEntityState(entityId: String): EntityStatus {
    return if (entityId == "Alpha") {
        EntityStatus(
            "Alpha",
            0f,
            0f,
            JourneyPhase.INITIAL,
            emptyMap(),
            WizardMemoryData(
                true,
                2,
                false
            ),
            null,
            "Приблизься к интересующей точке",
            0
        )
    } else {
        EntityStatus(
            "Beta",
            0f,
            0f,
            JourneyPhase.INITIAL,
            emptyMap(),
            WizardMemoryData(
                false,
                0,
                false
            ),
            null,
            "Приблизься к интересующей точке",
            0
        )
    }
}

data class DialogChoice(
    val choiceId: String,
    val displayLabel: String
)

data class DialogPresentation(
    val speakerId: String,
    val content: String,
    val availableChoices: List<DialogChoice>
)

fun constructWizardConversation(state: EntityStatus): DialogPresentation {
    val leafAmount = calculateFloraAmount(state)
    val mem = state.wizardMemory

    return when (state.currentPhase) {
        JourneyPhase.INITIAL -> {
            val salutation =
                if (!mem.hasEncountered) {
                    "Здравствуй, незнакомец"
                } else {
                    "Снова ты, ${state.entityUid}. Ну что?"
                }
            DialogPresentation(
                "Чародей",
                "$salutation\nМне нужны листья",
                listOf(
                    DialogChoice("agree_task", "Принимаю"),
                    DialogChoice("reject_task", "Отказываюсь")
                )
            )
        }
        JourneyPhase.COLLECTING_FLORA -> {
            if (leafAmount < 4) {
                DialogPresentation(
                    "Чародей",
                    "Этого недостаточно, нужно 4",
                    emptyList()
                )
            } else {
                DialogPresentation(
                    "Чародей",
                    "Благодарю",
                    listOf(
                        DialogChoice("submit_flora", "Передать 4 листа")
                    )
                )
            }
        }
        JourneyPhase.POSITIVE_OUTCOME -> {
            val message =
                if (mem.floraDelivered) {
                    "Готов к работе?"
                } else {
                    "Странно, квест завершён, но я не помню..."
                }
            DialogPresentation(
                "Чародей",
                message,
                emptyList()
            )
        }
        JourneyPhase.NEGATIVE_OUTCOME -> {
            DialogPresentation(
                "Чародей",
                "Разговор окончен",
                emptyList()
            )
        }
    }
}

sealed interface ActionRequest {
    val originId: String
}

data class ShiftPositionRequest(
    override val originId: String,
    val shiftX: Float,
    val shiftY: Float
) : ActionRequest

data class EngageRequest(
    override val originId: String
) : ActionRequest

data class SelectChoiceRequest(
    override val originId: String,
    val selectionCode: String
) : ActionRequest

data class ToggleActorRequest(
    override val originId: String,
    val targetActor: String
) : ActionRequest

data class ResetStateRequest(
    override val originId: String
) : ActionRequest

sealed interface SystemNotification {
    val relatedId: String
}

data class ZoneEntered(
    override val relatedId: String,
    val zoneIdentifier: String
) : SystemNotification

data class ZoneExited(
    override val relatedId: String,
    val zoneIdentifier: String
) : SystemNotification

data class WizardContactMade(
    override val relatedId: String,
    val wizardUid: String
) : SystemNotification

data class FloraCollected(
    override val relatedId: String,
    val nodeUid: String
) : SystemNotification

data class BackpackUpdated(
    override val relatedId: String,
    val itemType: String,
    val updatedQuantity: Int
) : SystemNotification

data class PhaseTransition(
    override val relatedId: String,
    val nextPhase: JourneyPhase
) : SystemNotification

data class WizardMemoryUpdate(
    override val relatedId: String,
    val snapshot: WizardMemoryData
) : SystemNotification

data class BroadcastAnnouncement(
    override val relatedId: String,
    val announcement: String
) : SystemNotification

data class TriumphAchieved(
    override val relatedId: String
) : SystemNotification

class WorldSimulator {
    val placedEntities = mutableListOf(
        WorldEntitySpec(
            "enchanter",
            EntityCategory.WIZARD,
            -3f,
            0f,
            1.7f
        ),
        WorldEntitySpec(
            "flora_patch",
            EntityCategory.FLORA_NODE,
            3f,
            0f,
            1.7f
        )
    )

    fun spawnRewardChest() {
        placedEntities.add(
            WorldEntitySpec(
                "reward_box",
                EntityCategory.TREASURE_BOX,
                1f,
                0f,
                1.7f
            )
        )
    }

    private val _outgoingNotifications = MutableSharedFlow<SystemNotification>(extraBufferCapacity = 64)
    val outgoingNotifications: SharedFlow<SystemNotification> = _outgoingNotifications.asSharedFlow()

    private val _incomingRequests = MutableSharedFlow<ActionRequest>(extraBufferCapacity = 64)
    val incomingRequests: SharedFlow<ActionRequest> = _incomingRequests.asSharedFlow()

    fun attemptEmit(req: ActionRequest): Boolean = _incomingRequests.tryEmit(req)

    private val _actorRegistry = MutableStateFlow(
        mapOf(
            "Beta" to generateDefaultEntityState("Beta"),
            "Alpha" to generateDefaultEntityState("Alpha")
        )
    )
    val actorRegistry: StateFlow<Map<String, EntityStatus>> = _actorRegistry.asStateFlow()

    fun initialize(executionContext: kotlinx.coroutines.CoroutineScope) {
        executionContext.launch {
            incomingRequests.collect { cmd ->
                handleRequest(cmd)
            }
        }
    }

    private fun applyStateUpdate(actorId: String, updatedState: EntityStatus) {
        val registry = _actorRegistry.value.toMutableMap()
        registry[actorId] = updatedState
        _actorRegistry.value = registry.toMap()
    }

    fun retrieveActorState(actorId: String): EntityStatus {
        return _actorRegistry.value[actorId] ?: generateDefaultEntityState(actorId)
    }

    private fun modifyActor(actorId: String, transformation: (EntityStatus) -> EntityStatus) {
        val currentRegistry = _actorRegistry.value
        val currentActor = currentRegistry[actorId] ?: return

        val modifiedActor = transformation(currentActor)

        val updatedRegistry = currentRegistry.toMutableMap()
        updatedRegistry[actorId] = modifiedActor
        _actorRegistry.value = updatedRegistry.toMap()
    }

    private fun findClosestEntity(actor: EntityStatus): WorldEntitySpec? {
        val withinRange = placedEntities.filter { spec ->
            computeEuclideanDistance(actor.locX, actor.locY, spec.locX, spec.locY) <= spec.proximityThreshold
        }

        return withinRange.minByOrNull { spec ->
            computeEuclideanDistance(actor.locX, actor.locY, spec.locX, spec.locY) <= spec.proximityThreshold
        }
    }

    private suspend fun reevaluateActorZone(actorId: String) {
        val actor = retrieveActorState(actorId)
        val closest = findClosestEntity(actor)

        val formerZone = actor.activeZone
        val currentZone = closest?.uid

        if (formerZone == currentZone) {
            val newHint =
                when (currentZone) {
                    "enchanter" -> "Поговори с чародеем"
                    "flora_patch" -> "Собери ресурсы"
                    "reward_box" -> "Осмотри сундук"
                    else -> "Приблизься к интересующей точке"
                }
            modifyActor(actorId) { s -> s.copy(guidanceMessage = newHint) }
            return
        }

        if (formerZone != null) {
            _outgoingNotifications.emit(ZoneExited(actorId, formerZone))
        }

        if (currentZone != null) {
            _outgoingNotifications.emit(ZoneEntered(actorId, currentZone))

            if (currentZone == "flora_patch") {
                modifyActor(actorId) { s ->
                    val mem = s.wizardMemory
                    if (!mem.witnessedNearFlora) {
                        s.copy(wizardMemory = mem.copy(witnessedNearFlora = true))
                    } else s
                }
            }
        }

        val newHint =
            when (currentZone) {
                "enchanter" -> "Поговори с чародеем"
                "flora_patch" -> "Собери ресурсы"
                "reward_box" -> "Осмотри сундук"
                else -> "Приблизься к интересующей точке"
            }
        modifyActor(actorId) { s -> s.copy(guidanceMessage = newHint, activeZone = currentZone) }
    }

    private suspend fun handleRequest(cmd: ActionRequest) {
        when (cmd) {
            is ShiftPositionRequest -> {
                modifyActor(cmd.originId) { s ->
                    s.copy(locX = s.locX + cmd.shiftX, locY = s.locY + cmd.shiftY)
                }
                reevaluateActorZone(cmd.originId)
            }
            is EngageRequest -> {
                val actor = retrieveActorState(cmd.originId)
                val nearby = findClosestEntity(actor)
                val distance = computeEuclideanDistance(actor.locX, actor.locY, nearby.locX, nearby.locY)
                val leafCount = calculateFloraAmount(actor)

                if (nearby == null) {
                    _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Вокруг ничего интересного"))
                    return
                }
                if (distance > nearby.proximityThreshold) {
                    _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Слишком далеко"))
                    return
                }

                when (nearby.category) {
                    EntityCategory.WIZARD -> {
                        val oldMem = actor.wizardMemory
                        val newMem = oldMem.copy(
                            hasEncountered = true,
                            conversationCounter = oldMem.conversationCounter + 1
                        )

                        if (leafCount < 3 && newMem.witnessedNearFlora) {
                            DialogPresentation(
                                "Чародей",
                                "Я заметил тебя у источника",
                                emptyList()
                            )
                        }

                        modifyActor(cmd.originId) { s ->
                            s.copy(wizardMemory = newMem)
                        }

                        _outgoingNotifications.emit(WizardContactMade(cmd.originId, nearby.uid))
                        _outgoingNotifications.emit(WizardMemoryUpdate(cmd.originId, newMem))
                    }

                    EntityCategory.FLORA_NODE -> {
                        if (actor.currentPhase != JourneyPhase.COLLECTING_FLORA) {
                            _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Сейчас это не нужно"))
                            return
                        }

                        val currentCount = calculateFloraAmount(actor)
                        val updatedCount = currentCount + 1
                        val updatedBackpack = actor.backpack + ("greenLeaf" to updatedCount)

                        modifyActor(cmd.originId) { s ->
                            s.copy(backpack = updatedBackpack)
                        }

                        _outgoingNotifications.emit(FloraCollected(cmd.originId, nearby.uid))
                        _outgoingNotifications.emit(BackpackUpdated(cmd.originId, "greenLeaf", updatedCount))
                    }

                    EntityCategory.TREASURE_BOX -> {
                        modifyActor(cmd.originId) { s ->
                            s.copy(treasury = s.treasury + 10)
                        }

                        placedEntities.removeIf { it.uid == "reward_box" }

                        _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Сундук исчез, но ты получил 10 монет"))
                    }
                }
            }
            is SelectChoiceRequest -> {
                val actor = retrieveActorState(cmd.originId)

                if (actor.activeZone != "enchanter") {
                    _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Сначала подойди к чародею"))
                    return
                }

                when (cmd.selectionCode) {
                    "agree_task" -> {
                        if (actor.currentPhase != JourneyPhase.INITIAL) {
                            _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Этот выбор недоступен"))
                            return
                        }

                        modifyActor(cmd.originId) { s ->
                            s.copy(currentPhase = JourneyPhase.COLLECTING_FLORA)
                        }

                        _outgoingNotifications.emit(PhaseTransition(cmd.originId, JourneyPhase.COLLECTING_FLORA))
                        _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Чародей просит 3 листа"))
                    }
                    "submit_flora" -> {
                        if (actor.currentPhase != JourneyPhase.COLLECTING_FLORA) {
                            _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Не время сдавать ресурсы"))
                        }

                        val leaves = calculateFloraAmount(actor)

                        if (leaves < 3) {
                            _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Не хватает листьев"))
                            return
                        }

                        val remainingLeaves = leaves - 3
                        val updatedBackpack =
                            if (remainingLeaves <= 0) actor.backpack - "greenLeaf" else actor.backpack + ("greenLeaf" to remainingLeaves)

                        val updatedMemory = actor.wizardMemory.copy(
                            floraDelivered = true
                        )

                        modifyActor(cmd.originId) { s ->
                            s.copy(
                                backpack = updatedBackpack,
                                treasury = s.treasury + 5,
                                currentPhase = JourneyPhase.POSITIVE_OUTCOME,
                                wizardMemory = updatedMemory
                            )
                        }
                        spawnRewardChest()
                        _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Появился сундук с наградой"))

                        _outgoingNotifications.emit(BackpackUpdated(cmd.originId, "greenLeaf", remainingLeaves))
                        _outgoingNotifications.emit(WizardMemoryUpdate(cmd.originId, updatedMemory))
                        _outgoingNotifications.emit(PhaseTransition(cmd.originId, JourneyPhase.POSITIVE_OUTCOME))
                        _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Чародей доволен, ты получил золото"))

                        _outgoingNotifications.emit(TriumphAchieved(cmd.originId))
                    }
                    else -> {
                        _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Неизвестный выбор"))
                    }
                }
            }
            is ToggleActorRequest -> {
                // Placeholder for actor switching
            }

            is ResetStateRequest -> {
                modifyActor(cmd.originId) { _ -> generateDefaultEntityState(cmd.originId) }
                _outgoingNotifications.emit(BroadcastAnnouncement(cmd.originId, "Состояние сброшено"))
            }
        }
    }
}

class InterfaceViewModel {
    val currentActorStream = MutableStateFlow("Beta")
    val currentActorUi = mutableStateOf("Beta")
    val actorCache = mutableStateOf(generateDefaultEntityState("Beta"))
    val eventHistory = mutableStateOf<List<String>>(emptyList())
    val victoryAchieved = mutableStateOf(false)
}

fun appendToHistory(vm: InterfaceViewModel, entry: String) {
    vm.eventHistory.value = (vm.eventHistory.value + entry).takeLast(20)
}

fun describeBackpack(state: EntityStatus): String {
    return if (state.backpack.isEmpty()) {
        "Инвентарь: пусто"
    } else {
        "Инвентарь:" + state.backpack.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

fun describeCurrentObjective(state: EntityStatus): String {
    val leaves = calculateFloraAmount(state)

    return when (state.currentPhase) {
        JourneyPhase.INITIAL -> "Найди чародея и поговори"
        JourneyPhase.COLLECTING_FLORA -> {
            if (leaves < 3) "Собери 3 листа (сейчас $leaves/3)"
            else "Вернись к чародею и передай листья"
        }
        JourneyPhase.POSITIVE_OUTCOME -> "Приключение завершено успешно"
        JourneyPhase.NEGATIVE_OUTCOME -> "Приключение завершено неудачно"
    }
}

fun describeZone(state: EntityStatus): String {
    return when (state.activeZone) {
        "enchanter" -> "Локация: Башня чародея"
        "flora_patch" -> "Локация: Лесная поляна"
        "reward_box" -> "Локация: Сундук"
        else -> "Без локации"
    }
}

fun describeWizardMemory(mem: WizardMemoryData): String {
    return "Встречался:${mem.hasEncountered} | Бесед: ${mem.conversationCounter} | Доставка: ${mem.floraDelivered}"
}

fun translateNotificationToText(evt: SystemNotification): String {
    return when (evt) {
        is ZoneEntered -> "Вход в зону: ${evt.zoneIdentifier}"
        is ZoneExited -> "Выход из зоны: ${evt.zoneIdentifier}"
        is WizardContactMade -> "Контакт с чародеем: ${evt.wizardUid}"
        is FloraCollected -> "Сбор ресурсов: ${evt.nodeUid}"
        is BackpackUpdated -> "Обновление инвентаря ${evt.itemType} -> ${evt.updatedQuantity}"
        is PhaseTransition -> "Смена этапа: ${evt.nextPhase}"
        is WizardMemoryUpdate -> "Память чародея обновлена: Встречался:${evt.snapshot.hasEncountered} | Бесед: ${evt.snapshot.conversationCounter} | Доставка: ${evt.snapshot.floraDelivered}"
        is BroadcastAnnouncement -> "Система: ${evt.announcement}"
    }
}

fun main() = KoolApplication {
    val interfaceVm = InterfaceViewModel()
    val worldEngine = WorldSimulator()

    addScene {
        defaultOrbitCamera()

        val actorMesh = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val wizardMesh = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        wizardMesh.transform.translate(-3f, 0f, 0f)

        val floraMesh = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        floraMesh.transform.translate(3f, 0f, 0f)

        val treasureMesh = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        treasureMesh.transform.translate(1f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, 1f))
            setColor(Color.YELLOW, 5f)
        }

        worldEngine.initialize(coroutineScope)

        var prevPosX = 0f
        var prevPosY = 0f

        actorMesh.onUpdate {
            val activeId = interfaceVm.currentActorStream.value
            val state = worldEngine.retrieveActorState(activeId)

            val deltaX = state.locX - prevPosX
            val deltaY = state.locY - prevPosY

            actorMesh.transform.translate(deltaX, 0f, deltaY)

            prevPosX = state.locX
            prevPosY = state.locY
        }

        wizardMesh.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        floraMesh.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        treasureMesh.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        interfaceVm.currentActorStream
            .flatMapLatest { pid ->
                worldEngine.outgoingNotifications.filter { it.relatedId == pid }
            }
            .onEach { event ->
                if (event is TriumphAchieved) {
                    interfaceVm.victoryAchieved.value = true
                }
            }
            .launchIn(coroutineScope)

        interfaceVm.currentActorStream
            .flatMapLatest { pid ->
                worldEngine.actorRegistry.map { map ->
                    map[pid] ?: generateDefaultEntityState(pid)
                }
            }
            .onEach { state ->
                interfaceVm.actorCache.value = state
            }
            .launchIn(coroutineScope)

        interfaceVm.currentActorStream
            .flatMapLatest { pid ->
                worldEngine.outgoingNotifications.filter { it.relatedId == pid }
            }
            .map { event ->
                translateNotificationToText(event)
            }
            .onEach { line ->
                appendToHistory(interfaceVm, "[${interfaceVm.currentActorStream.value}] $line")
            }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), (12.dp)))
                .padding(12.dp)
            Column {
                val state = interfaceVm.actorCache.use()
                val dialog = constructWizardConversation(state)

                Text("Персонаж: ${interfaceVm.currentActorStream.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Координаты x=${"%.1f".format(state.locX)} y=${"%.1f".format(state.locY)}") {}
                Text("Этап: ${state.currentPhase}") {
                    modifier.font(sizes.smallText)
                }
                Text(describeCurrentObjective(state)) {
                    modifier.font(sizes.smallText)
                }
                Text(describeBackpack(state)) {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Монеты: ${state.treasury}") {
                    modifier.font(sizes.smallText)
                }
                Text("Подсказка: ${state.guidanceMessage}") {
                    modifier.font(sizes.smallText)
                }
                Text("Память чародея: ${describeWizardMemory(state.wizardMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить героя") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (interfaceVm.currentActorStream.value == "Beta") "Alpha" else "Beta"

                            interfaceVm.currentActorUi.value = newId
                            interfaceVm.currentActorStream.value = newId
                        }
                    }

                    Button("Сброс") {
                        modifier.onClick {
                            worldEngine.attemptEmit(ResetStateRequest(state.entityUid))
                        }
                    }
                }
                Text("Перемещение:") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("←") {
                        modifier.margin(end = 8.dp).onClick {
                            worldEngine.attemptEmit(ShiftPositionRequest(state.entityUid, shiftX = -0.5f, shiftY = 0f))
                        }
                    }
                    Button("→") {
                        modifier.margin(end = 8.dp).onClick {
                            worldEngine.attemptEmit(ShiftPositionRequest(state.entityUid, shiftX = 0.5f, shiftY = 0f))
                        }
                    }
                    Button("↑") {
                        modifier.margin(end = 8.dp).onClick {
                            worldEngine.attemptEmit(ShiftPositionRequest(state.entityUid, shiftX = 0f, shiftY = -0.5f))
                        }
                    }
                    Button("↓") {
                        modifier.margin(end = 8.dp).onClick {
                            worldEngine.attemptEmit(ShiftPositionRequest(state.entityUid, shiftX = 0f, shiftY = 0.5f))
                        }
                    }
                }

                Text("Действие") { modifier.margin(top = sizes.gap) }
                Row {
                    Button("Взаимодействовать") {
                        modifier.margin(end = 8.dp).onClick {
                            worldEngine.attemptEmit(EngageRequest(state.entityUid))
                        }
                    }
                }

                Text(dialog.speakerId) { modifier.margin(top = sizes.gap) }

                Text(dialog.content) { modifier.margin(bottom = sizes.smallGap) }

                if (dialog.availableChoices.isEmpty()) {
                    Text("Нет вариантов ответа") {
                        modifier.margin(top = sizes.gap).font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                } else {
                    Row {
                        for (choice in dialog.availableChoices) {
                            Button(choice.displayLabel) {
                                worldEngine.attemptEmit(
                                    SelectChoiceRequest(state.entityUid, choice.choiceId)
                                )
                            }
                        }
                    }
                }
                Text("История: ") { modifier.margin(top = sizes.gap, bottom = sizes.gap) }

                for (entry in interfaceVm.eventHistory.use()) {
                    Text(entry) { modifier.font(sizes.smallText) }
                }

                if (interfaceVm.victoryAchieved.use()) {
                    addPanelSurface {
                        modifier
                            .fillWidth()
                            .fillHeight()
                            .background(Color(0f, 0f, 0f, 0.8f))
                            .align(AlignmentX.Center, AlignmentY.Center)

                        Column {
                            modifier.align(AlignmentX.Center, AlignmentY.Center)

                            Text("Победа!") {
                                modifier.font(sizes.largeText)
                            }

                            Button("Продолжить") {
                                modifier.margin(top = 16.dp).onClick {
                                    interfaceVm.victoryAchieved.value = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
//1.1 a (пруф https://www.youtube.com/watch?v=eEyMtEHY7kU)
//1.2 d
//1.3 d