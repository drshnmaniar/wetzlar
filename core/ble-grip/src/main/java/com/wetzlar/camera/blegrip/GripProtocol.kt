package com.wetzlar.camera.blegrip

import java.util.UUID

/**
 * Device-specific GATT details. The Fjorden/Kiki UUIDs and packet format are not published in
 * this project, so callers must supply a verified profile before notifications can be decoded.
 */
data class GripGattProfile(
    val serviceUuid: UUID,
    val eventCharacteristicUuid: UUID,
    val descriptorUuid: UUID = CLIENT_CHARACTERISTIC_CONFIGURATION,
    val decoder: (ByteArray) -> GripEvent = { GripEvent.RawNotification(it.copyOf()) },
) {
    companion object {
        val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** Events produced by a grip profile decoder, plus raw events for profiles not yet reverse-engineered. */
sealed interface GripEvent {
    data object HalfPress : GripEvent
    data object FullPress : GripEvent
    data object ShutterReleased : GripEvent
    data class DialChanged(val delta: Int) : GripEvent
    data class RawNotification(val bytes: ByteArray) : GripEvent
}

/** Connection lifecycle surfaced by [GripManager]. */
sealed interface GripConnectionState {
    data object Connecting : GripConnectionState
    data object Connected : GripConnectionState
    data object Disconnected : GripConnectionState
    data class Failed(val status: Int) : GripConnectionState
}
