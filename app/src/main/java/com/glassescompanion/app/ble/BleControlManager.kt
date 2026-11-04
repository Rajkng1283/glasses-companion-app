package com.glassescompanion.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class BleLink { OFF, IDLE, SCANNING, CONNECTING, DISCOVERING, READY, DISCONNECTED, ERROR }

data class BleDeviceInfo(
    val name: String = "",
    val address: String = "",
    val battery: Int? = null,
    val charging: Boolean = false,
    val firmware: String = "",
    val pidVid: String = "",
    val capacity: String = "",
    val supportMask: String = "",
)

data class BleState(
    val link: BleLink = BleLink.IDLE,
    val info: BleDeviceInfo = BleDeviceInfo(),
    val message: String = "",
    val mtu: Int = 23,
    val photoProgress: Int = 0,
)

data class ScannedGlasses(val name: String, val address: String, val rssi: Int, val advertisesAa12: Boolean)

sealed interface BleEvent {
    data class Frame(val frame: EyevueBleProtocol.Frame) : BleEvent
    data class Photo(val jpeg: ByteArray, val announced: Int) : BleEvent
    data class Raw(val characteristic: String, val bytes: ByteArray) : BleEvent
}

/**
 * BLE GATT control link to the glasses. This layer only knows about the AA12
 * control service; a READY BLE link says nothing about classic Bluetooth audio
 * or the Wi-Fi media channel, which are tracked separately.
 */
