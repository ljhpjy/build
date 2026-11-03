package com.fuckds.android

import java.util.concurrent.CopyOnWriteArraySet

enum class ServerPhase {
    STOPPED,
    PREPARING,
    STARTING,
    RUNNING,
    ERROR,
}

data class ServerSnapshot(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val message: String = "未启动",
    val port: Int = 8787,
)

/** 服务和界面在同一进程，用这个单例广播状态就够了。 */
object ServerState {
    @Volatile
    var snapshot: ServerSnapshot = ServerSnapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(ServerSnapshot) -> Unit>()

    fun update(phase: ServerPhase, message: String, port: Int = snapshot.port) {
        snapshot = ServerSnapshot(phase, message, port)
        for (listener in listeners) {
            try {
                listener(snapshot)
            } catch (_: Throwable) {
                /* 忽略界面侧的异常 */
            }
        }
    }

    fun addListener(listener: (ServerSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (ServerSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
