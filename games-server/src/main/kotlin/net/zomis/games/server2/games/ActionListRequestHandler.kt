package net.zomis.games.server2.games

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import klog.KLoggers
import net.zomis.games.dsl.impl.ActionInfo
import net.zomis.games.dsl.impl.GameImpl
import net.zomis.games.server2.Client
import net.zomis.games.server2.ClientJsonMessage
import net.zomis.games.server2.getTextOrDefault

class ActionList(val playerIndex: Int, val game: ServerGame, val actions: List<Pair<String, ActionInfo<Any>>>)
class ActionListRequestHandler(private val game: ServerGame?) {
    private val logger = KLoggers.logger(this)
    private val mapper = jacksonObjectMapper()

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

    fun sendActionList(message: ClientJsonMessage) {
        val actionParams = actionParams(message)
        this.sendActionParams(message.client, actionParams)
    }

    private fun sendActionParams(client: Client, actionParams: ActionList) {
        val game = actionParams.game
        logger.info { "Sending action list data for ${game.gameId} of type ${game.gameType.type} to ${actionParams.playerIndex}" }
        client.send(mapOf(
            "type" to "ActionList",
            "gameType" to game.gameType.type,
            "gameId" to game.gameId,
            "playerIndex" to actionParams.playerIndex,
            "actions" to actionParams.actions
        ))
    }

    private fun actionParams(message: ClientJsonMessage): ActionList {
        if (game!!.obj !is GameImpl<*>) {
            throw IllegalArgumentException("Game ${game.gameId} of type ${game.gameType.type} is not a valid DSL game")
        }

        val obj = game.obj as GameImpl<*>
        val playerIndex = message.data.getTextOrDefault("playerIndex", "-1").toInt()
        if (!game.verifyPlayerIndex(message.client, playerIndex)) {
            throw IllegalArgumentException("Client ${message.client} does not have index $playerIndex in Game ${game.gameId} of type ${game.gameType.type}")
        }

        val moveType = message.data.get("moveType")?.asText()
        val chosenJson = message.data.get("chosen") ?: emptyList<JsonNode>()
        val chosen = mutableListOf<Any>()

        for (choiceJson in chosenJson) {
            val actionParams = availableActionsMessage(obj, playerIndex, moveType, chosen)
            val actionInfo = actionParams.single().second
            val clazz = actionInfo.nextOptions.map { it::class }.toSet().single()

            val parameter: Any
            try {
                parameter = if (clazz == Unit::class) {
                    Unit
                } else {
                    val moveJsonText = mapper.writeValueAsString(choiceJson)
                    mapper.readValue(moveJsonText, clazz.java)
                }
            } catch (e: Exception) {
                logger.error(e, "Error reading choice: $choiceJson")
                throw e
            }
            chosen.add(parameter)
        }
        return ActionList(playerIndex, game, availableActionsMessage(obj, playerIndex, moveType, chosen))
    }

    fun actionRequest(message: ClientJsonMessage, callback: GameCallback) {
        val actionParams = actionParams(message)
        val actionType = actionParams.actions.singleOrNull()
        val action = actionType?.second!!.parameters.singleOrNull()
        if (action != null) {
            val actionRequest = PlayerGameMoveRequest(actionParams.game, actionParams.playerIndex, actionType.first, action)
            callback.moveHandler(actionRequest)
        } else {
            this.sendActionParams(message.client, actionParams)
        }
    }

}