@SuppressLint("MissingPermission")
class BleControlManager(private val context: Context, private val settings: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    private val _scan = MutableStateFlow<List<ScannedGlasses>>(emptyList())
    val scanResults: StateFlow<List<ScannedGlasses>> = _scan.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val parser = EyevueBleProtocol.StreamParser()
    private val photo = EyevueBleProtocol.PhotoAssembler()
    private val opMutex = Mutex()
    private var pendingOp: CompletableDeferred<Boolean>? = null
    private var userDisconnect = false
    private var reconnectJob: Job? = null
    private var scanJob: Job? = null

    val isReady: Boolean get() = _state.value.link == BleLink.READY
    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------- scanning ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: ""
            val aa12 = result.scanRecord?.serviceUuids?.any { it.uuid == EyevueBleProtocol.SERVICE } == true
            if (name.isBlank() && !aa12) return
            val entry = ScannedGlasses(name.ifBlank { "Unknown" }, device.address, result.rssi, aa12)
            val list = _scan.value.filterNot { it.address == entry.address } + entry
            _scan.value = list.sortedWith(compareByDescending<ScannedGlasses> { it.advertisesAa12 || looksLikeGlasses(it.name) }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            setState { it.copy(link = BleLink.ERROR, message = "Scan failed (code $errorCode)") }
        }
    }

    fun looksLikeGlasses(name: String): Boolean {
        val n = name.lowercase()
        return listOf("g6", "tk802", "eyevue", "glass", "e09", "ai-").any { n.contains(it) }
    }

    fun startScan(durationMs: Long = 12_000) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            setState { it.copy(link = BleLink.OFF, message = "Bluetooth is off") }
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        _scan.value = emptyList()
        runCatching { scanner.stopScan(scanCallback) }
        val settingsScan = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settingsScan, scanCallback) }
            .onFailure { e -> setState { it.copy(link = BleLink.ERROR, message = "Scan error: ${e.message}") } }
        if (!isReady) setState { it.copy(link = BleLink.SCANNING, message = "Scanning for glasses") }
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value.link == BleLink.SCANNING) setState { it.copy(link = BleLink.IDLE, message = "Scan finished") }
    }

    // ---------------- connection ----------------

    fun connect(address: String, name: String = "") {
        val a = adapter ?: return
        stopScan()
        userDisconnect = false
        reconnectJob?.cancel()
        closeGatt()
        val device: BluetoothDevice = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: run {
            setState { it.copy(link = BleLink.ERROR, message = "Invalid address $address") }
            return
        }
        settings.update { it.copy(lastDeviceAddress = address, lastDeviceName = name.ifBlank { it.lastDeviceName }) }
        setState { it.copy(link = BleLink.CONNECTING, message = "Connecting to ${name.ifBlank { address }}", info = BleDeviceInfo(name = name, address = address)) }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connectLast(): Boolean {
        val s = settings.current
        if (s.lastDeviceAddress.isBlank() || !bluetoothEnabled) return false
        if (_state.value.link in listOf(BleLink.READY, BleLink.CONNECTING, BleLink.DISCOVERING)) return true
        connect(s.lastDeviceAddress, s.lastDeviceName)
        return true
    }

    fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        closeGatt()
        setState { it.copy(link = BleLink.IDLE, message = "Disconnected") }
    }

    fun forget() {
        disconnect()
        settings.update { it.copy(lastDeviceAddress = "", lastDeviceName = "") }
    }

    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        parser.reset()
        pendingOp?.complete(false)
    }

    private fun scheduleReconnect() {
        if (userDisconnect) return
        val address = settings.current.lastDeviceAddress.ifBlank { return }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = 2_000L
            repeat(6) {
                delay(backoff)
                if (isReady || userDisconnect) return@launch
                log("Reconnect attempt ${it + 1}")
                connect(address, settings.current.lastDeviceName)
                val ok = withTimeoutOrNull(15_000) { state.filter { s -> s.link == BleLink.READY }.first() } != null
                if (ok) return@launch
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState { it.copy(link = BleLink.DISCOVERING, message = "Discovering services") }
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                if (gatt == g) gatt = null
                writeChar = null
                pendingOp?.complete(false)
                setState { it.copy(link = BleLink.DISCONNECTED, message = "BLE disconnected (status $status)") }
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            setState { it.copy(mtu = mtu) }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(EyevueBleProtocol.SERVICE)
            if (service == null) {
                setState { it.copy(link = BleLink.ERROR, message = "Service AA12 not found: not a supported glasses unit") }
                return
            }
            writeChar = service.getCharacteristic(EyevueBleProtocol.CMD_WRITE)
            scope.launch {
                val n1 = service.getCharacteristic(EyevueBleProtocol.CMD_NOTIFY)?.let { enableNotify(g, it) } ?: false
                val n2 = service.getCharacteristic(EyevueBleProtocol.PHOTO_NOTIFY)?.let { enableNotify(g, it) } ?: false
                if (writeChar == null || !n1) {
                    setState { it.copy(link = BleLink.ERROR, message = "AA13/AA14 missing or notify failed") }
                    return@launch
                }
                setState { it.copy(link = BleLink.READY, message = if (n2) "BLE control ready" else "BLE ready (AA15 photo preview unavailable)") }
                handshake()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            onNotify(c.uuid.toString(), c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid.toString(), value)
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean = opMutex.withLock {
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(EyevueBleProtocol.CCCD) ?: return true
        val op = CompletableDeferred<Boolean>()
        pendingOp = op
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!started) return false
        withTimeoutOrNull(3_000) { op.await() } ?: false
    }

    private fun onNotify(uuid: String, bytes: ByteArray) {
        when (uuid.lowercase()) {
            EyevueBleProtocol.CMD_NOTIFY.toString() -> {
                for (frame in parser.feed(bytes)) {
                    handleFrame(frame)
                    _events.tryEmit(BleEvent.Frame(frame))
                }
            }
            EyevueBleProtocol.PHOTO_NOTIFY.toString() -> {
                val done = photo.feed(bytes)
                setState { it.copy(photoProgress = photo.receivedBytes) }
                if (done != null) {
                    log("Photo preview ${done.size} B (announced ${photo.announcedSize})")
                    _events.tryEmit(BleEvent.Photo(done, photo.announcedSize))
                }
            }
            else -> _events.tryEmit(BleEvent.Raw(uuid, bytes))
        }
    }

    private fun handleFrame(f: EyevueBleProtocol.Frame) {
        if (f.command != Cmd.APP_RECEIVE_VOICE_DATA) log("RX ${f}${if (!f.checksumOk) " (checksum mismatch)" else ""}")
        when (f.command) {
            Cmd.GET_BATTERY -> EyevueBleProtocol.parseBattery(f.payload)?.let { (lvl, chg) ->
                setState { it.copy(info = it.info.copy(battery = lvl, charging = chg)) }
            }
            Cmd.GET_DEVICE_INFO -> setState { it.copy(info = it.info.copy(firmware = EyevueBleProtocol.printable(f.payload).ifBlank { f.payload.toHex() })) }
            Cmd.CUSTOMER_PID_VID -> setState { it.copy(info = it.info.copy(pidVid = f.payload.toHex())) }
            Cmd.GET_CAPACITY -> setState { it.copy(info = it.info.copy(capacity = f.payload.toHex())) }
            Cmd.GET_SUPPORT_FUNCTION -> setState { it.copy(info = it.info.copy(supportMask = f.payload.toHex())) }
        }
    }

    private suspend fun handshake() {
        for (cmd in listOf(Cmd.GET_DEVICE_INFO, Cmd.GET_BATTERY, Cmd.GET_SUPPORT_FUNCTION, Cmd.CUSTOMER_PID_VID, Cmd.GET_CAPACITY)) {
            send(cmd)
            delay(120)
        }
    }

    // ---------------- commands ----------------

    suspend fun send(command: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        val frame = EyevueBleProtocol.encode(command, payload)
        return opMutex.withLock {
            val op = CompletableDeferred<Boolean>()
            pendingOp = op
            val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, frame, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = writeType
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            log("TX ${Cmd.name(command)} [${payload.toHex()}]${if (started) "" else " FAILED"}")
            if (!started) return@withLock false
            withTimeoutOrNull(2_000) { op.await() } ?: (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    fun sendAsync(command: Int, payload: ByteArray = ByteArray(0)) {
        scope.launch { send(command, payload) }
    }

    /** Sends a command and waits for the first reply frame with the same (or [replyCommand]) id. */
    suspend fun request(command: Int, payload: ByteArray = ByteArray(0), replyCommand: Int = command, timeoutMs: Long = 4_000): EyevueBleProtocol.Frame? {
        val deferred = CompletableDeferred<EyevueBleProtocol.Frame>()
        val job = scope.launch {
            events.filter { it is BleEvent.Frame && it.frame.command == replyCommand }.first().let {
                deferred.complete((it as BleEvent.Frame).frame)
            }
        }
        delay(10)
        if (!send(command, payload)) {
            job.cancel()
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        job.cancel()
        return result
    }

    fun refreshBattery() = sendAsync(Cmd.GET_BATTERY)

    private fun setState(block: (BleState) -> BleState) {
        _state.value = block(_state.value)
    }

    private fun log(line: String) {
        Log.d("BleControl", line)
        _log.value = (_log.value + line).takeLast(200)
    }

    @Suppress("unused")
    private fun serviceUuidFilter() = ParcelUuid(EyevueBleProtocol.SERVICE)
}
