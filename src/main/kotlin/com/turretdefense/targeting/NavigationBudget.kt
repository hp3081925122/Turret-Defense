package com.turretdefense.targeting

import net.minecraft.world.entity.Mob
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

// 原版寻路不可分帧执行，因此限制发起次数与累计耗时，并轮转服务等待中的怪物。
class NavigationBudget {
    private val waiting = ArrayDeque<Mob>()
    private val queued = Collections.newSetFromMap(IdentityHashMap<Mob,Boolean>())
    private val permits = Collections.newSetFromMap(IdentityHashMap<Mob,Boolean>())
    private var requests = 0
    private var nanos = 0L
    fun beginTick() {
        requests = 0; nanos = 0; permits.clear()
        while (waiting.isNotEmpty() && permits.size < 4) { val mob = waiting.removeFirst(); queued.remove(mob); if (mob.isAlive && !mob.isRemoved) permits.add(mob) }
    }
    fun acquire(mob: Mob): Boolean {
        if (nanos < 1_000_000L && requests < 4 && (permits.contains(mob) || requests+permits.size < 4)) { permits.remove(mob); requests++; return true }
        permits.remove(mob)
        if (queued.add(mob)) waiting.addLast(mob)
        return false
    }
    fun finish(cost: Long) { nanos += cost }
    fun release(mob: Mob) { permits.remove(mob) }
    fun clear() { waiting.clear(); queued.clear(); permits.clear() }
}
