package net.zomis.games.server2.db

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.document.*
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec
import com.amazonaws.services.dynamodbv2.model.*
import klog.KLoggers
import net.zomis.common.convertFromDBFormat
import net.zomis.common.convertToDBFormat
import net.zomis.core.events.EventSystem
import net.zomis.core.events.ListenerPriority
import net.zomis.games.Features
import net.zomis.games.dsl.GameSpec
import net.zomis.games.dsl.impl.GameImpl
import net.zomis.games.server2.*
import net.zomis.games.server2.ais.ServerAIProvider
import net.zomis.games.server2.clients.FakeClient
import net.zomis.games.server2.games.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class UnfinishedGames(val unfinishedGames: MutableSet<DBGameSummary>)
data class MoveHistory(val moveType: String, val playerIndex: Int, val move: Any?, val state: Map<String, Any>?)
data class PlayerView(val playerId: String, val name: String)

class SuperTable(private val dynamoDB: AmazonDynamoDB) {

    private val logger = KLoggers.logger(this)

    private fun dbEnabled(game: ServerGame): Boolean {
        return game.gameMeta.database
    }

    fun setup(features: Features, events: EventSystem): List<CreateTableRequest> {
        events.listen("Auth", ClientLoginEvent::class, {true}, {
            this.authenticate(it)
        })
        events.listen("Load unfinished games", StartupEvent::class, {true}, {
            features.addData(UnfinishedGames(this.listUnfinished().toMutableSet()))
        })
        events.listen("save game in Database", ListenerPriority.LATER, GameStartedEvent::class, { dbEnabled(it.game) }, {
            event -> this.createGame(event.game, mapOf())
        })
        events.listen("save game move in Database", MoveEvent::class, { dbEnabled(it.game) }, {event ->
            this.addMove(event)
        })
        events.listen("finish game in Database", GameEndedEvent::class, { dbEnabled(it.game) }, { event ->
            this.finishGame(event.game)
        })
        events.listen("eliminate player in Database", PlayerEliminatedEvent::class, { dbEnabled(it.game) }, {event ->
            this.playerEliminated(event)
        })
        events.listen("load game", ClientJsonMessage::class, { it.data.getTextOrDefault("type", "") == "LoadGame" }, { event ->
            TODO("Delete LoadGame call. Should no longer be used")
            //val unfinishedGame = features[UnfinishedGames::class]!!.unfinishedGames.filter { it.gameId == gameId }.single()
            //val dbGame = this.getGame(unfinishedGame)
            // val game = gameType.resumeGame(dbGame.summary.gameId, dbGame.game)
        })
        return listOf(this.table).map { it.createTableRequest() }
    }

    val tableName = "Server2"
    val pk = "PK"
    val sk = "SK"
    val data = "Data"
    val table = MyTable(dynamoDB, tableName).strings(pk, sk).numbers(data)
    val primaryIndex = table.primaryIndex(pk, sk)
    val gsi = table.index(ProjectionType.KEYS_ONLY, listOf(sk), listOf(data))

    private val SK_UNFINISHED = "unfinished"
    private val SK_PUSH_TO_STATS = "push-to-stats"

    enum class Prefix {
        GAME,
        TAG,
        GAME_TYPE,
        SUMMARY,
        ZMOVE,
        PLAYER, SESSION, OAUTH, ALIAS,
        ;

        val prefix = this.name.toLowerCase() + ":"
        fun extract(fieldValue: String): String {
            if (!fieldValue.startsWith(prefix)) {
                throw IllegalArgumentException("$fieldValue does not begin with $prefix")
            }
            return fieldValue.substringAfter(prefix)
        }
        fun sk(value: String) = prefix + value
        fun rangeKeyCondition(): RangeKeyCondition = RangeKeyCondition("SK").beginsWith(this.prefix)
    }

    enum class Fields(val fieldName: String) {
        PLAYER_NAME("PlayerName"),
        PLAYER_PREFIX("Player"),
        GAME_TYPE("GameType"),
        GAME_PLAYERS("GamePlayers"),
        GAME_TIME_LAST("GameTime"),
        GAME_TIME_STARTED("GameTimeStarted"),
        GAME_OPTIONS("Options"),
        GAME_HIDDEN("Hidden"),

        MOVE_PLAYER_INDEX("PlayerIndex"),
        MOVE_TIME("Time"),
        MOVE_TYPE("MoveType"),
        MOVE("Move"),
        MOVE_STATE("MoveState")
        ;
    }

