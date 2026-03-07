package com.projekt_x.studybuddy.bridge

/**
 * Interface for Speech-to-Text (STT) bridges
 */
interface STTBridgeInterface {
    fun isAvailable(): Boolean
    fun getName(): String
}
