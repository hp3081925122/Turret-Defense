package com.turretdefense.util

import net.minecraftforge.energy.IEnergyStorage

// 容量从当前等级读取；外部输入与内部扣款分别处理，避免把无线分配误认为发电。
class EnergyBuffer(private val capacity: () -> Int, private val allowed: () -> Boolean = { true }, private val changed: () -> Unit, private val received: () -> Unit = {}) : IEnergyStorage {
    var stored = 0
        set(value) { field = value.coerceIn(0,capacity()); changed() }
    override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
        if (!allowed()) return 0
        val amount = maxReceive.coerceAtLeast(0).coerceAtMost((capacity()-stored).coerceAtLeast(0))
        if (!simulate && amount > 0) { stored += amount; received() }
        return amount
    }
    override fun extractEnergy(maxExtract: Int, simulate: Boolean) = 0
    override fun getEnergyStored() = stored
    override fun getMaxEnergyStored() = capacity()
    override fun canExtract() = false
    override fun canReceive() = allowed()
    fun spend(amount: Int): Boolean {
        if (amount < 0 || stored < amount) return false
        if (amount > 0) stored -= amount
        return true
    }
    fun add(amount: Int) { if (amount > 0) stored += amount }
}