    fun createGame(game: ServerGame, options: Map<String, Any>) {
        val pkValue = Prefix.GAME.sk(game.gameId)

        // Don't use data here because PK and SK is the same, this doesn't need to be in GSI-1
        val state: Any? = gameRandomnessState(game)
        val updates = listOf(
            AttributeUpdate(Fields.GAME_TYPE.fieldName).put(game.gameType.type),
            AttributeUpdate(Fields.GAME_TIME_STARTED.fieldName).put(Instant.now().epochSecond),
            AttributeUpdate(Fields.GAME_OPTIONS.fieldName).put(options)
        ).let { if (state != null) it.plus(AttributeUpdate(Fields.MOVE_STATE.fieldName).put(state)) else it }

        val update = UpdateItemSpec().withPrimaryKey(this.pk, pkValue, this.sk, pkValue)
            .withAttributeUpdate(updates)
        this.update("Create game $pkValue", update)
        val epochMilli = Instant.now().toEpochMilli()
        this.simpleUpdate(pkValue, SK_UNFINISHED, epochMilli)
//        this.simpleUpdate(pkValue, gameSortKey(game.gameId), epochSecond,
//            Fields.GAME_TYPE to game.gameType.type,
//            Fields.GAME_PLAYERS to game.players.map { mapOf("Name" to it.name!!) }
//        )

        // Add players in game
        val playerIds = game.players
            .map { it.playerId ?: throw IllegalStateException("Missing playerId for ${it.name}") }
        val players = playerIds.withIndex().groupBy({ it.value }) { it.index }
        players.forEach { (playerId, indexes) ->
            this.simpleUpdate(pkValue, Prefix.PLAYER.sk(playerId.toString()), epochMilli,
                Fields.GAME_PLAYERS to indexes.map {
                    mapOf("Index" to it)
                }
//                TODO: Instead of the above, use Player0: name, Player1: name
            )
        }
    }

    fun addMove(move: MoveEvent) {
        val epochMilli = Instant.now().toEpochMilli()
        val serverGame = move.game
        val moveIndex = serverGame.nextMoveIndex()
        val moveData = convertToDBFormat(move.move)
        val state: Any? = gameRandomnessState(serverGame)
        val updates = mutableListOf(
            AttributeUpdate(Fields.MOVE_TIME.fieldName).put(epochMilli),
            AttributeUpdate(Fields.MOVE_PLAYER_INDEX.fieldName).put(move.player),
            AttributeUpdate(Fields.MOVE_TYPE.fieldName).put(move.moveType)
        )
        if (moveData != null) {
            updates += AttributeUpdate(Fields.MOVE.fieldName).put(moveData)
        }
        if (state != null) {
            updates += AttributeUpdate(Fields.MOVE_STATE.fieldName).put(state)
        }

        val update = UpdateItemSpec().withPrimaryKey(this.pk, Prefix.GAME.sk(serverGame.gameId),
            this.sk, Prefix.ZMOVE.sk(moveIndex.toString())).withAttributeUpdate(updates)
        this.update("Add Move $move", update)
    }

    private fun gameRandomnessState(serverGame: ServerGame): Any? {
        if (serverGame.obj is GameImpl<*>) {
            val game = serverGame.obj as GameImpl<*>
            val lastMoveState = game.stateKeeper.lastMoveState()
            if (lastMoveState.isNotEmpty()) {
                return convertToDBFormat(lastMoveState)
            }
        }
        return null
    }

