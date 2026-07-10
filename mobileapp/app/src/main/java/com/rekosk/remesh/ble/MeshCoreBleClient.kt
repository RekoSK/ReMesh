package com.rekosk.remesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "MeshCoreBle"

/** Command/response timeout. The protocol doc recommends 5s. */
private const val COMMAND_TIMEOUT_MS = 5_000L
private const val CONNECT_TIMEOUT_MS = 20_000L

/** The user has to read a PIN off the node's screen and type it in. */
private const val BOND_TIMEOUT_MS = 90_000L

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState

    /** Waiting for the user to enter the node's BLE PIN. */
    data object Bonding : ConnectionState
    data object Discovering : ConnectionState
    data class Ready(val deviceName: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * Speaks the MeshCore companion protocol over the Nordic UART service.
 *
 * The firmware exposes both characteristics with `ENC_MITM` permissions, so the
 * link must be bonded with a passkey before any GATT traffic is allowed. We bond
 * up front rather than letting a failed write trigger it, because an
 * authentication retry mid-command is far harder to reason about.
 *
 * One command is in flight at a time ([commandMutex]); the firmware's contact
 * iterator is stateful and will answer `ERR_CODE_BAD_STATE` to a concurrent
 * `CMD_GET_CONTACTS`.
 */
@SuppressLint("MissingPermission")
class MeshCoreBleClient(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    /**
     * Asynchronous frames from the node (adverts, MSG_WAITING, acks).
     *
     * Generously buffered because LOG_RX_DATA carries *every* packet the radio hears,
     * and `tryEmit` drops rather than suspends: a busy channel must not evict the one
     * push that would confirm a message we just sent.
     */
    private val _pushes = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 256)
    val pushes: SharedFlow<MeshFrame> = _pushes.asSharedFlow()

    // Assigned on the binder thread in onServicesDiscovered, read when sending commands.
    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var rxChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var txChar: BluetoothGattCharacteristic? = null

    private val commandMutex = Mutex()

    /**
     * Frames collected for the command currently in flight. Written from the
     * coroutine that issues the command, read on the BLE binder thread that
     * delivers notifications, so it needs a memory barrier.
     */
    @Volatile
    private var pending: PendingCommand? = null

    // Completed from gattCallback (binder thread), awaited from connect() (coroutine).
    @Volatile
    private var servicesDiscovered: CompletableDeferred<Boolean>? = null

    @Volatile
    private var notificationsEnabled: CompletableDeferred<Boolean>? = null

    @Volatile
    private var connected: CompletableDeferred<Boolean>? = null

    private class PendingCommand(
        val isTerminal: (MeshFrame) -> Boolean,
        val result: CompletableDeferred<List<MeshFrame>>,
    ) {
        val frames = mutableListOf<MeshFrame>()
    }

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------- scanning ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = DiscoveredDevice(
                address = result.device.address,
                name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull(),
                rssi = result.rssi,
            )
            _devices.update { current ->
                // Refresh RSSI in place rather than accumulating duplicates.
                val without = current.filterNot { it.address == device.address }
                (without + device).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            _state.value = ConnectionState.Failed("Bluetooth scan failed (code $errorCode)")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Failed("Bluetooth is off")
            return
        }
        _devices.value = emptyList()
        _state.value = ConnectionState.Scanning
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshCoreProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value is ConnectionState.Scanning) _state.value = ConnectionState.Disconnected
    }

    // ---------------- bonding ----------------

    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        _state.value = ConnectionState.Bonding
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            if (!device.createBond()) throw MeshBleException("Could not start pairing")
            val ok = withTimeout(BOND_TIMEOUT_MS) { bonded.await() }
            if (!ok) throw MeshBleException("Pairing rejected or wrong PIN")
        } catch (e: TimeoutCancellationException) {
            throw MeshBleException("Timed out waiting for pairing")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // ---------------- connection ----------------

    suspend fun connect(address: String) {
        stopScan()
        disconnect()

        val device = adapter?.getRemoteDevice(address)
            ?: throw MeshBleException("Unknown device $address")

        try {
            ensureBonded(device)

            _state.value = ConnectionState.Connecting
            connected = CompletableDeferred()
            servicesDiscovered = CompletableDeferred()
            notificationsEnabled = CompletableDeferred()

            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            withTimeout(CONNECT_TIMEOUT_MS) {
                if (!connected!!.await()) throw MeshBleException("Connection dropped")
                _state.value = ConnectionState.Discovering
                if (!servicesDiscovered!!.await()) throw MeshBleException("MeshCore service not found")
                if (!notificationsEnabled!!.await()) throw MeshBleException("Could not subscribe to notifications")
            }
            _state.value = ConnectionState.Ready(device.name ?: address)
        } catch (e: TimeoutCancellationException) {
            disconnect()
            throw MeshBleException("Timed out connecting to ${device.name ?: address}")
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    fun disconnect() {
        pending?.result?.completeExceptionally(MeshBleException("Disconnected"))
        pending = null
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        rxChar = null
        txChar = null
        if (_state.value !is ConnectionState.Failed) _state.value = ConnectionState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(true)
                // 176 is the firmware's frame cap; ask for plenty and let it negotiate down.
                g.requestMtu(517)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connected?.complete(false)
                servicesDiscovered?.complete(false)
                notificationsEnabled?.complete(false)
                pending?.result?.completeExceptionally(MeshBleException("Disconnected"))
                pending = null
                if (_state.value is ConnectionState.Ready) {
                    _state.value = ConnectionState.Disconnected
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU now $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(MeshCoreProtocol.SERVICE_UUID)
            if (service == null) {
                servicesDiscovered?.complete(false)
                return
            }
            rxChar = service.getCharacteristic(MeshCoreProtocol.RX_CHAR_UUID)
            txChar = service.getCharacteristic(MeshCoreProtocol.TX_CHAR_UUID)
            if (rxChar == null || txChar == null) {
                servicesDiscovered?.complete(false)
                return
            }
            servicesDiscovered?.complete(true)

            g.setCharacteristicNotification(txChar, true)
            val cccd = txChar!!.getDescriptor(MeshCoreProtocol.CCCD_UUID)
            if (cccd == null) {
                notificationsEnabled?.complete(false)
                return
            }
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == MeshCoreProtocol.CCCD_UUID) {
                notificationsEnabled?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != MeshCoreProtocol.TX_CHAR_UUID) return
            onFrame(MeshCoreProtocol.decode(value))
        }
    }

    /** One BLE notification is exactly one protocol frame (BaseSerialInterface.h). */
    private fun onFrame(frame: MeshFrame) {
        if (MeshCoreProtocol.isPush(frame.code)) {
            _pushes.tryEmit(frame)
            return
        }
        val current = pending
        if (current == null) {
            Log.w(TAG, "unsolicited frame 0x%02X".format(frame.code))
            return
        }
        current.frames += frame
        if (current.isTerminal(frame)) {
            pending = null
            current.result.complete(current.frames.toList())
        }
    }

    // ---------------- commands ----------------

    /**
     * Writes [command] and collects reply frames until [isTerminal] says stop.
     *
     * Multi-frame replies are normal: `CMD_GET_CONTACTS` answers with
     * `CONTACTS_START`, N × `CONTACT`, then `END_OF_CONTACTS`.
     */
    suspend fun command(
        command: ByteArray,
        isTerminal: (MeshFrame) -> Boolean,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): List<MeshFrame> = commandMutex.withLock {
        val g = gatt ?: throw MeshBleException("Not connected")
        val rx = rxChar ?: throw MeshBleException("Not connected")
        require(command.size <= MeshCoreProtocol.MAX_FRAME_SIZE) {
            "frame of ${command.size} bytes exceeds MAX_FRAME_SIZE"
        }

        val result = CompletableDeferred<List<MeshFrame>>()
        pending = PendingCommand(isTerminal, result)

        val status = g.writeCharacteristic(
            rx,
            command,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pending = null
            throw MeshBleException("Write failed (status $status)")
        }

        try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            pending = null
            throw MeshBleException("Node did not answer command 0x%02X".format(command[0]))
        }
    }

    /** Convenience for commands whose reply is a single frame. */
    suspend fun commandSingle(command: ByteArray, timeoutMs: Long = COMMAND_TIMEOUT_MS): MeshFrame =
        command(command, isTerminal = { true }, timeoutMs = timeoutMs).first()
}

class MeshBleException(message: String) : Exception(message)
