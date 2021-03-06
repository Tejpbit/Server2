package net.zomis.games.server2.ais

import klog.KLoggers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zomis.core.events.EventSystem
import net.zomis.games.Map2D
import net.zomis.games.Position
import net.zomis.games.Transformation
import net.zomis.games.dsl.PlayerIndex
import net.zomis.games.dsl.Point
import net.zomis.games.dsl.impl.GameImpl
import net.zomis.games.dsl.index
import net.zomis.games.server2.ClientJsonMessage
import net.zomis.games.server2.games.*
import net.zomis.games.server2.getTextOrDefault
import net.zomis.tttultimate.TTPlayer
import net.zomis.tttultimate.games.TTController

class TTTQLearn(val games: GameSystem) {
    val gameType = "DSL-TTT"

    val logger = KLoggers.logger(this)

    val actionPossible: ActionPossible<TTController> = { tt, action ->
        val pos = actionToPosition(tt, action)
        tt.isAllowedPlay(tt.game.getSub(pos.x, pos.y)!!)
    }

    fun newLearner(qStore: QStore<String>): MyQLearning<TTController, String> {
        return newLearner(9, qStore)
    }

    fun newLearner(controller: TTController, qStore: QStore<String>): MyQLearning<TTController, String> {
        return newLearner(controller.game.sizeX * controller.game.sizeY, qStore)
    }

    private fun normalizeTransformation(controller: TTController): Transformation {
        return Map2D(controller.game.sizeX, controller.game.sizeY, {x, y ->
            controller.game.getSub(x, y)!!.wonBy
        }).standardizedTransformation {
            it.ordinal
        }
    }

    private fun newLearner(maxActions: Int, qStore: QStore<String>): MyQLearning<TTController, String> {
        val stateToString: (TTController) -> String = { g ->
            val transformation = normalizeTransformation(g)
            val sizeX = g.game.sizeX
            val sizeY = g.game.sizeY
            val str = StringBuilder()
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    val p = Position(x, y, sizeX, sizeY).transform(transformation)
                    val sub = g.game.getSub(p.x, p.y)!!
                    str.append(if (sub.wonBy.isExactlyOnePlayer) sub.wonBy.name else "_")
                }
                str.append('-')
            }
            str.toString()
        }
        val learn = MyQLearning(maxActions, stateToString, actionPossible, this::stateActionString, qStore)
        // learn.setLearningRate(-0.01); // This leads to bad player moves. Like XOX-OXO-_X_ instead of XOX-OXO-X__
        learn.discountFactor = -0.9
        learn.learningRate = 1.0
        learn.randomMoveProbability = 0.05
        return learn
    }

    private fun stateActionString(environment: TTController, state: String, action: Int): String {
        val transformation = normalizeTransformation(environment)
        // Transform action
        val point = actionToPosition(environment, action)
        val resultingActionPoint = point.transform(transformation)
        val resultingActionInt = positionToAction(environment, resultingActionPoint)
        return state + resultingActionInt
    }

    private fun positionToAction(environment: TTController, position: Position): Int {
        val columns = environment.game.sizeX
        return position.y * columns + position.x
    }

    private fun actionToPosition(environment: TTController, action: Int): Position {
        val columns = environment.game.sizeX
        val x = action % columns
        val y = action / columns
        return Position(x, y, environment.game.sizeX, environment.game.sizeY)
    }

    fun isDraw(tt: TTController): Boolean {
        for (yy in 0 until tt.game.sizeY) {
            for (xx in 0 until tt.game.sizeX) {
                val sub = tt.game.getSub(xx, yy)
                if (!sub!!.isWon) {
                    return false
                }
            }
        }
        return true
    }

    fun observeReward(game: GameImpl<TTController>, action: Int, myIndex: PlayerIndex): MyQLearning.Rewarded<TTController> {
        val tt = game.model
        val player = if (myIndex == 0) TTPlayer.X else TTPlayer.O

        // Observe reward
        var reward = if (tt.isGameOver && tt.wonBy.isExactlyOnePlayer) {
            (if (tt.wonBy.`is`(player)) 1 else -1).toDouble()
        } else {
            -0.01
        }
        if (isDraw(tt)) {
            reward = 0.0
        }
        return MyQLearning.Rewarded(tt, reward)
    }

    private val mutex = Mutex()
    private val awaitingResults = mutableMapOf<Pair<ServerGame, PlayerIndex>, QAwaitingReward<*>>()
    private val learn = this.newLearner(QStoreMap())

    fun setup(events: EventSystem) {
        events.listen("register ServerAIs for DSL Game", GameTypeRegisterEvent::class, { it.gameType == gameType }, { event ->
            registerAI(events)
        })
    }

    fun registerAI(events: EventSystem) {
        learn.randomMoveProbability = 0.0

        val serverAI = ServerAI(gameType, "#AI_QLearn_$gameType") { game, index ->
            val controller = game.obj as GameImpl<Any>
            val model = controller.model as TTController
            if (model.currentPlayer.index() != index) {
                return@ServerAI listOf()
            }
            if (model.isGameOver || isDraw(model)) {
                return@ServerAI listOf()
            }

            // Always do actions based on the standardized state
            // Find possible symmetry transformations
            // Make move
            // TODO: Learn the same value for all possible symmetries of action

            val action = learn.pickWeightedBestAction(model)
            val x = action % model.game.sizeX
            val y = action / model.game.sizeX
            val point = Point(x, y)
            return@ServerAI listOf(PlayerGameMoveRequest(game, index, "play", point))

            /*
            Alternative approach of finding available actions to evaluate:
            val actionTypes = controller.availableActionTypes().map {
                it to controller.actionType<Any>(it)!!
            }
            val actions = actionTypes.flatMap {actionType ->
                actionType.second.availableActions(index).map { actionType.first to it }
            }
            if (actions.isEmpty()) {
                return@ServerAI listOf()
            }
            val chosenAction = actions.(use learner to find best action).let {
                return@let PlayerGameMoveRequest(game, index, it.first, it.second.parameter)
            }
            listOf(chosenAction)
            */
        }
        serverAI.register(events)
        events.listen("#AI_QLearn_$gameType pre-move", PreMoveEvent::class, {
            it.game.players.contains(serverAI.client)
        }, {
            val game = it.game.obj as GameImpl<TTController>
            val point = it.move as Point

            val action = it.move.y * game.model.game.sizeX + it.move.x
            val awaitingReward = learn.prepareReward(game.model, action)
            runBlocking {
                mutex.withLock { awaitingResults[Pair(it.game, it.player)] = awaitingReward }
            }
        })
        events.listen("#AI_QLearn_$gameType post-move", MoveEvent::class, {
            it.game.players.contains(serverAI.client)
        }, {event ->
            runBlocking {
                mutex.withLock {
                    val entry = awaitingResults.entries.find {
                        it.key.first == event.game
                    }
                    if (entry == null) {
                        logger.error("No entry found for server game ${event.game}")
                        return@runBlocking
                    }
                    awaitingResults.entries.remove(entry)

                    val reward = observeReward(event.game.obj as GameImpl<TTController>,
                            entry.value.action, entry.key.second)
                    learn.performReward(entry.value as QAwaitingReward<String>, reward)
                }
            }
        })
    }

}