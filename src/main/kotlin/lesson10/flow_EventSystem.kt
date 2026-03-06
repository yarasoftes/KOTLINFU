package lesson10

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String,
    val attackSpeedBuffTicksLeft: Int = 0
)

sealed interface GameEvent {
    val playerId: String
}

data class CommandRejected(
    override val playerId: String,
    val reason: String
) : GameEvent

data class AttackPressed(
    override val playerId: String,
    val targetId: String
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
) : GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTick: Int,
    val intervalMs: Long
) : GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

data class SaveRequested(
    override val playerId: String
) : GameEvent

data class AttackSpeedBuffApplied(
    override val playerId: String,
    val ticks: Int
) : GameEvent

class GameServer {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 0, 0L, "START", 0),
            "Stas" to PlayerSave("Stas", 100, 0, 0, 0L, "START", 0)
        )
    )
    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean {
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent) {
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)
        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap
    }

    fun getPlayer(playerId: String): PlayerSave {
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0, 0, 0L, "START", 0)
    }
}

class DamageSystem(
    private val server: GameServer
) {
    fun onEvent(e: GameEvent) {
        if (e is DamageDealt) {
            server.updatePlayer(e.targetId) { player ->
                val newHp = (player.hp - e.amount).coerceAtLeast(0)
                player.copy(hp = newHp)
            }
        }
    }
}

class CooldownSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val cooldownJobs = mutableMapOf<String, Job>()

    private val BASE_COOLDOWN_MS = 1200L
    private val BUFFED_COOLDOWN_MS = 700L

    fun getCurrentCooldown(playerId: String): Long {
        val player = server.getPlayer(playerId)
        return if (player.attackSpeedBuffTicksLeft > 0) {
            BUFFED_COOLDOWN_MS
        } else {
            BASE_COOLDOWN_MS
        }
    }

    fun startCooldown(playerId: String) {
        cooldownJobs[playerId]?.cancel()

        val totalMs = getCurrentCooldown(playerId)

        server.updatePlayer(playerId) { player -> player.copy(attackCooldownMsLeft = totalMs) }

        val job = scope.launch {
            val step = 100L

            while (isActive && server.getPlayer(playerId).attackCooldownMsLeft > 0L) {
                delay(step)

                server.updatePlayer(playerId) { player ->
                    val left = (player.attackCooldownMsLeft - step).coerceAtLeast(0L)
                    player.copy(attackCooldownMsLeft = left)
                }
            }
            cooldownJobs.remove(playerId)
        }

        cooldownJobs[playerId] = job
    }

    fun canAttack(playerId: String): Boolean {
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }
}

class AttackSpeedBuffSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val buffJobs = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent) {
        if (e is AttackSpeedBuffApplied) {
            server.updatePlayer(e.playerId) { player ->
                player.copy(attackSpeedBuffTicksLeft = player.attackSpeedBuffTicksLeft + e.ticks)
            }

            if (buffJobs.containsKey(e.playerId)) {
                return
            }

            val job = scope.launch {
                val tickRate = 1000L

                while (isActive) {
                    delay(tickRate)

                    val player = server.getPlayer(e.playerId)
                    if (player.attackSpeedBuffTicksLeft <= 0) {
                        break
                    }

                    server.updatePlayer(e.playerId) { player ->
                        player.copy(attackSpeedBuffTicksLeft = player.attackSpeedBuffTicksLeft - 1)
                    }
                }
                buffJobs.remove(e.playerId)
            }

            buffJobs[e.playerId] = job
        }
    }
}

class PoisonSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val poisonJobs = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent, publishDamage: (DamageDealt) -> Unit) {
        if (e is PoisonApplied) {
            server.updatePlayer(e.playerId) { player ->
                player.copy(poisonTicksLeft = player.poisonTicksLeft + e.ticks)
            }

            val job = scope.launch {
                while (isActive && server.getPlayer(e.playerId).poisonTicksLeft > 0) {
                    delay(e.intervalMs)

                    server.updatePlayer(e.playerId) { player ->
                        player.copy(poisonTicksLeft = (player.poisonTicksLeft - 1).coerceAtLeast(0))
                    }

                    publishDamage(DamageDealt(e.playerId, "self", e.damagePerTick))
                }
                poisonJobs.remove(e.playerId)
            }
            poisonJobs[e.playerId] = job
        }
    }
}

class QuestSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun onEvent(e: GameEvent, publish: (GameEvent) -> Unit) {
        val player = server.getPlayer(e.playerId)

        when (e) {
            is TalkedToNpc -> {
                if (e.npcId != npcId) return

                if (player.questState == "START") {
                    server.updatePlayer(e.playerId) { it.copy(questState = "OFFERED") }
                    publish(QuestStateChanged(e.playerId, questId, "OFFERED"))
                }
            }
            is ChoiceSelected -> {
                if (e.npcId != npcId) return

                if (player.questState == "OFFERED") {
                    val newState =
                        if (e.choiceId == "help") "GOOD_END"
                        else "EVIL_END"

                    server.updatePlayer(e.playerId) { it.copy(questState = newState) }
                    publish(QuestStateChanged(e.playerId, questId, newState))
                } else if (e.choiceId == "help") {
                    publish(CommandRejected(e.playerId, "67"))
                }
            }
            else -> {}
        }
    }
}

