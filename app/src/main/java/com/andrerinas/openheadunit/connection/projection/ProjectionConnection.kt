package com.andrerinas.openheadunit.connection.projection

interface ProjectionConnection {

    val type: Type

    val isConnected: Boolean

    suspend fun connect(): Boolean

    fun disconnect()

    val isSingleMessage: Boolean

    fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int

    fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int



    enum class Type {

        USB,
        WIFI
    }
}
