package com.leica.lux.blegrip

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** A discovered BLE peripheral. Name and RSSI are snapshots and can be absent or stale. */
data class GripScanResult(val device: BluetoothDevice, val name: String?, val rssi: Int)

/**
 * BLE scan and GATT connection helper for a camera grip.
 *
 * The host app must request BLUETOOTH_SCAN and BLUETOOTH_CONNECT at runtime on Android 12+;
 * Android 6–11 requires location permission for BLE scanning. This class checks those permissions
 * and throws [SecurityException] with the missing permission name if the caller has not granted it.
 * Scans are foreground, time-bounded operations; this class does not start a background service.
 */
class GripManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    /** Emits scan results until [durationMillis] elapses or the collector is cancelled. */
    fun scan(
        durationMillis: Long = DEFAULT_SCAN_DURATION_MILLIS,
        serviceUuid: UUID? = null,
    ): Flow<GripScanResult> = flow {
        require(durationMillis > 0) { "durationMillis must be positive" }
        ensurePermission(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth is unavailable or disabled")
        val filters = serviceUuid?.let { listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
            ?: emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        callbackFlow {
            val stopped = AtomicBoolean(false)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    trySend(GripScanResult(result.device, result.scanRecord?.deviceName, result.rssi))
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { result ->
                        trySend(GripScanResult(result.device, result.scanRecord?.deviceName, result.rssi))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    close(IllegalStateException("BLE scan failed (code $errorCode)"))
                }
            }
            try {
                scanner.startScan(filters, settings, callback)
            } catch (error: SecurityException) {
                close(error)
            }
            val timeout = launch {
                delay(durationMillis)
                if (stopped.compareAndSet(false, true)) close()
            }
            awaitClose {
                stopped.set(true)
                timeout.cancel()
                try {
                    scanner.stopScan(callback)
                } catch (_: SecurityException) {
                    // Permission may have been revoked while the scan was active.
                }
            }
        }.collect { emit(it) }
    }

    /** Opens a GATT session. Call [GripSession.close] when the camera screen or owner is stopped. */
    fun connect(device: BluetoothDevice, profile: GripGattProfile): GripSession {
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
        val session = GripSession(profile)
        session.open(appContext, device)
        return session
    }

    private fun ensurePermission(vararg permissions: String) {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.firstOrNull { it == Manifest.permission.BLUETOOTH_SCAN }
                ?.let { arrayOf(Manifest.permission.BLUETOOTH_SCAN) }
                ?: arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.firstOrNull { it == Manifest.permission.BLUETOOTH_SCAN }
                ?.let { arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) }
                ?: emptyArray()
        }
        required.firstOrNull { ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED }
            ?.let { throw SecurityException("Missing runtime permission: $it") }
    }

    companion object {
        const val DEFAULT_SCAN_DURATION_MILLIS = 10_000L
    }
}

/** A single GATT connection. Notifications are exposed as cold-safe shared flows. */
class GripSession internal constructor(private val profile: GripGattProfile) : AutoCloseable {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<GripEvent>(extraBufferCapacity = 32)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<GripConnectionState>(GripConnectionState.Connecting)
    private var gatt: BluetoothGatt? = null

    val events: Flow<GripEvent> = _events
    val state: Flow<GripConnectionState> = _state

    @Suppress("MissingPermission")
    internal fun open(context: Context, device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> _state.value = GripConnectionState.Failed(status)
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        _state.value = GripConnectionState.Connected
                        try {
                            gatt.discoverServices()
                        } catch (_: SecurityException) {
                            _state.value = GripConnectionState.Failed(STATUS_PERMISSION_REVOKED)
                        }
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> _state.value = GripConnectionState.Disconnected
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.value = GripConnectionState.Failed(status)
                    return
                }
                val characteristic = gatt.getService(profile.serviceUuid)
                    ?.getCharacteristic(profile.eventCharacteristicUuid)
                if (characteristic == null) {
                    _state.value = GripConnectionState.Failed(STATUS_PROFILE_MISMATCH)
                    return
                }
                try {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(profile.descriptorUuid)
                        ?: run { _state.value = GripConnectionState.Failed(STATUS_PROFILE_MISMATCH); return }
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } catch (_: SecurityException) {
                    _state.value = GripConnectionState.Failed(STATUS_PERMISSION_REVOKED)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == profile.eventCharacteristicUuid) {
                    characteristic.value?.let { decodeAndEmit(it) }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == profile.eventCharacteristicUuid) decodeAndEmit(value)
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun decodeAndEmit(bytes: ByteArray) {
        runCatching { profile.decoder(bytes.copyOf()) }
            .onSuccess { _events.tryEmit(it) }
            .onFailure { _events.tryEmit(GripEvent.RawNotification(bytes.copyOf())) }
    }

    @Suppress("MissingPermission")
    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _state.value = GripConnectionState.Disconnected
    }

    companion object {
        private const val STATUS_PROFILE_MISMATCH = -1001
        private const val STATUS_PERMISSION_REVOKED = -1002
    }
}
