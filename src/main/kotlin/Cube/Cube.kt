package Cube

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.geometry.IndexedVertexList
import jdk.jfr.DataAmount
import jdk.jfr.StackTrace

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

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
import kotlinx.coroutines.processNextEventInCurrentThread
import javax.accessibility.AccessibleValue
import kotlin.math.sqrt

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    var x: Float,
    var z: Float,
    val interactRadius: Float,
    val trajectory: List<Pair<Float, Float>>
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean
)

@kotlinx.serialization.Serializable
data class CubeData(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val creationTime: Long
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val hp: Int,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int,
    val blowCount: Int = 0,
    val createdCubes: List<CubeData> = emptyList()
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            100,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            100,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val option: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "О привет"
                }else{
                    "снова ты... я тебя знаю, ты ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Хочешь помочь - принеси травку",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "травы не будет, гони товар")
                )
            )
        }

        QuestState.WAIT_HERB ->{
            if (herbs < 3){
                DialogueView(
                    "Алхимик",
                    "Недостаточно, надо $herbs/3 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "найс, прет как белый, давай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 3 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо спасибо"
                }else{
                    "Ты завершил квест, но нпс все забыл..."
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "ты проиграл бетмен",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdMoveNpc(
    override val playerId: String,
    val objId: String
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdCreateCube(
    override val playerId: String
): GameCommand

data class CmdSaveCubes(
    override val playerId: String
): GameCommand

data class CmdLoadCubes(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

data class CubeCreated(
    override val playerId: String,
    val cubeId: Int,
    val position: Vec3f
): GameEvent

data class CubesSaved(
    override val playerId: String,
    val count: Int,
    val filePath: String
): GameEvent

data class CubesLoaded(
    override val playerId: String,
    val count: Int
): GameEvent

class CubeSpawner {
    private var cubeCounter = 0
    private val activeCubes = mutableListOf<CubeSpawnData>()

    data class CubeSpawnData(
        val id: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        val creationTime: Long,
        val node: Node
    )

    fun createCube(
        playerX: Float,
        playerZ: Float,
        scene: Scene
    ): CubeSpawnData {
        cubeCounter++
        val offsetX = (kotlin.random.Random.nextFloat() - 0.5f) * 2f
        val offsetZ = (kotlin.random.Random.nextFloat() - 0.5f) * 2f

        val cubeNode = scene.addColorMesh {
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

        cubeNode.transform.translate(
            playerX + offsetX,
            0.5f,
            playerZ + offsetZ
        )

        val spawnData = CubeSpawnData(
            id = cubeCounter,
            x = playerX + offsetX,
            y = 0.5f,
            z = playerZ + offsetZ,
            creationTime = System.currentTimeMillis(),
            node = cubeNode
        )

        activeCubes.add(spawnData)
        return spawnData
    }

    fun getCubeCount(): Int = activeCubes.size

    fun getAllCubes(): List<CubeData> = activeCubes.map {
        CubeData(
            id = it.id,
            x = it.x,
            y = it.y,
            z = it.z,
            creationTime = it.creationTime
        )
    }

    fun clearAll() {
        activeCubes.clear()
    }
}

class GameServer {
    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f,
            listOf(
                -3f to -1f,
                -3f to -2f,
                -4f to -2f,
                -5f to -2f,
                -5f to -1f,
                -5f to 0f,
                -4f to 0f,
                -3f to 0f
            )
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f,
            emptyList()
        ),
        WorldObjectDef(
            "treasure_box",
            WorldObjectType.CHEST,
            5f,
            0f,
            2f,
            emptyList()
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()
    val cubeSpawner = CubeSpawner()

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    private fun setPlayerData(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z)
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when (newAreaId){
                    "alchemist" -> "Подойди и нажми на алхимика"
                    "herb_source" -> "собери траву"
                    "treasure_box" -> "открыть сундук"
                    else -> "Подойди к одной из локаций"
                }
        }

        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null){
            _events.emit(EnteredArea(playerId, newAreaId))
        }

        val newHint =
            when (newAreaId){
                "alchemist" -> "Подойди и нажми на алхимика"
                "herb_source" -> "собери траву"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                hintText = newHint,
                currentAreaId = newAreaId
            )
        }
    }

    private var debounce: Boolean = true
    private suspend fun startMoveNpc(id: String) {
        val index = worldObjects.indexOfFirst { it.id == id }
        val trajectory = worldObjects.find { it.id == id }?.trajectory ?: emptyList()
        coroutineScope {
            debounce = true
            while (true) {
                for (pos in trajectory){
                    if (debounce != true) break
                    val obj = worldObjects[index]
                    worldObjects[index] = obj.copy(x = pos.second, z = pos.first)
                    delay(1000)
                }
            }
        }
    }

    private fun randomChance(probability: Float): Boolean {
        return kotlin.random.Random.nextFloat() < probability
    }

    private suspend fun processCommand(cmd: GameCommand){
        when(cmd){
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdMoveNpc -> {
                startMoveNpc(cmd.objId)
            }

            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val player = getPlayerData(cmd.playerId)
                        val distance = distance2D(player.posX, player.posZ, obj.x, obj.z)
                        if (distance >= 2f){
                            _events.emit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                            return
                        }

                        if (player.alchemistMemory.sawPlayerNearSource == true){
                            _events.emit(ServerMessage(cmd.playerId, "Вижу, ты хотя бы дошёл до места, где растёт трава, ты ее принёс?"))
                        }

                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        debounce = false
                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава сейчас тебе не нужна - сначала возьми квест"))
                            return
                        }

                        if(randomChance(0.5f)){
                            _events.emit(ServerMessage(cmd.playerId, "Промах"))
                            return
                        }

                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            sawPlayerNearSource = true
                        )
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)
                        updatePlayer(cmd.playerId){p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST -> {
                        val newGold = player.gold + 1

                        updatePlayer(cmd.playerId){p ->
                            p.copy(gold = newGold)
                        }
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if (player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId){p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал золото"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }

            }
            is CmdSwitchActivePlayer -> {

            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
            }

            is CmdCreateCube -> {
                val player = getPlayerData(cmd.playerId)
                val newCount = player.blowCount + 1

                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        blowCount = newCount,
                        createdCubes = p.createdCubes + CubeData(
                            id = newCount,
                            x = p.posX,
                            y = 0.5f,
                            z = p.posZ,
                            creationTime = System.currentTimeMillis()
                        )
                    )
                }

                _events.emit(CubeCreated(
                    cmd.playerId,
                    cubeId = newCount,
                    position = Vec3f(player.posX, 0.5f, player.posZ)
                ))

                _events.emit(ServerMessage(
                    cmd.playerId,
                    "Куб #$newCount создан! Всего кубов: $newCount"
                ))
            }

            is CmdSaveCubes -> {
                val player = getPlayerData(cmd.playerId)
                val savePath = "cubes_${cmd.playerId}.json"

                val jsonData = buildString {
                    appendLine("{")
                    appendLine("  \"playerId\": \"${cmd.playerId}\",")
                    appendLine("  \"saveTime\": ${System.currentTimeMillis()},")
                    appendLine("  \"cubes\": [")
                    player.createdCubes.forEachIndexed { index, cube ->
                        appendLine("    {")
                        appendLine("      \"id\": ${cube.id},")
                        appendLine("      \"x\": ${cube.x},")
                        appendLine("      \"y\": ${cube.y},")
                        appendLine("      \"z\": ${cube.z},")
                        appendLine("      \"creationTime\": ${cube.creationTime}")
                        appendLine("    }${if (index < player.createdCubes.size - 1) "," else ""}")
                    }
                    appendLine("  ]")
                    appendLine("}")
                }

                println("Saving cubes to $savePath:\n$jsonData")

                _events.emit(CubesSaved(
                    cmd.playerId,
                    count = player.createdCubes.size,
                    filePath = savePath
                ))

                _events.emit(ServerMessage(
                    cmd.playerId,
                    "Сохранено ${player.createdCubes.size} кубов в $savePath"
                ))
            }

            is CmdLoadCubes -> {
                val player = getPlayerData(cmd.playerId)
                val loadedCount = player.createdCubes.size

                _events.emit(CubesLoaded(
                    cmd.playerId,
                    count = loadedCount
                ))

                _events.emit(ServerMessage(
                    cmd.playerId,
                    "Загружено $loadedCount кубов"
                ))
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePLayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState) : String{
    return if(player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player: PlayerState) : String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 травы"
        }
        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Зона: Алхимик"
        "herb_source" -> "Зона источника травы"
        "treasure_box" -> "Зона сундука"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился = ${memory.hasMet}, Сколько раз поговорил = ${memory.timesTalked}, отдал траву = ${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, отдал траву = ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
        is CubeCreated -> "CubeCreated #${e.cubeId}"
        is CubesSaved -> "CubesSaved count=${e.count}"
        is CubesLoaded -> "CubesLoaded count=${e.count}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(3f,0f,0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        herbNode.transform.translate(3f,0f,0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map{ event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePLayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePLayerIdUi.use()}"){ modifier.margin(bottom = sizes.gap) }
                Text("Позиция: x=${"%.1f".format(player.posX)} z=${"%.1f".format(player.posZ)}"){}
                Text("Quest State: ${player.questState}"){ modifier.font(sizes.smallText) }
                Text(currentObjective(player)){ modifier.font(sizes.smallText) }
                Text(formatInventory(player)){ modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }
                Text("Gold: ${player.gold}"){ modifier.font(sizes.smallText) }
                Text("Hint: ${player.hintText}"){ modifier.font(sizes.smallText) }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}"){ modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }
                Text("Создано кубов (blow): ${player.blowCount}"){ modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }

                Row {
                    modifier.margin(bottom = sizes.smallGap)

                    val hpPercent = player.hp.coerceIn(0, 100)
                    val hpColor =
                        if (hpPercent > 60) Color(0.1f, 0.75f, 0.25f, 0.9f)
                        else if (hpPercent > 30) Color(0.9f, 0.7f, 0.15f, 0.9f)
                        else Color(0.9f, 0.15f, 0.1f, 0.9f)

                    Text("HP: $hpPercent%"){
                        modifier.font(sizes.smallText).margin(end = 8.dp)
                    }

                    Box {
                        modifier
                            .width(120.dp)
                            .height(12.dp)
                            .background(RoundRectBackground(Color(0.05f, 0.05f, 0.05f, 0.7f), 6.dp))

                        Box {
                            modifier
                                .width((hpPercent * 120 / 100).dp)
                                .height(12.dp)
                                .background(RoundRectBackground(hpColor, 6.dp))
                        }
                    }

                }
                Row {
                    Button("Сменить игрока"){
                        modifier.margin(end = 8.dp).onClick{
                            val newId = if(hud.activePLayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePLayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                    Button("Сбросить игрока"){
                        modifier.onClick{
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }

                Text("Движение в мире:"){ modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                        }
                    }
                    Button("Право"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперед"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                        }
                    }
                    Button("Назад"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                        }
                    }
                }
                Text("Взаимодействия:"){ modifier.margin(top = sizes.gap) }

                Row {
                    Button("Потрогать ближайшего"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                    Button("[E] Создать куб"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdCreateCube(player.playerId))
                            val activeId = hud.activePlayerIdFlow.value
                            val currentPlayer = server.getPlayerData(activeId)
                            server.cubeSpawner.createCube(currentPlayer.posX, currentPlayer.posZ, this@addScene)
                        }
                    }
                }

                Text("Кубы:"){ modifier.margin(top = sizes.gap) }

                Row {
                    Button("Сохранить кубы"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdSaveCubes(player.playerId))
                        }
                    }
                    Button("Загрузить кубы"){
                        modifier.onClick{
                            server.trySend(CmdLoadCubes(player.playerId))
                        }
                    }
                }

                Text(dialogue.npcId){ modifier.margin(top = sizes.gap) }
                Text(dialogue.text){ modifier.margin(bottom = sizes.smallGap) }

                if(dialogue.option.isEmpty()){
                    Text("Нет доступных вариантов ответа"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row{
                        for (option in dialogue.option){
                            Button(option.text){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(
                                        CmdChooseDialogueOption(
                                            player.playerId,
                                            option.id
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Лог: "){modifier.margin(top = sizes.gap)}

                for(line in hud.log.use()){
                    Text(line){ modifier.font(sizes.smallText) }
                }
            }
        }
    }
}