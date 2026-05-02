package com.jabberrock.simplewebcam

import kotlinx.serialization.Serializable

@Serializable
data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
)
