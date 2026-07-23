package com.eni.albionradar

import java.util.concurrent.ConcurrentHashMap

data class PlayerInfo(
    val id: Long,
    var name: String,
    var x: Float,
    var y: Float,
    var z: Float,
    var health: Int = 100,
    var maxHealth: Int = 100,
    var lastUpdate: Long = System.currentTimeMillis()
)

object RadarEngine {

    private val players = ConcurrentHashMap<Long, PlayerInfo>()
    private var localPlayerId: Long? = null
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var localZ: Float = 0f

    fun spawnPlayer(id: Long, name: String, x: Float, y: Float, z: Float) {
        players[id] = PlayerInfo(id, name, x, y, z)
    }

    fun updatePlayerPosition(id: Long, x: Float, y: Float, z: Float) {
        val player = players[id]
        if (player != null) {
            player.x = x
            player.y = y
            player.z = z
            player.lastUpdate = System.currentTimeMillis()
        } else {
            players[id] = PlayerInfo(id, "Player_$id", x, y, z)
        }

        if (localPlayerId == null) localPlayerId = id
        if (id == localPlayerId) {
            localX = x
            localY = y
            localZ = z
        }
    }

    fun updatePlayerHealth(id: Long, health: Int, maxHealth: Int) {
        val player = players[id]
        if (player != null) {
            player.health = health
            player.maxHealth = maxHealth
            player.lastUpdate = System.currentTimeMillis()
        }
    }

    fun removePlayer(id: Long) {
        players.remove(id)
        if (id == localPlayerId) localPlayerId = null
    }

    fun getPlayerCount(): Int = players.size

    fun getPlayers(): Map<Long, PlayerInfo> = players.toMap()

    fun getLocalPlayer(): PlayerInfo? {
        val id = localPlayerId ?: return null
        return players[id]
    }

    fun getLocalPosition(): FloatArray = floatArrayOf(localX, localY, localZ)

    fun clear() {
        players.clear()
        localPlayerId = null
    }
}
