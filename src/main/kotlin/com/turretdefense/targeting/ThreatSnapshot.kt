package com.turretdefense.targeting

import net.minecraft.world.entity.Mob
import java.util.Collections
import java.util.IdentityHashMap

// 快照存实体身份及排序距离，不保存位置；命中与射程判断始终读取实体当前坐标。
class ThreatSnapshot(val targets: List<Mob>,val distances: DoubleArray,val tick: Long) {
    val members: MutableSet<Mob> = Collections.newSetFromMap(IdentityHashMap<Mob,Boolean>())
    init { members.addAll(targets) }
    companion object { val EMPTY = ThreatSnapshot(emptyList(),DoubleArray(0),0L) }
}
