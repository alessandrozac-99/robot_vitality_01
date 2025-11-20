package com.example.vitality.coaching

import com.example.vitality.data.ComfortClass

data class ComfortData(
    val pmv: Double?,
    val pmv2: Double?,
    val pmv3: Double?,
    val cloPred: Double?,
    val comfortClass: ComfortClass?,   // 👈 ENUM, non String
    val co2: Double?,
    val lux: Double?,
    val voc: Double?,
    val iaq: Double?,
    val sound: Double?
)
