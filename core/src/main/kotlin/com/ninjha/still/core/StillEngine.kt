package com.ninjha.still.core

class StillEngine {
    fun infer(token: ContextToken): StillState {
        val candidates = listOf(
            scoreAway(token),
            scoreGym(token),
            scoreMeditating(token),
            scoreCommuting(token),
            scoreDeepWork(token),
            scoreAvailable(token),
        )

        val best = candidates.maxBy { it.rawScore }
        val confidence = (best.rawScore / 5f).coerceIn(0.2f, 0.98f)

        return StillState(
            label = best.label,
            confidence = confidence,
            estimatedReturnMinutes = best.returnMinutes,
            autoReply = "${best.intent} ${best.returnText}",
            reasons = best.reasons,
        )
    }

    private fun scoreAway(token: ContextToken): Candidate {
        var score = 0
        val reasons = mutableListOf<String>()
        if (token.isDeviceLocked) {
            score += 3
            reasons += "phone locked"
        }
        if (token.isCharging) {
            score += 2
            reasons += "charging"
        }
        if (token.devicePlace == DevicePlace.ChargingStand || token.devicePlace == DevicePlace.FaceDown) {
            score += 1
            reasons += "phone set aside"
        }
        return Candidate(
            label = "Away",
            intent = "Nishant appears away from his phone.",
            returnText = "He'll respond when he picks it back up.",
            returnMinutes = 30,
            rawScore = score,
            reasons = reasons,
        )
    }

    private fun scoreGym(token: ContextToken): Candidate {
        var score = 0
        val reasons = mutableListOf<String>()
        if (token.motion == MotionState.Still || token.motion == MotionState.MicroVibration) {
            score += 1
            reasons += "controlled motion"
        }
        if ((token.heartRateBpm ?: 0) >= 110 || token.physioStress == PhysioStress.Aerobic) {
            score += 2
            reasons += "elevated heart rate"
        }
        if (token.ambientAudio == EnviroDecibel.Rhythmic) {
            score += 1
            reasons += "rhythmic audio"
        }
        if (token.peripheral == Peripheral.Headphones) {
            score += 1
            reasons += "headphones connected"
        }
        return Candidate(
            label = "Training",
            intent = "Nishant is in a focused workout block.",
            returnText = "He'll check his phone in about 10 mins.",
            returnMinutes = 10,
            rawScore = score,
            reasons = reasons,
        )
    }

    private fun scoreMeditating(token: ContextToken): Candidate {
        var score = 0
        val reasons = mutableListOf<String>()
        if (token.devicePlace == DevicePlace.InHand) {
            score -= 2
        }
        if (token.motion == MotionState.Still) {
            score += 1
            reasons += "still body"
        }
        if (token.ambientAudio == EnviroDecibel.Silent) {
            score += 1
            reasons += "quiet environment"
        }
        if (token.devicePlace == DevicePlace.FaceDown) {
            score += 2
            reasons += "phone face down"
        }
        if (token.physioStress == PhysioStress.Resting || (token.heartRateBpm ?: 999) < 85) {
            score += 1
            reasons += "resting physiology"
        }
        return Candidate(
            label = "Meditating",
            intent = "Nishant is likely in a quiet focus or meditation session.",
            returnText = "He'll respond in about 20 mins.",
            returnMinutes = 20,
            rawScore = score,
            reasons = reasons,
        )
    }

    private fun scoreCommuting(token: ContextToken): Candidate {
        var score = 0
        val reasons = mutableListOf<String>()
        if (token.motion == MotionState.HighMotion || token.motion == MotionState.MicroVibration) {
            score += 2
            reasons += "vehicle-like vibration"
        }
        if (token.sessionSteps >= 20) {
            score -= 2
        }
        if (token.peripheral == Peripheral.CarBluetooth) {
            score += 2
            reasons += "car bluetooth connected"
        }
        if (token.ambientAudio == EnviroDecibel.Chaotic) {
            score += 1
            reasons += "traffic-like audio"
        }
        return Candidate(
            label = "Commuting",
            intent = "Nishant is likely commuting.",
            returnText = "He'll get back once he parks.",
            returnMinutes = 30,
            rawScore = score,
            reasons = reasons,
        )
    }

    private fun scoreDeepWork(token: ContextToken): Candidate {
        var score = 0
        val reasons = mutableListOf<String>()
        if (token.motion == MotionState.Still) {
            score += 1
            reasons += "low movement"
        }
        if (token.devicePlace == DevicePlace.ChargingStand || token.devicePlace == DevicePlace.FaceDown) {
            score += 1
            reasons += "phone set aside"
        }
        if (token.ambientAudio == EnviroDecibel.Silent || token.ambientAudio == EnviroDecibel.Rhythmic) {
            score += 1
            reasons += "stable audio"
        }
        if (token.peripheral == Peripheral.Headphones) {
            score += 1
            reasons += "headphones connected"
        }
        if (token.sessionSteps >= 20) {
            score += 1
            reasons += "recent steps"
        }
        return Candidate(
            label = "Deep work",
            intent = "Nishant is likely in deep work.",
            returnText = "He'll reply when he comes up for air in about 45 mins.",
            returnMinutes = 45,
            rawScore = score,
            reasons = reasons,
        )
    }

    private fun scoreAvailable(token: ContextToken): Candidate {
        var score = 1
        val reasons = mutableListOf("fallback state")
        if (token.devicePlace == DevicePlace.InHand && !token.isDeviceLocked) {
            score += 4
            reasons += "phone in hand"
        }
        if (token.physioStress == PhysioStress.Resting && token.ambientAudio != EnviroDecibel.Chaotic) {
            score += 1
            reasons += "low stress"
        }
        return Candidate(
            label = "Available",
            intent = "Nishant seems available but may not be looking at his phone.",
            returnText = "He'll reply soon.",
            returnMinutes = 5,
            rawScore = score,
            reasons = reasons,
        )
    }

    private data class Candidate(
        val label: String,
        val intent: String,
        val returnText: String,
        val returnMinutes: Int,
        val rawScore: Int,
        val reasons: List<String>,
    )
}
