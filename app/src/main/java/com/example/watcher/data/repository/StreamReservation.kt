package com.example.watcher.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global stream ownership coordinator for ESP32 single-connection MJPEG streams.
 *
 * ESP32-CAM only allows one active MJPEG connection. When multiple components
 * need the stream (home page monitor vs AI Fitness), this coordinator ensures
 * orderly handoff without lifecycle-based disconnection that would break
 * background monitoring tasks.
 *
 * Usage:
 * - Home page (MjpegStreamPlayer): observes [isReserved], pauses when true
 * - AI Fitness: calls [reserve("fitness")] on entry, [release("fitness")] on exit
 */
object StreamReservation {

    private val _owner = MutableStateFlow<String?>(null)

    /** Current reservation owner (null = no reservation, stream is free) */
    val owner: StateFlow<String?> = _owner.asStateFlow()

    /** Whether the stream is currently reserved by any component */
    val isReserved: Boolean get() = _owner.value != null

    /**
     * Reserve the stream for exclusive use.
     * The home page MjpegStreamPlayer will pause its connection.
     */
    fun reserve(ownerName: String) {
        _owner.value = ownerName
    }

    /**
     * Release the stream reservation. Only the current owner can release.
     * The home page MjpegStreamPlayer will automatically reconnect.
     */
    fun release(ownerName: String) {
        if (_owner.value == ownerName) {
            _owner.value = null
        }
    }
}
