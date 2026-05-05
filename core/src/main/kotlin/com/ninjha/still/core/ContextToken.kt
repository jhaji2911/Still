package com.ninjha.still.core

enum class MotionState {
    Still,
    MicroVibration,
    HighMotion
}

enum class PhysioStress {
    Resting,
    Aerobic,
    Anaerobic,
    Unknown
}

enum class EnviroDecibel {
    Silent,
    Rhythmic,
    Chaotic
}

enum class DevicePlace {
    Pocket,
    FaceDown,
    ChargingStand,
    InHand
}

enum class Peripheral {
    Headphones,
    CarBluetooth,
    None
}

data class ContextToken(
    val motion: MotionState,
    val physioStress: PhysioStress,
    val ambientAudio: EnviroDecibel,
    val devicePlace: DevicePlace,
    val peripheral: Peripheral,
    val heartRateBpm: Int? = null,
    val isDeviceLocked: Boolean = false,
    val isCharging: Boolean = false,
    val sessionSteps: Int = 0,
)
