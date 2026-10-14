package com.turretdefense.network

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

// 每个维度使用自己的持久数据，登记核心位置和权限；不触发未加载区块加载。
class TurretNetworkData : SavedData() {
    val cores = LongOpenHashSet()
    private val trusts = mutableMapOf<Long, MutableSet<UUID>>()
    fun trust(core: Long, player: UUID, add: Boolean) {
        val set = trusts.getOrPut(core) { mutableSetOf() }
        if (add) set.add(player) else set.remove(player)
        setDirty()
    }
    fun trusted(core: Long, player: UUID) = trusts[core]?.contains(player) == true
    fun trustedPlayers(core: Long): Set<UUID> = trusts[core]?.toSet() ?: emptySet()
    fun forget(core: Long) { cores.remove(core); trusts.remove(core); setDirty() }
    override fun save(tag: CompoundTag): CompoundTag {
        tag.putLongArray("Cores",cores.toLongArray())
        val entries = ListTag()
        trusts.forEach { (position,players) ->
            val entry = CompoundTag(); entry.putLong("Pos",position)
            val list = ListTag(); players.forEach { id -> val player = CompoundTag(); player.putUUID("Player",id); list.add(player) }
            entry.put("Players",list); entries.add(entry)
        }
        tag.put("Trust",entries)
        return tag
    }
    companion object {
        fun get(level: ServerLevel): TurretNetworkData = level.dataStorage.computeIfAbsent({ tag ->
            val data = TurretNetworkData(); data.cores.addAll(LongOpenHashSet(tag.getLongArray("Cores")))
            val entries = tag.getList("Trust",10)
            for (index in 0 until entries.size) {
                val entry = entries.getCompound(index); val players = entry.getList("Players",10)
                val set = mutableSetOf<UUID>()
                for (p in 0 until players.size) { val value = players.getCompound(p); if (value.hasUUID("Player")) set.add(value.getUUID("Player")) }
                data.trusts[entry.getLong("Pos")] = set
            }
            data
        },{ TurretNetworkData() },"turretdefense_network")
    }
}
