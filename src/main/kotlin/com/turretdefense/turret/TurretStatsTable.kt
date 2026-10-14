package com.turretdefense.turret

// 首期数值只在本表定义，等级索引固定为 T1 至 T5。
data class TurretStats(
    val health: Double, val shield: Int, val damage: Double,
    val cooldownTicks: Int, val range: Double, val energy: Double,
    val aoeRadius: Double = 0.0, val chainCount: Int = 0,
    val jumpRange: Double = 0.0, val pierceCount: Int = 0
)

data class CoreStats(
    val health: Double, val shield: Int, val energy: Int, val generation: Int,
    val range: Double, val repair: Double, val projection: Int,
    val deflectionRange: Double, val judgmentDamage: Double,
    val judgmentCooldown: Int, val judgmentEnergy: Int
)

object TurretStatsTable {
    val CORE = listOf(
        CoreStats(100.0,20,10000,32,32.0,1.0,5,16.0,40.0,300,200),
        CoreStats(150.0,30,20000,48,48.0,2.0,8,24.0,60.0,280,300),
        CoreStats(200.0,40,40000,64,64.0,3.0,12,32.0,90.0,260,450),
        CoreStats(275.0,55,80000,96,96.0,5.0,18,48.0,130.0,240,650),
        CoreStats(350.0,70,160000,128,128.0,8.0,25,64.0,180.0,200,900)
    )
    val PULSE = listOf(
        TurretStats(20.0,10,8.0,50,16.0,6.0,pierceCount=1),
        TurretStats(30.0,15,12.0,46,20.0,10.0,pierceCount=1),
        TurretStats(40.0,20,18.0,42,24.0,15.0,pierceCount=1),
        TurretStats(55.0,28,28.0,38,28.0,22.0,pierceCount=1),
        TurretStats(70.0,36,42.0,34,32.0,32.0,pierceCount=1)
    )
    val PARTICLE = listOf(
        TurretStats(16.0,8,2.0,10,14.0,3.0), TurretStats(24.0,12,3.0,9,18.0,4.0),
        TurretStats(32.0,16,5.0,8,22.0,6.0), TurretStats(44.0,22,7.0,7,26.0,9.0),
        TurretStats(56.0,30,10.0,6,30.0,13.0)
    )
    val GRENADE = listOf(
        TurretStats(20.0,8,6.0,60,20.0,12.0,2.0), TurretStats(30.0,12,9.0,56,24.0,18.0,2.5),
        TurretStats(40.0,16,14.0,52,28.0,26.0,3.0), TurretStats(55.0,22,20.0,48,32.0,36.0,3.5),
        TurretStats(70.0,30,28.0,44,36.0,48.0,4.0)
    )
    // 激光原表为每秒数值，结算时按实际经过的 tick 比例计算，避免浮点能量取整丢失。
    val LASER = listOf(
        TurretStats(18.0,8,4.0,2,14.0,10.0), TurretStats(27.0,12,6.0,2,16.0,15.0),
        TurretStats(36.0,16,9.0,2,18.0,22.0), TurretStats(50.0,22,14.0,2,20.0,32.0),
        TurretStats(64.0,30,20.0,2,22.0,45.0)
    )
    val ARC = listOf(
        TurretStats(18.0,8,5.0,40,14.0,8.0,chainCount=2,jumpRange=6.0),
        TurretStats(27.0,12,7.0,38,16.0,12.0,chainCount=2,jumpRange=6.0),
        TurretStats(36.0,16,10.0,36,18.0,18.0,chainCount=3,jumpRange=8.0),
        TurretStats(50.0,22,15.0,34,20.0,26.0,chainCount=3,jumpRange=8.0),
        TurretStats(64.0,30,22.0,32,22.0,35.0,chainCount=4,jumpRange=10.0)
    )
    val FACE_LIMITS = intArrayOf(1,2,3,4,6)
    val CORE_UPGRADE_ENERGY = intArrayOf(10000,30000,80000,200000)
    val HEAD_UPGRADE_ENERGY = intArrayOf(2000,5000,12000,25000)
    const val IDLE_ENERGY_PER_TICK = 1
    const val SHIELD_ENERGY_PER_TICK = 5
    const val REPAIR_ENERGY_PER_SECOND = 50
    const val SELF_CHARGE_INTERVAL = 20
    const val SELF_CHARGE_ENERGY = 1
    const val DISTRIBUTION_INTERVAL = 5
    const val MAINTENANCE_INTERVAL = 100
    const val WARNING_INTERVAL = 600
    const val SOUND_DISTANCE = 48.0
    const val ARC_FALLOFF = 0.8
    const val MODULE_DAMAGE_MULTIPLIER = 1.25
    const val MODULE_COOLDOWN_MULTIPLIER = 0.8
    const val MODULE_ENERGY_MULTIPLIER = 0.8
    const val MODULE_ARMOR_MULTIPLIER = 0.8
    const val MODULE_SHIELD_BONUS = 10
    const val OVERLOAD_DURATION = 200L
    const val OVERHEAT_DURATION = 200L
    const val OVERLOAD_DAMAGE = 1.5
    const val OVERLOAD_RATE = 2.0
    const val SELF_DESTRUCT_RADIUS = 6.0
    const val SELF_DESTRUCT_DAMAGE = 40.0
    const val JUDGMENT_RADIUS = 10.0
    const val JUDGMENT_COUNT = 10
    const val JUDGMENT_HEALTH = 200.0
    const val JUDGMENT_T4_MULTIPLIER = 1.5
    const val JUDGMENT_T5_RADIUS_MULTIPLIER = 2.0
    const val MARTYR_RADIUS = 64.0
    const val MARTYR_INTERVAL = 10
    const val MAX_BLAST_TARGETS = 64
    const val MAX_CHAIN_DEPTH = 3
    const val KNOCKBACK = 0.4
    const val MONSTER_TARGET_RANGE = 16.0
    const val MONSTER_ATTACK_INTERVAL = 20
    const val CHUNKS_PER_TICK = 4
}

// 类型顺序在存档发布后保持稳定；五个方块共用行为策略与方块实体类。
sealed class TurretKind(val id: String, val stats: List<TurretStats>, val ammo4: String?, val ammo5: String?) {
    object Pulse : TurretKind("pulse",TurretStatsTable.PULSE,"ammo_iron_slug","ammo_pulse_core")
    object Particle : TurretKind("particle",TurretStatsTable.PARTICLE,"ammo_particle_clip","ammo_hv_particle_clip")
    object Grenade : TurretKind("grenade",TurretStatsTable.GRENADE,"ammo_grenade","ammo_he_grenade")
    object Laser : TurretKind("laser",TurretStatsTable.LASER,null,null)
    object Arc : TurretKind("arc",TurretStatsTable.ARC,"ammo_arc_cell","ammo_hv_arc_cell")
    companion object {
        val ALL = listOf(Pulse,Particle,Grenade,Laser,Arc)
        fun byId(id: String) = ALL.firstOrNull { it.id == id }
    }
}
