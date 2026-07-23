package com.eni.albionradar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PhotonParser {

    private const val OP_MOVE = 2
    private const val OP_SERVER_SPAWN = 8
    private const val OP_LEAVE = 4
    private const val OP_HEALTH_UPDATE = 17

    private const val PARAM_POSITION = 3
    private const val PARAM_USER_ID = 1
    private const val PARAM_OBJECT_ID = 0
    private const val PARAM_NAME = 2
    private const val PARAM_HEALTH = 6
    private const val PARAM_MAX_HEALTH = 7

    fun parse(payload: ByteArray, srcPort: Int, dstPort: Int) {
        try {
            if (payload.size < 12) return

            val buf = ByteBuffer.wrap(payload)
            buf.order(ByteOrder.BIG_ENDIAN)

            val commandCount = buf.get(3).toInt() and 0xFF

            buf.position(12)

            for (i in 0 until commandCount) {
                if (buf.remaining() < 8) break
                parseCommand(buf)
            }
        } catch (_: Exception) {}
    }

    private fun parseCommand(buf: ByteBuffer) {
        val commandType = buf.get().toInt() and 0xFF
        buf.get()
        val flags = buf.get().toInt() and 0xFF
        val commandSize = buf.short.toInt() and 0xFFFF

        if (commandSize < 8 || commandSize > buf.remaining() + 8) {
            buf.position(buf.limit())
            return
        }

        val endPos = buf.position() + commandSize - 8

        when (commandType) {
            4 -> parseReliableCommand(buf, endPos)
            else -> buf.position(endPos)
        }
    }

    private fun parseReliableCommand(buf: ByteBuffer, endPos: Int) {
        buf.int

        if (buf.position() >= endPos) return
        if (endPos - buf.position() < 4) return

        val opCode = buf.get().toInt() and 0xFF
        val paramCount = buf.get().toInt() and 0xFF

        val params = HashMap<Int, Any>()
        for (i in 0 until paramCount) {
            if (buf.position() >= endPos) break
            val param = parseParameter(buf, endPos)
            if (param != null) {
                params[param.first] = param.second
            }
        }

        processOperation(opCode, params)
        buf.position(endPos)
    }

    private fun parseParameter(buf: ByteBuffer, endPos: Int): Pair<Int, Any>? {
        if (buf.position() + 2 > endPos) return null

        val paramKey = buf.get().toInt() and 0xFF
        val paramType = buf.get().toInt() and 0xFF

        val value: Any? = when (paramType) {
            0 -> null
            1 -> buf.get().toInt() and 0xFF
            2 -> buf.short.toInt()
            3 -> buf.int
            4 -> buf.long
            5 -> buf.float
            6 -> buf.double
            7 -> readString(buf, endPos)
            8 -> readVector3(buf)
            9 -> readLongArray(buf, endPos)
            10 -> readByteArray(buf, endPos)
            else -> { buf.position(endPos); null }
        }

        if (value == null) return null
        return Pair(paramKey, value)
    }

    private fun readString(buf: ByteBuffer, endPos: Int): String? {
        if (buf.position() + 2 > endPos) return null
        val length = buf.short.toInt() and 0xFFFF
        if (length <= 0 || buf.position() + length > endPos) return null
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readVector3(buf: ByteBuffer): FloatArray {
        return floatArrayOf(buf.float, buf.float, buf.float)
    }

    private fun readLongArray(buf: ByteBuffer, endPos: Int): LongArray? {
        if (buf.position() + 2 > endPos) return null
        val count = buf.short.toInt() and 0xFFFF
        if (count <= 0 || buf.position() + count * 8 > endPos) return null
        val arr = LongArray(count)
        for (i in 0 until count) arr[i] = buf.long
        return arr
    }

    private fun readByteArray(buf: ByteBuffer, endPos: Int): ByteArray? {
        if (buf.position() + 2 > endPos) return null
        val length = buf.short.toInt() and 0xFFFF
        if (length <= 0 || buf.position() + length > endPos) return null
        val arr = ByteArray(length)
        buf.get(arr)
        return arr
    }

    private fun processOperation(opCode: Int, params: Map<Int, Any>) {
        when (opCode) {
            OP_MOVE -> {
                val pos = params[PARAM_POSITION] as? FloatArray
                val userId = params[PARAM_USER_ID] as? Long
                if (pos != null && userId != null) {
                    RadarEngine.updatePlayerPosition(userId, pos[0], pos[1], pos[2])
                }
            }
            OP_SERVER_SPAWN -> {
                val userId = params[PARAM_USER_ID] as? Long
                val name = params[PARAM_NAME] as? String
                val pos = params[PARAM_POSITION] as? FloatArray
                if (userId != null) {
                    RadarEngine.spawnPlayer(userId, name ?: "Unknown", pos?.get(0) ?: 0f, pos?.get(1) ?: 0f, pos?.get(2) ?: 0f)
                }
            }
            OP_LEAVE -> {
                val userId = params[PARAM_USER_ID] as? Long
                if (userId != null) RadarEngine.removePlayer(userId)
            }
            OP_HEALTH_UPDATE -> {
                val userId = params[PARAM_USER_ID] as? Long
                val health = params[PARAM_HEALTH] as? Int
                val maxHealth = params[PARAM_MAX_HEALTH] as? Int
                if (userId != null && health != null) {
                    RadarEngine.updatePlayerHealth(userId, health, maxHealth ?: 100)
                }
            }
        }
    }
}
