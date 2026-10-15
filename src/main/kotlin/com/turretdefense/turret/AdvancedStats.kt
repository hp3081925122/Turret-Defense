package com.turretdefense.turret

// 后续炮塔使用与首期相同的五级表，持续束伤害与能耗均为每秒。
object AdvancedStats {
    val GRAVITY = listOf(
        TurretStats(24.0,10,8.0,80,32.0,15.0,aoeRadius=3.0,minRange=8.0),
        TurretStats(36.0,15,12.0,76,40.0,22.0,aoeRadius=3.0,minRange=8.0),
        TurretStats(48.0,20,18.0,70,48.0,30.0,aoeRadius=4.0,minRange=8.0),
        TurretStats(66.0,28,26.0,64,56.0,42.0,aoeRadius=4.0,minRange=8.0),
        TurretStats(84.0,36,36.0,60,64.0,55.0,aoeRadius=5.0,minRange=8.0)
    )
    val RAILGUN = listOf(
        TurretStats(22.0,10,10.0,80,24.0,18.0,pierceCount=2,armorPenetration=1.0),
        TurretStats(33.0,15,15.0,76,28.0,26.0,pierceCount=3,armorPenetration=1.0),
        TurretStats(44.0,20,22.0,72,32.0,36.0,pierceCount=4,armorPenetration=1.0),
        TurretStats(60.0,28,32.0,68,36.0,48.0,pierceCount=5,armorPenetration=1.0),
        TurretStats(76.0,36,45.0,64,40.0,62.0,pierceCount=6,armorPenetration=1.0)
    )
    val PHASE = listOf(
        TurretStats(20.0,12,8.0,30,12.0,8.0),
        TurretStats(30.0,18,12.0,28,14.0,12.0),
        TurretStats(40.0,24,18.0,26,16.0,18.0),
        TurretStats(55.0,32,26.0,24,18.0,26.0),
        TurretStats(70.0,42,36.0,22,20.0,35.0)
    )
    val SNIPER = listOf(
        TurretStats(16.0,6,20.0,120,40.0,25.0,pierceCount=1),
        TurretStats(24.0,9,30.0,116,48.0,35.0,pierceCount=1),
        TurretStats(32.0,12,45.0,112,56.0,48.0,pierceCount=1),
        TurretStats(44.0,18,65.0,108,64.0,65.0,pierceCount=1),
        TurretStats(56.0,24,90.0,100,72.0,85.0,pierceCount=1)
    )
    val ARBITER = listOf(
        TurretStats(22.0,10,6.0,36,18.0,10.0),
        TurretStats(33.0,15,9.0,34,22.0,15.0),
        TurretStats(44.0,20,14.0,32,26.0,22.0),
        TurretStats(60.0,28,20.0,30,30.0,32.0),
        TurretStats(76.0,36,28.0,28,34.0,45.0)
    )
    val FUSION = listOf(
        TurretStats(24.0,10,3.0,2,14.0,8.0),
        TurretStats(36.0,15,4.0,2,16.0,12.0),
        TurretStats(48.0,20,6.0,2,18.0,18.0),
        TurretStats(66.0,28,9.0,2,20.0,26.0),
        TurretStats(84.0,36,13.0,2,22.0,35.0)
    )
    val ACID = listOf(
        TurretStats(20.0,8,3.0,40,16.0,8.0),
        TurretStats(30.0,12,5.0,38,20.0,12.0),
        TurretStats(40.0,16,8.0,36,24.0,18.0),
        TurretStats(55.0,22,12.0,34,28.0,26.0),
        TurretStats(70.0,30,18.0,32,32.0,35.0)
    )
    val CRYO = listOf(
        TurretStats(18.0,8,3.0,40,14.0,6.0),
        TurretStats(27.0,12,5.0,38,16.0,9.0),
        TurretStats(36.0,16,8.0,36,18.0,14.0),
        TurretStats(50.0,22,12.0,34,20.0,20.0),
        TurretStats(64.0,30,18.0,32,22.0,28.0)
    )
    val EMP = listOf(
        TurretStats(22.0,10,2.0,60,12.0,12.0),
        TurretStats(33.0,15,3.0,58,14.0,18.0),
        TurretStats(44.0,20,5.0,56,16.0,26.0),
        TurretStats(60.0,28,8.0,54,18.0,36.0),
        TurretStats(76.0,36,12.0,52,20.0,48.0)
    )
    val GRAVITON = listOf(
        TurretStats(24.0,10,4.0,80,16.0,18.0),
        TurretStats(36.0,15,6.0,76,18.0,26.0),
        TurretStats(48.0,20,9.0,72,20.0,36.0),
        TurretStats(66.0,28,14.0,68,22.0,48.0),
        TurretStats(84.0,36,20.0,64,24.0,62.0)
    )
}
