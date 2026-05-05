package com.ninjha.still.core

data class StillState(
    val label: String,
    val confidence: Float,
    val estimatedReturnMinutes: Int,
    val autoReply: String,
    val reasons: List<String>,
)
