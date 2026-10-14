package com.turretdefense.network

import com.turretdefense.blockentity.*
import com.turretdefense.config.Config
import com.turretdefense.util.TickScheduler
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

// 运行时登记的都是已加载方块实体；维度卸载事件负责清理全部引用和延迟任务。
class LevelRuntime(val level: ServerLevel) {
    val cores = Long2ObjectOpenHashMap<BaseCoreBlockEntity>()
    val bases = Long2ObjectOpenHashMap<TurretBaseBlockEntity>()
    val heads = Long2ObjectOpenHashMap<TurretHeadBlockEntity>()
    private val headChunks = Long2ObjectOpenHashMap<MutableSet<TurretHeadBlockEntity>>()
    // 怪物每秒只访问附近九个区块的已登记塔头，不扫描方块或全维度塔头。
    fun addHead(head: TurretHeadBlockEntity) {
        heads.put(head.blockPos.asLong(),head)
        headChunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(head.blockPos.x shr 4,head.blockPos.z shr 4)) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TurretHeadBlockEntity,Boolean>()) }.add(head)
    }
    fun removeHead(head: TurretHeadBlockEntity) {
        if (heads[head.blockPos.asLong()] === head) heads.remove(head.blockPos.asLong())
        val key = net.minecraft.world.level.ChunkPos.asLong(head.blockPos.x shr 4,head.blockPos.z shr 4)
        headChunks[key]?.let { it.remove(head); if (it.isEmpty()) headChunks.remove(key) }
    }
    fun nearestHead(point: net.minecraft.world.phys.Vec3,range: Double): TurretHeadBlockEntity? {
        var nearest: TurretHeadBlockEntity? = null; var distance = range*range
        for (x in (kotlin.math.floor(point.x-range).toInt() shr 4)..(kotlin.math.floor(point.x+range).toInt() shr 4)) for (z in (kotlin.math.floor(point.z-range).toInt() shr 4)..(kotlin.math.floor(point.z+range).toInt() shr 4)) {
            val list = headChunks[net.minecraft.world.level.ChunkPos.asLong(x,z)] ?: continue
            for (head in list) if (!head.isRemoved) { val square = head.center.distanceToSqr(point); if (square < distance) { distance = square; nearest = head } }
        }
        return nearest
    }
    val scheduler = TickScheduler()
    val navigation = com.turretdefense.targeting.NavigationBudget()
    var pathNanos = 0L
    var pathCount = 0L
    var pathPeakNanos = 0L
    var tickNanos = 0L
    var particlePackets = 0
    private val samples = LongArray(600)
    private var sampleIndex = 0
    private var sampleCount = 0
    fun recordNanos(start: Long) { tickNanos += System.nanoTime()-start }
    fun finishTick() {
        samples[sampleIndex] = tickNanos; sampleIndex = (sampleIndex+1)%samples.size
        sampleCount = (sampleCount+1).coerceAtMost(samples.size); tickNanos = 0L
    }
    fun meanMillis(): Double = if (sampleCount == 0) 0.0 else samples.take(sampleCount).sum()/sampleCount/1_000_000.0
    fun peakMillis(): Double = if (sampleCount == 0) 0.0 else samples.take(sampleCount).maxOrNull()!!/1_000_000.0
    fun resetSamples() { samples.fill(0L); sampleIndex = 0; sampleCount = 0; tickNanos = 0; pathNanos = 0; pathCount = 0; pathPeakNanos = 0 }
    fun clear() { scheduler.clear(); navigation.clear(); cores.clear(); bases.clear(); heads.clear(); headChunks.clear() }
    companion object {
        private val levels = IdentityHashMap<ServerLevel,LevelRuntime>()
        fun get(level: ServerLevel): LevelRuntime = levels.getOrPut(level) { LevelRuntime(level) }
        fun peek(level: ServerLevel): LevelRuntime? = levels[level]
        fun all(): Collection<LevelRuntime> = levels.values
        fun unload(level: ServerLevel) { levels.remove(level)?.clear(); FireBudget.removeLevel(level) }
        fun clearAll() { levels.values.forEach { it.clear() }; levels.clear(); FireBudget.clear() }
    }
}

// 全服务器共享开火预算，超预算请求按轮转顺序获得优先权，避免高频塔头永久饿死其他塔。
object FireBudget {
    private val waiting = ArrayDeque<TurretHeadBlockEntity>()
    private val queued = Collections.newSetFromMap(IdentityHashMap<TurretHeadBlockEntity,Boolean>())
    private val permits = Collections.newSetFromMap(IdentityHashMap<TurretHeadBlockEntity,Boolean>())
    var fires = 0
    private var attempts = 0
    var peakFires = 0
    var limitHits = 0L
    fun beginTick() {
        peakFires = maxOf(peakFires,fires); fires = 0; attempts = 0; permits.clear()
        while (waiting.isNotEmpty() && permits.size < Config.maxFires.get()) {
            val head = waiting.removeFirst(); queued.remove(head)
            if (!head.isRemoved && head.level != null) permits.add(head)
        }
    }
    fun acquire(head: TurretHeadBlockEntity): Boolean {
        if (permits.remove(head)) { attempts++; return true }
        if (attempts+permits.size < Config.maxFires.get()) { attempts++; return true }
        limitHits++
        if (queued.add(head)) waiting.addLast(head)
        return false
    }
    fun releaseUnused(head: TurretHeadBlockEntity) { permits.remove(head) }
    fun recordFire() { fires++ }
    fun removeLevel(level: ServerLevel) {
        waiting.removeIf { it.level === level }; queued.removeIf { it.level === level }; permits.removeIf { it.level === level }
    }
    fun clear() { waiting.clear(); queued.clear(); permits.clear(); fires = 0; peakFires = 0; limitHits = 0 }
}