    fun update(description: String, update: UpdateItemSpec) {
        this.logCapacity(description, { it.updateItemResult.consumedCapacity }, {
            table.table.updateItem(update.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES))
        })
    }

    fun playerEliminated(event: PlayerEliminatedEvent) {
        val pkValue = Prefix.GAME.sk(event.game.gameId)

        val playerData = mapOf(
            "Result" to event.winner.result,
            "ResultPosition" to event.position,
            "ResultReason" to "eliminated"
        )
        this.update("Eliminate player ${event.player} in game $pkValue", UpdateItemSpec().withPrimaryKey(this.pk, pkValue, this.sk, pkValue)
          .withAttributeUpdate(AttributeUpdate(Fields.PLAYER_PREFIX.fieldName + event.player).put(playerData)))
    }

    fun finishGame(game: ServerGame) {
        val pkValue = Prefix.GAME.sk(game.gameId)
        this.logCapacity("remove unfinished ${game.gameId}", { it.deleteItemResult.consumedCapacity }) {
            table.table.deleteItem(DeleteItemSpec().withPrimaryKey(this.pk, pkValue, this.sk, SK_UNFINISHED)
                .withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES))
        }
        this.simpleUpdate(pkValue, SK_PUSH_TO_STATS, Instant.now().toEpochMilli())
    }

    fun <T : Any> logCapacity(description: String, capacity: (T) -> ConsumedCapacity?, function: () -> T): T {
        val startTime = System.nanoTime()
        val result = function()
        val endTime = System.nanoTime()
        val timeTaken = endTime - startTime
        logger.info { "Consumed Capacity on $description consumed ${capacity(result)} and took $timeTaken" }
        return result
    }

    fun getGame(game: DBGameSummary): DBGame {
        val moves = getGameMoves(game.gameId)
        return DBGame(game, moves)
    }

    fun listUnfinished(): Set<DBGameSummary> {
        return this.gsiLookup(QuerySpec().withHashKey(this.sk, SK_UNFINISHED).withRangeKeyCondition(RangeKeyCondition(this.data).gt(0))).map { unfinishedRow ->
            val gameId = unfinishedRow[this.pk] as String
            getGameSummary(gameId)
        }.filterNotNull().toSet()
    }

    fun getGameMoves(gameId: String): List<MoveHistory> {
        val dbMoves = this.queryTable(QuerySpec()
                .withHashKey(this.pk, Prefix.GAME.sk(gameId))
                .withRangeKeyCondition(Prefix.ZMOVE.rangeKeyCondition()))
        return dbMoves.map {
            Prefix.ZMOVE.extract(it.getString(this.sk)).toInt() to MoveHistory(
                it[Fields.MOVE_TYPE.fieldName] as String,
                (it[Fields.MOVE_PLAYER_INDEX.fieldName] as BigDecimal).toInt(),
                convertFromDBFormat(it[Fields.MOVE.fieldName]),
                convertFromDBFormat(it[Fields.MOVE_STATE.fieldName]) as Map<String, Any>?
            )
        }.sortedBy { it.first }.map { it.second }
    }

    fun authenticate(event: ClientLoginEvent) {
        // 1. Lookup GSI-1:   oauth:<provider>/<providerId>, any sort key. Get back playerId
        val provider = event.provider
        val providerId = event.loginName
        val skValue = Prefix.OAUTH.sk("$provider/$providerId")
        val existing = this.gsiLookup(QuerySpec().withHashKey(this.sk, skValue)).singleOrNull()

        val timestamp = Instant.now().epochSecond

        // If exists, overwrite client playerId, and update sort key (last connected time)
        if (existing != null) {
            val uuid = existing.getString(this.pk).substringAfter(':')
            val pkValue = Prefix.PLAYER.sk(uuid)
            event.client.playerId = UUID.fromString(uuid)
            if (event.provider == ServerAIProvider) {
                // Server AI times should be updated when the AI is used, not when starting the server
                return
            }
            val updateResult = this.table.table.updateItem(pk, pkValue, sk, skValue, AttributeUpdate(data).put(timestamp))
            logger.info("Update ${event.client.name}. Found $uuid. Update result $updateResult")
            return
        }

        // If not exists: PutItem - playerId, oauth:provider/ProviderId, last login
        if (event.client.playerId == null) {
            throw IllegalStateException("Client should have playerId set: ${event.client.name}")
        }

        val pkValue = Prefix.PLAYER.sk(event.client.playerId.toString())
        val putItemRequest = PutItemRequest(tableName, mapOf(
            pk to AttributeValue(pkValue),
            sk to AttributeValue(skValue),
            data to timeStamp(),
            Fields.PLAYER_NAME.fieldName to AttributeValue(event.client.name)
        )).withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES)
        val updateResult = this.logCapacity("authenticate $pkValue", { it.consumedCapacity }) { dynamoDB.putItem(putItemRequest) }
        logger.info("Update ${event.client.name}. Adding as $pkValue. Update result $updateResult")
    }

    private fun gsiLookup(query: QuerySpec): ItemCollection<QueryOutcome> {
        return this.logCapacity("Querying GSI on ${query.hashKey} / ${query.rangeKeyCondition}", { it.accumulatedConsumedCapacity }) {
            this.gsi.index.query(query.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES))
        }
    }

    fun simpleUpdate(pkValue: String, skValue: String, data: Long?, vararg pairs: Pair<Fields, Any>) {
        val update = UpdateItemSpec().withPrimaryKey(this.pk, pkValue, this.sk, skValue)
            .withAttributeUpdate(
                pairs.map {
                    AttributeUpdate(it.first.fieldName).put(it.second)
                }.let {
                    if (data != null) it.plus(AttributeUpdate(this.data).put(data)) else it
                }
            )
        this.update("Simple Update $pkValue, $skValue", update)
    }

    fun authenticateSession(session: String) {}

    fun getGameSummary(gameId: String): DBGameSummary? {
        val pureGameId = Prefix.GAME.extract(gameId)
        val query = QuerySpec()
            .withHashKey(this.pk, Prefix.GAME.sk(pureGameId))
            .withRangeKeyCondition(RangeKeyCondition(this.sk).between("a", "y"))

        val sks = this.queryTable(query).associateBy {
            it[this.sk] as String
        }

        val gameDetails = sks.getValue(gameId)
        val playersInGame = sks.filter { it.key.startsWith(Prefix.PLAYER.prefix) }.flatMap {playerEntry ->
            val playerId = Prefix.PLAYER.extract(playerEntry.key)
            // Look in it[Fields.GAME_PLAYERS] for which indexes a player belongs to. (Maybe also store name?)
            val indexes = playerEntry.value[Fields.GAME_PLAYERS.fieldName] as List<Map<String, Any>>
            return@flatMap indexes.map {playerInfo ->
                val index = (playerInfo["Index"] as BigDecimal).toInt()
                val playerName = playerInfo["Name"] as String? ?: findPlayerName(playerId) ?: "UNKNOWN"
                val attributeName = Fields.PLAYER_PREFIX.fieldName + index
                val hasDetails = gameDetails.hasAttribute(attributeName)
                val playerResults = if (hasDetails) {
                    val details = gameDetails[attributeName] as Map<String, Any>
                    val result = (details["Result"] as BigDecimal).toDouble()
                    val resultPosition = (details["ResultPosition"] as BigDecimal).toInt()
                    PlayerInGameResults(result, resultPosition, details["ResultReason"] as String, mapOf())
                } else null
                PlayerInGame(PlayerView(playerId, playerName), index, playerResults)
            }
        }
        val unfinished = sks.any { it.key == this.SK_UNFINISHED || it.key == "tag:$SK_UNFINISHED" }
        val hidden = gameDetails.hasAttribute(Fields.GAME_HIDDEN.fieldName)
        val gameState = when {
            unfinished -> GameState.UNFINISHED
            hidden -> GameState.HIDDEN
            else -> GameState.PUBLIC
        }
        val startingState = convertFromDBFormat(gameDetails[Fields.MOVE_STATE.fieldName]) as Map<String, Any>?
        val timeStarted = gameDetails[Fields.GAME_TIME_STARTED.fieldName] as BigDecimal
        val timeLastAction = gameDetails[Fields.GAME_TIME_LAST.fieldName] as BigDecimal?

        val gameType = gameDetails[Fields.GAME_TYPE.fieldName] as String
        val gameSpec = ServerGames.games[gameType] as GameSpec<Any>?
        if (gameSpec == null) {
            logger.warn { "Ignoring unfinished game $gameId. Expected gameType $gameType not found." }
            return null
        }

        return DBGameSummary(gameSpec, Prefix.GAME.extract(gameId), playersInGame, gameType, gameState.value,
                startingState, timeStarted.longValueExact(), timeLastAction?.longValueExact()?:0)
    }

    private fun findPlayerName(playerId: String): String? {
        val query = QuerySpec()
                .withHashKey(this.pk, Prefix.PLAYER.sk(playerId))
                .withRangeKeyCondition(RangeKeyCondition(this.sk).between("o", "s"))
        val playerItem = this.queryTable(query).firstOrNull()
        return playerItem?.get("PlayerName") as String?
    }

    private fun queryTable(query: QuerySpec): ItemCollection<QueryOutcome> {
        return this.logCapacity("${query.hashKey} / ${query.rangeKeyCondition}", {it.accumulatedConsumedCapacity}) {
            this.table.table.query(query.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES))
        }
    }

}
