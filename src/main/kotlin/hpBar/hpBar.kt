package hpBar

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.util.Color
import kotlinx.coroutines.*

// Flow корутины
import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

data class PlayerState(
    val playerId: String,
    val hp: Int,
)

sealed interface GameCommand { val playerId: String }

data class CmdTakeDmg(
    override val playerId: String,
    val count: Int
) : GameCommand

data class CmdLog(
    override val playerId: String,
    val event: String
) : GameCommand

private val _players = MutableStateFlow(
    mapOf("p1" to PlayerState("p1", 100))
)
val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

private val _cmdFlow = MutableSharedFlow<GameCommand>()
val cmdFlow: SharedFlow<GameCommand> = _cmdFlow.asSharedFlow()

private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
    val old = _players.value[playerId] ?: return
    val newMap = _players.value.toMutableMap()
    newMap[playerId] = change(old)
    _players.value = newMap
}

private suspend fun processCommand(cmd: GameCommand) {
    when (cmd) {
        is CmdTakeDmg -> {
            updatePlayer(cmd.playerId) { p ->
                val newHp = (p.hp - cmd.count).coerceAtLeast(0)
                p.copy(hp = newHp)
            }
            println("HP изменилось: ${cmd.playerId}")
        }

        is CmdLog -> {
            println("[LOG] ${cmd.event}")
        }
    }
}

fun main() = KoolApplication {
    val hpState = mutableStateOf(100)
    val isDead = mutableStateOf(false)

    val scope = CoroutineScope(Dispatchers.Default)
    scope.launch {
        cmdFlow.collect { processCommand(it) }
    }

    players.onEach { map ->
        val hp = map["p1"]!!.hp
        hpState.set(hp)
        isDead.set(hp <= 0)
    }.launchIn(scope)

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .padding(12.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 12.dp))

            Column {
                Button("дать по роже") {
                    if (isDead.use()) {
                        modifier.background(RoundRectBackground(Color(0.3f, 0.3f, 0.3f, 1f), 8.dp))
                    }

                    modifier.onClick {
                        if (isDead.use()) return@onClick

                        scope.launch {
                            _cmdFlow.emit(CmdTakeDmg("p1", 10))
                            _cmdFlow.emit(CmdLog("p1", "DealDmgBtn pressed"))
                        }
                    }
                }
            }
        }

        addPanelSurface {
            modifier
                .align(AlignmentX.End, AlignmentY.Bottom)
                .margin(16.dp)
                .padding(12.dp)
                .width(300.dp)
                .height(40.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 12.dp))

            val hpState = remember { mutableStateOf(100) }

            players.onEach { map ->
                hpState.set(map["p1"]!!.hp)
            }.launchIn(scope)

            Box {
                modifier
                    .width((hpState.use() * 3).dp)
                    .height(24.dp)
                    .background(RoundRectBackground(Color.RED, 8.dp))
            }

            if (hpState.use() == 0) {
                addPanelSurface {
                    Text("ЕБАТЬ ТЫ ЛОХ ХАХАХАХХАХАХАХАХАХ") {
                        modifier
                            .align(AlignmentX.Center, AlignmentY.Center)
                            .textColor(Color.WHITE)
                    }
                }
            }
        }
    }
}