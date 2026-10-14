package com.turretdefense.util

import java.util.PriorityQueue

// 每个维度运行时拥有独立队列；卸载时清空，不使用协程或墙钟计时。
class TickScheduler {
    private data class Task(val due: Long,val order: Long,val action: () -> Unit)
    private val queue = PriorityQueue<Task>(compareBy<Task> { it.due }.thenBy { it.order })
    private var order = 0L
    fun schedule(dueTick: Long,action: () -> Unit) { queue.add(Task(dueTick,order++,action)) }
    fun tick(now: Long) {
        var executed = 0
        while (queue.isNotEmpty() && queue.peek().due <= now && executed < 256) {
            val task = queue.poll(); executed++; task.action()
        }
    }
    fun clear() { queue.clear() }
    val size get() = queue.size
}
