package lesson10

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
import de.fabmax.kool.modules.ui2.UiModifier.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

import java.io.File

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String
)

sealed interface GameEvent{
    val playerId: String
}

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTicks: Int,
    val intervalMs: Long
): GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class SaveRequested(
    override val playerId: String
): GameEvent

data class CommandRejected(
    override val playerId: String,
    val reason: String
): GameEvent

data class AttackSpeedBuffApplied(
    override val playerId: String,
    val ticks: Int
): GameEvent



class GameServer{

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg",100,0,0,0L,"START"),
            "Stas" to PlayerSave("Stas",100,0,0,0L,"START")
        )
    )

    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean{
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent){
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change:(PlayerSave)->PlayerSave){

        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer

        _players.value = newMap
    }

    fun getPlayer(playerId: String): PlayerSave{
        return _players.value[playerId]
            ?: PlayerSave(playerId,100,0,0,0L,"START")
    }

}

class DamageSystem(private val server: GameServer){

    fun onEvent(e: GameEvent){

        if(e is DamageDealt){

            server.updatePlayer(e.targetId){
                val newHp = (it.hp - e.amount).coerceAtLeast(0)
                it.copy(hp = newHp)
            }

        }

    }

}

class CooldownSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
){

    private val cooldownJobs = mutableMapOf<String, Job>()

    fun startCooldown(playerId:String,totalMs:Long){

        cooldownJobs[playerId]?.cancel()

        server.updatePlayer(playerId){
            it.copy(attackCooldownMsLeft = totalMs)
        }

        val job = scope.launch{

            val step = 100L

            while(isActive && server.getPlayer(playerId).attackCooldownMsLeft>0){

                delay(step)

                server.updatePlayer(playerId){

                    val left = (it.attackCooldownMsLeft - step)
                        .coerceAtLeast(0)

                    it.copy(attackCooldownMsLeft = left)

                }

            }

        }

        cooldownJobs[playerId] = job
    }

    fun canAttack(playerId:String):Boolean{
        return server.getPlayer(playerId).attackCooldownMsLeft<=0
    }

}

class AttackBuffSystem(
    private val scope: CoroutineScope
){

    val activeBuff = mutableMapOf<String,Boolean>()

    fun onEvent(e:GameEvent){

        if(e is AttackSpeedBuffApplied){

            activeBuff[e.playerId] = true

            scope.launch{

                repeat(e.ticks){
                    delay(1000)
                }

                activeBuff[e.playerId] = false

            }

        }

    }

}

class QuestSystem(
    private val server: GameServer
){

    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun onEvent(e: GameEvent, publish:(GameEvent)->Unit){

        val player = server.getPlayer(e.playerId)

        when(e){

            is ChoiceSelected ->{

                if(player.questState!="OFFERED"){
                    publish(CommandRejected(e.playerId,"Quest not offered"))
                    return
                }

                val newState =
                    if(e.choiceId=="help") "GOOD_END"
                    else "EVIL_END"

                server.updatePlayer(e.playerId){
                    it.copy(questState = newState)
                }

                publish(
                    QuestStateChanged(
                        e.playerId,
                        questId,
                        newState
                    )
                )

            }

            else->{}

        }

    }

}

class SaveSystem{

    private val json = Json{
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(playerId:String):File{

        val dir = File("saves")

        if(!dir.exists()) dir.mkdirs()

        return File(dir,"$playerId.json")
    }

    fun save(player:PlayerSave){

        val text = json.encodeToString(
            PlayerSave.serializer(),
            player
        )

        file(player.playerId).writeText(text)
    }

}

class HudState{

    val activePlayerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)

    val questState = mutableStateOf("START")

    val attackCooldownMsLeft = mutableStateOf(0L)

    val log = mutableStateOf<List<String>>(emptyList())

}

fun hudLog(hud:HudState,line:String){

    hud.log.value =
        (hud.log.value + line).takeLast(20)

}

object Shared{

    var server:GameServer?=null
    var cooldowns:CooldownSystem?=null
    var buff:AttackBuffSystem?=null

}

fun main() = KoolApplication {

    val hud = HudState()

    addScene {

        defaultOrbitCamera()

        addColorMesh {

            generate { cube{ colored() } }

            shader = KslPbrShader{
                color{ vertexColor() }
            }

            onUpdate{
                transform.rotate(
                    45f.deg * Time.deltaT,
                    Vec3f.X_AXIS
                )
            }

        }

        val server = GameServer()

        val cooldowns = CooldownSystem(server,coroutineScope)

        val buff = AttackBuffSystem(coroutineScope)

        Shared.server = server
        Shared.cooldowns = cooldowns
        Shared.buff = buff

        coroutineScope.launch{

            server.events.collect{event->

                buff.onEvent(event)

                if(event is AttackPressed){

                    val cd = Shared.cooldowns ?: return@collect

                    if(!cd.canAttack(event.playerId)){

                        server.tryPublish(
                            CommandRejected(
                                event.playerId,
                                "Cooldown"
                            )
                        )

                        return@collect
                    }

                    val fast =
                        buff.activeBuff[event.playerId]==true

                    val cooldown =
                        if(fast) 700 else 1200

                    cd.startCooldown(event.playerId,cooldown.toLong())

                    server.tryPublish(
                        DamageDealt(
                            event.playerId,
                            "enemy",
                            10
                        )
                    )

                }

            }

        }

    }

    addScene {

        setupUiScene(ClearColorLoad)

        val server = Shared.server ?: return@addScene

        coroutineScope.launch{

            server.players.collect{

                val pid = hud.activePlayerId.value
                val player = it[pid] ?: return@collect

                hud.hp.value = player.hp
                hud.questState.value = player.questState
                hud.attackCooldownMsLeft.value =
                    player.attackCooldownMsLeft

            }

        }

        addPanelSurface {

            modifier
                .align(AlignmentX.Start,AlignmentY.Top)
                .padding(16.dp)

            Text("Player: ${hud.activePlayerId.use()}"){}

            Text("HP: ${hud.hp.use()}"){}

            Text("Cooldown: ${hud.attackCooldownMsLeft.use()}"){}

            Row{

                Button("Switch Player"){

                    modifier.onClick{

                        hud.activePlayerId.value =
                            if(hud.activePlayerId.value=="Oleg")
                                "Stas"
                            else
                                "Oleg"

                    }

                }

                Button("Attack"){

                    modifier.onClick{

                        val pid = hud.activePlayerId.value

                        server.tryPublish(
                            AttackPressed(pid,"enemy")
                        )

                    }

                }

                Button("Attack Buff"){

                    modifier.onClick{

                        val pid = hud.activePlayerId.value

                        server.tryPublish(
                            AttackSpeedBuffApplied(pid,5)
                        )

                    }

                }

            }

        }

    }

}