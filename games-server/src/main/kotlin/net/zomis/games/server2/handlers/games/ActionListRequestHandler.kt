package net.zomis.games.server2.handlers.games

import klog.KLoggers
import net.zomis.games.dsl.impl.ActionInfo
import net.zomis.games.dsl.impl.GameImpl
import net.zomis.games.server2.ClientJsonMessage
import net.zomis.games.server2.IncomingMessageHandler
import net.zomis.games.server2.games.GameSystem
import net.zomis.games.server2.getTextOrDefault

class ActionListRequestHandler(private val gameSystem: GameSystem): IncomingMessageHandler {
    private val logger = KLoggers.logger(this)

    fun availableActionsMessage(obj: GameImpl<*>, playerIndex: Int, moveType: String?, chosen: List<Any>?): List<Pair<String, ActionInfo<Any>>> {
        if (moveType != null) {
            val actionType = obj.actions.type(moveType)
            return if (actionType != null) {
                listOf(actionType.name to actionType.availableParameters(playerIndex, chosen ?: emptyList()))
            } else {
                emptyList()
            }
        } else {
            return obj.actions.types().map {
                it.name to it.availableParameters(playerIndex, emptyList())
            }
        }
    }

    override fun invoke(message: ClientJsonMessage) {
        val gameType = message.data.getTextOrDefault("gameType", "")
        val type = gameSystem.getGameType(gameType)
        if (type == null) {
            logger.error("No such gameType: $gameType")
            return
        }
        val gameId = message.data.getTextOrDefault("gameId", "")
        val game = type.runningGames[gameId]
        if (game == null) {
            logger.error("No such game: $gameId of type $gameType")
            return
        }

        if (game.obj !is GameImpl<*>) {
            logger.error("Game $gameId of type $gameType is not a valid DSL game")
            return
        }

        val obj = game.obj as GameImpl<*>
        val playerIndex = message.data.getTextOrDefault("playerIndex", "-1").toInt()
        if (!game.verifyPlayerIndex(message.client, playerIndex)) {
            logger.error("Client ${message.client} does not have index $playerIndex in Game $gameId of type $gameType")
            return
        }

        val moveType = message.data.get("moveType")?.asText()
        val chosen = message.data.get("chosen")?.map { if (it.isInt) it.asInt() else it.asText() } ?: emptyList()

        val actionParams = availableActionsMessage(obj, playerIndex, moveType, chosen)

        logger.info { "Sending action list data for $gameId of type $gameType to $playerIndex" }
        message.client.send(mapOf(
            "type" to "ActionList",
            "gameType" to gameType,
            "gameId" to gameId,
            "playerIndex" to playerIndex,
            "actions" to actionParams
        ))
    }

}