class SaveSystem {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(playerId: String): File {
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(playerSave: PlayerSave) {
        val text = json.encodeToString(PlayerSave.serializer(), playerSave)
        file(playerSave.playerId).writeText(text)
    }

    fun load(playerId: String): PlayerSave? {
        val file = file(playerId)
        if (!file.exists()) return null

        val text = file.readText()
        return try {
            json.decodeFromString(PlayerSave.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }
}

class HudState {
    val activePlayerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val questState = mutableStateOf("START")
    val attackCooldownMsLeft = mutableStateOf(0L)
    val attackSpeedBuffTicksLeft = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String) {
    hud.log.value = (hud.log.value + line).takeLast(20)
}

object Shared {
    var server: GameServer? = null
    var saver: SaveSystem? = null
    var cooldowns: CooldownSystem? = null
    var quests: QuestSystem? = null
    var poison: PoisonSystem? = null
    var damage: DamageSystem? = null
    var attackSpeedBuff: AttackSpeedBuffSystem? = null
}

fun main() = KoolApplication {
    val hud = HudState()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        val server = GameServer()
        val saver = SaveSystem()
        val damage = DamageSystem(server)
        val cooldowns = CooldownSystem(server, coroutineScope)
        val poison = PoisonSystem(server, coroutineScope)
        val quests = QuestSystem(server, coroutineScope)
        val attackSpeedBuff = AttackSpeedBuffSystem(server, coroutineScope)

        Shared.server = server
        Shared.saver = saver
        Shared.damage = damage
        Shared.cooldowns = cooldowns
        Shared.poison = poison
        Shared.quests = quests
        Shared.attackSpeedBuff = attackSpeedBuff

        coroutineScope.launch {
            server.events.collect { event ->
                damage.onEvent(event)
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                poison.onEvent(event) { dmg ->
                    if (!server.tryPublish(dmg)) {
                        coroutineScope.launch { server.publish(dmg) }
                    }
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                quests.onEvent(event) { newEvent ->
                    if (!server.tryPublish(newEvent)) {
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                attackSpeedBuff.onEvent(event)
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                if (event is AttackPressed) {
                    if (cooldowns.canAttack(event.playerId)) {
                        val damageAmount = 10
                        server.tryPublish(DamageDealt(event.targetId, event.playerId, damageAmount))
                        cooldowns.startCooldown(event.playerId)
                    } else {
                        server.tryPublish(CommandRejected(
                            event.playerId,
                            "Атака невозможна. Время кулдауна: ${server.getPlayer(event.playerId).attackCooldownMsLeft}мс осталось"
                        ))
                    }
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                if (event is SaveRequested) {
                    val snapshot = server.getPlayer(event.playerId)
                    saver.save(snapshot)
                }
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        val server = Shared.server

        if (server != null) {
            coroutineScope.launch {
                server.events.collect { event ->
                    val line = when (event) {
                        is AttackPressed -> "${event.playerId} пытается атаковать ${event.targetId}"
                        is DamageDealt -> "${event.targetId} получил ${event.amount} урона от ${event.playerId}"
                        is PoisonApplied -> "На ${event.playerId} наложен яд на ${event.ticks} тиков"
                        is TalkedToNpc -> "${event.playerId} начал разговор с ${event.npcId}"
                        is ChoiceSelected -> "${event.playerId} выбрал ${event.choiceId}"
                        is SaveRequested -> "Запрос на сохранение"
                        is QuestStateChanged -> "${event.playerId} перешел на новый этап квеста ${event.newState}"
                        is CommandRejected -> " ОТКАЗ: ${event.playerId} - ${event.reason}"
                        is AttackSpeedBuffApplied -> "${event.playerId} получил бафф скорости атаки на ${event.ticks} сек"
                        else -> "Неизвестное событие"
                    }

                    hudLog(hud, line)
                }
            }

            coroutineScope.launch {
                server.players.collect { playersMap ->
                    val pid = hud.activePlayerId.value
                    val player = playersMap[pid] ?: return@collect

                    hud.hp.value = player.hp
                    hud.gold.value = player.gold
                    hud.poisonTicksLeft.value = player.poisonTicksLeft
                    hud.questState.value = player.questState
                    hud.attackCooldownMsLeft.value = player.attackCooldownMsLeft
                    hud.attackSpeedBuffTicksLeft.value = player.attackSpeedBuffTicksLeft
                }
            }
        }


        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.8f), 14.dp))
                .padding(16.dp)
                .width(450.dp)

            Column {
                Row {
                    Text("Player: ${hud.activePlayerId.use()}") {
                    }
                    Button("Switch Player") {
                        modifier
                            .width(100.dp)
                            .onClick {
                                val currentPlayer = hud.activePlayerId.value
                                val newPlayer = if (currentPlayer == "Oleg") "Stas" else "Oleg"
                                hud.activePlayerId.value = newPlayer
                                hudLog(hud, "Переключено на $newPlayer")
                            }
                    }
                }

                Box {
                    modifier
                        .height(1.dp)
                        .margin(vertical = 8.dp)
                }

                Column {
                    Row {
                        Text("HP:") { modifier.width(100.dp) }
                        Text("${hud.hp.use()}/100") {
                        }
                    }
                    Row {
                        Text("Gold:") { modifier.width(100.dp) }
                    }
                    Row {
                        Text("Яд:") { modifier.width(100.dp) }
                    }
                    Row {
                        Text("Квест:") { modifier.width(100.dp) }
                    }
                    Row {
                        Text("Кулдаун:") { modifier.width(100.dp) }
                        Text("${hud.attackCooldownMsLeft.use()} ms") {
                        }
                    }
                    Row {
                        Text("Бафф скорости:") { modifier.width(100.dp) }
                        Text("${hud.attackSpeedBuffTicksLeft.use()} сек") {
                        }
                    }
                }

                Box {
                    modifier
                        .height(1.dp)
                        .margin(vertical = 8.dp)
                }

                Text("ДЕЙСТВИЯ") { modifier.margin(bottom = 4.dp) }

                Row {
                    Button("Атаковать") {
                        modifier
                            .margin(end = 4.dp)
                            .onClick {
                                val playerId = hud.activePlayerId.value
                                val targetId = if (playerId == "Oleg") "Stas" else "Oleg"
                                Shared.server?.tryPublish(AttackPressed(playerId, targetId))
                                hudLog(hud, "Попытка атаки: $playerId -> $targetId")
                            }
                    }

                    Button("Бафф (5 сек)") {
                        modifier
                            .margin(start = 4.dp)
                            .onClick {
                                val playerId = hud.activePlayerId.value
                                Shared.server?.tryPublish(AttackSpeedBuffApplied(playerId, 5))
                                hudLog(hud, "Запрошен бафф скорости для $playerId")
                            }
                    }
                }

                Row {
                    Button("Яд (5 тиков)") {
                        modifier
                            .margin(end = 4.dp, top = 4.dp)
                            .onClick {
                                Shared.server?.tryPublish(PoisonApplied(hud.activePlayerId.value, 5, 2, 1000L))
                            }
                    }

                    Button("Сохранить") {
                        modifier
                            .margin(start = 4.dp, top = 4.dp)
                            .onClick {
                                val playerId = hud.activePlayerId.value
                                Shared.server?.tryPublish(SaveRequested(playerId))
                                hudLog(hud, "Запрос сохранения для $playerId")
                            }
                    }
                }

                Box {
                    modifier
                        .height(1.dp)
                        .margin(vertical = 8.dp)
                }

                Text("КВЕСТ") { modifier.margin(bottom = 4.dp) }

                when (hud.questState.use()) {
                    "START" -> {
                        Button("Говорить с алхимиком") {
                            modifier
                                .margin(vertical = 4.dp)
                                .onClick {
                                    Shared.server?.tryPublish(TalkedToNpc(hud.activePlayerId.value, "alchemist"))
                                }
                        }
                    }
                    "OFFERED" -> {
                        Row {
                            Button("Помочь") {
                                modifier
                                    .margin(end = 4.dp)
                                    .onClick {
                                        Shared.server?.tryPublish(ChoiceSelected(hud.activePlayerId.value, "alchemist", "help"))
                                    }
                            }
                            Button("Драться") {
                                modifier
                                    .margin(start = 4.dp)
                                    .onClick {
                                        Shared.server?.tryPublish(ChoiceSelected(hud.activePlayerId.value, "alchemist", "evil"))
                                    }
                            }
                        }
                    }
                    "GOOD_END" -> {
                    }
                    "EVIL_END" -> {
                    }
                }

                Box {
                    modifier
                        .height(1.dp)
                        .margin(vertical = 8.dp)
                }

                Text("ЛОГИ (последние 20)") { modifier.margin(bottom = 4.dp) }

                Box {
                    modifier
                        .height(200.dp)
                        .padding(8.dp)

                    Column {
                        val lines = hud.log.use()
                        if (lines.isEmpty()) {
                            Text("Нет событий...") { modifier }
                        } else {
                            for (line in lines) {
                                Text(line) {
                                    modifier
                                        .margin(bottom = 2.dp)
                                }
                            }
                        }
                    }
                }

                if (hud.attackCooldownMsLeft.use() > 0) {
                    Text("Кулдаун активен: ${hud.attackCooldownMsLeft.use()}ms") {
                        modifier
                            .margin(top = 8.dp)
                    }
                }

                if (hud.attackSpeedBuffTicksLeft.use() > 0) {
                    Text("Бафф скорости активен: ${hud.attackSpeedBuffTicksLeft.use()} сек (кулдаун 700ms)") {
                        modifier
                            .margin(top = 4.dp)
                    }
                }
            }
        }
    }
}