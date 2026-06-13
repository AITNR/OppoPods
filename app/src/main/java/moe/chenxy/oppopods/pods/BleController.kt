package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.hook.Log
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE GATT controller for ROG Cetra TWS SpeedNova earbuds using Airoha RACE protocol.
 *
 * This controller replaces the RFCOMM-based [RfcommController] for BLE-only devices.
 * It communicates via the Airoha RACE custom BLE GATT service (TX/RX characteristics)
 * for ANC control and battery queries, and falls back to the standard Battery Service (BAS)
 * for single-ear battery reading without pairing.
 *
 * Architecture mirrors [RfcommController]: same broadcast-based UI communication,
 * same BatteryParams/AncMode data model, same action constants from [OppoPodsAction].
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object BleController {
    private const val TAG = "OppoPods-BleController"
    private const val AUTO_RECONNECT_DELAY_MS = 120_000L
    private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L
    private const val RESPONSE_TIMEOUT_MS = 3000L

    // ── BLE GATT UUIDs ──
    private val TX_UUID: UUID = UUID.fromString(RaceBleUuids.TX)
    private val RX_UUID: UUID = UUID.fromString(RaceBleUuids.RX)
    private val BAS_LEVEL_UUID: UUID = UUID.fromString(RaceBleUuids.BAS_LEVEL)
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── Context & Device ──
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    // ── BLE GATT state ──
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private var isRaceReady = false

    // ── Current state ──
    private var currentAnc: Int = 1  // 1=off, 2=nc, 3=transparency, 4=adaptive, 5-8=intensity
    private var currentTransparencyVocalEnhancement = false
    private var cachedDeviceName: String = ""
    lateinit var currentBatteryParams: BatteryParams
    private var lastTempBatt = 0
    private var mShowedConnectedToast = false
    private var receiverRegistered = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L
    private var autoGameModeEnabled = false
    private var gameModeImplementation: GameModeImplementation = GameModeImplementation.STANDARD
    private var currentGameMode = false
    private var currentSpatialAudioMode = SpatialAudioMode.OFF
    private var currentDualDeviceConnection = false
    private var currentEqPreset = -1

    // ── Coroutines ──
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectPending = false

    // ── Response matching ──
    private val pendingResponses = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val responseLock = Object()

    // ── Status snapshot (mirrors RfcommController) ──
    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
        val reconnectPending: Boolean,
    )

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
            anc = currentAnc,
            transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName.takeIf { it.isNotEmpty() },
            connected = isBleConnected && isRaceReady,
            connecting = connectionJob?.isActive == true,
            reconnectPending = reconnectPending,
        )
    }

    // ══════════════════════════════════════════════
    // Broadcast-based UI communication
    // ══════════════════════════════════════════════

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                markAppUiActive()
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIAncStatus(currentAnc)
                changeUIGameModeStatus(currentGameMode)
                changeUITransparencyVocalEnhancementStatus(currentTransparencyVocalEnhancement)
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                changeUIEqPreset(currentEqPreset)
                changeUIDualDeviceConnectionStatus(currentDualDeviceConnection)
                if (::mDevice.isInitialized && isBleConnected) {
                    sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                }
            }
            OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                appUiActive = false
                appUiActiveUntilMs = 0L
                Log.i(TAG, "UI Closed")
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus(immediateReconnect = true)
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setGameMode(enabled)
            }
            OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setTransparencyVocalEnhancement(enabled)
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                Log.d(TAG, "Config synced")
            }
            // Forward other actions as needed
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET,
            OppoPodsAction.ACTION_EQ_PRESET_SET,
            OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET,
            OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED,
            OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED -> {
                // These features are not supported via RACE protocol on ROG Cetra
                Log.d(TAG, "Action not supported on BLE/RACE: ${intent.action}")
            }
        }
    }

    // ── UI broadcast helpers (mirror RfcommController) ──

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private fun changeUIConnectionState(state: String) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            putExtra("state", state)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            this.putExtra("enabled", enabled)
        }
    }

    private fun changeUITransparencyVocalEnhancementStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            this.putExtra("enabled", enabled)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            this.putExtra("mode", mode)
        }
    }

    private fun changeUIEqPreset(presetId: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            this.putExtra("preset", presetId)
        }
    }

    private fun changeUIDualDeviceConnectionStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED) {
            this.putExtra("enabled", enabled)
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        if (!isAppUiActive()) return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        Log.d(TAG, "app UI active timeout, stop app status broadcasts")
        return false
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }

    private fun currentConnectionState(): String = when {
        isBleConnected && isRaceReady -> "connected"
        connectionJob?.isActive == true || reconnectPending -> "connecting"
        isBleConnected -> "connecting"
        else -> "disconnected"
    }

    // ══════════════════════════════════════════════
    // BLE GATT connection management
    // ══════════════════════════════════════════════

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        closeGatt()
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        if (appRequested) {
            markAppUiActive()
        }
        autoGameModeEnabled = prefs.getBoolean("auto_game_mode", false)
        gameModeImplementation = GameModeImplementation.fromPreference(
            prefs.getString(GameModeImplementation.PREF_KEY, null)
        )

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                addAction(OppoPodsAction.ACTION_ANC_SELECT)
                addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
                addAction(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET)
                addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
                addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                addAction(OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET)
                addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
                addAction(OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED)
                addAction(OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        isBleConnected = true
        changeUIConnectionState("connecting")
        connectBle(initialDelayMs = 500L)
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isBleConnected = false
        isRaceReady = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts.set(0)
        reconnectPending = false
        closeGatt()

        mContext?.let {
            if (receiverRegistered) {
                try { it.unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
                receiverRegistered = false
            }
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", device.address)
            }
        }

        mShowedConnectedToast = false
        currentAnc = 1
        currentGameMode = false
        currentTransparencyVocalEnhancement = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        currentEqPreset = -1
        currentDualDeviceConnection = false
        changeUIConnectionState("disconnected")
        cachedDeviceName = ""
        mContext = null
    }

    private fun connectBle(initialDelayMs: Long = 0L) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            if (!isBleConnected || !::mDevice.isInitialized) return@launch
            closeGatt()
            try {
                Log.d(TAG, "Connecting BLE GATT to ${mDevice.address}")
                val ctx = mContext ?: return@launch
                gatt = mDevice.connectGatt(ctx, false, gattCallback)
                // Wait for connection callback
                delay(10_000)
                if (gatt == null || !isBleConnected) {
                    Log.e(TAG, "BLE GATT connect timeout")
                    scheduleReconnect("connect timeout")
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE GATT connect failed", e)
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!isBleConnected || !::mDevice.isInitialized || mContext == null) return
        Log.w(TAG, "schedule reconnect reason=$reason immediate=$immediate")
        closeGatt()
        reconnectPending = true
        if (immediate) {
            if (connectionJob?.isActive == true) {
                Log.d(TAG, "immediate BLE reconnect skipped: connecting reason=$reason")
                return
            }
            Log.d(TAG, "immediate BLE reconnect reason=$reason")
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectPending = false
            connectBle()
            return
        }
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "BLE reconnect already scheduled reason=$reason")
            return
        }
        val attempt = reconnectAttempts.incrementAndGet()
        Log.d(TAG, "schedule BLE reconnect reason=$reason attempt=$attempt delay=${AUTO_RECONNECT_DELAY_MS}ms")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            reconnectJob = null
            reconnectPending = false
            connectBle()
        }
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        txChar = null
        rxChar = null
        isRaceReady = false
        synchronized(responseLock) {
            pendingResponses.values.forEach { it.cancel() }
            pendingResponses.clear()
        }
    }

    // ══════════════════════════════════════════════
    // GATT Callback
    // ══════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "BLE state: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "BLE connected, discovering services...")
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(500) // Small delay for stability
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "BLE disconnected")
                    isRaceReady = false
                    if (isBleConnected) {
                        scheduleReconnect("disconnected")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                scheduleReconnect("service discovery failed")
                return
            }
            Log.d(TAG, "Services discovered, setting up RACE channel...")

            // Find RACE TX/RX characteristics
            for (service in g.services) {
                for (char in service.characteristics) {
                    when (char.uuid.toString().lowercase()) {
                        TX_UUID.toString().lowercase() -> txChar = char
                        RX_UUID.toString().lowercase() -> rxChar = char
                    }
                }
            }

            if (txChar == null || rxChar == null) {
                Log.e(TAG, "RACE TX/RX characteristics not found!")
                // Try BAS-only mode
                CoroutineScope(Dispatchers.IO).launch {
                    readBasBattery()
                    changeUIConnectionState("connecting")
                }
                return
            }

            // Enable notifications on RX and pair
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    g.setCharacteristicNotification(rxChar, true)
                    delay(200)

                    // Write CCCD descriptor to enable notifications
                    val descriptor = rxChar!!.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(descriptor)
                        delay(300)
                    }

                    // Bond/pair for RACE access
                    if (mDevice.bondState != BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Bonding...")
                        mDevice.createBond()
                        delay(3000)
                    }

                    isRaceReady = true
                    Log.d(TAG, "RACE channel ready")
                    changeUIConnectionState("connected")

                    // Send initial queries
                    delay(300)
                    sendInitQueries()
                } catch (e: Exception) {
                    Log.e(TAG, "RACE setup failed", e)
                    scheduleReconnect("race setup failed")
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleRaceNotification(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleRaceNotification(characteristic.value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write: ${descriptor.uuid} status=$status")
        }
    }

    // ══════════════════════════════════════════════
    // RACE notification handling
    // ══════════════════════════════════════════════

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleRaceNotification(data: ByteArray) {
        Log.v(TAG, "RACE RX: ${data.toHexString(HexFormat.UpperCase)}")

        val rid = RacePacket.extractRid(data)
        val rt = RacePacket.getResponseType(data)

        // Dispatch to pending response waiter
        synchronized(responseLock) {
            pendingResponses.remove(rid)?.complete(data)
        }

        // Also parse proactively for state updates
        when (rid) {
            RaceCmd.ANC_GET -> {
                val mode = RaceAncParser.parseAncResponse(data)
                if (mode != null) {
                    val internalAnc = raceAncToInternal(mode)
                    if (internalAnc != currentAnc) {
                        Log.d(TAG, "ANC mode notify: race=$mode internal=$internalAnc")
                        currentAnc = internalAnc
                        changeUIAncStatus(currentAnc)
                    }
                }
            }
            RaceCmd.BATTERY_GET -> {
                val result = RaceBatteryParser.parseSingleResponse(data)
                if (result != null) {
                    Log.d(TAG, "Battery notify: ${result.first}=${result.second}%")
                    // Battery updates are handled by queryBattery which collects both sides
                }
            }
            RaceCmd.ANC_SET -> {
                val ok = RaceAncParser.parseAncSetResponse(data)
                Log.d(TAG, "ANC set response: $ok")
            }
        }
    }

    // ══════════════════════════════════════════════
    // RACE command send / response receive
    // ══════════════════════════════════════════════

    private suspend fun sendRaceCommand(packet: ByteArray, expectResponse: Boolean = true): ByteArray? {
        val g = gatt ?: return null
        val tx = txChar ?: return null

        val rid = RacePacket.extractRid(packet)
        val deferred = CompletableDeferred<ByteArray>()

        if (expectResponse && rid >= 0) {
            synchronized(responseLock) {
                pendingResponses[rid] = deferred
            }
        }

        try {
            @Suppress("DEPRECATION")
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            tx.value = packet
            @Suppress("DEPRECATION")
            g.writeCharacteristic(tx)
            Log.v(TAG, "RACE TX: ${packet.joinToString(" ") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "RACE write failed", e)
            synchronized(responseLock) { pendingResponses.remove(rid) }
            return null
        }

        if (!expectResponse) return null

        return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            deferred.await()
        }
    }

    // ══════════════════════════════════════════════
    // ANC control
    // ══════════════════════════════════════════════

    /**
     * Set ANC mode using internal status codes (matching RfcommController numbering):
     *   1 = Off
     *   2 = Noise Cancellation (maps to RACE Strong=1)
     *   3 = Transparency (maps to RACE 9)
     *   4 = Adaptive (maps to RACE 4)
     *   5 = Smart NC (maps to RACE Medium=2)
     *   6 = Light NC (maps to RACE Light=3)
     *   7 = Medium NC (maps to RACE Medium=2)
     *   8 = Deep NC (maps to RACE Strong=1)
     */
    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode
        changeUIAncStatus(mode)

        val raceValue = when (mode) {
            1 -> 0  // Off
            2 -> RaceAncValue.STRONG    // NC → Strong
            3 -> RaceAncValue.TRANSPARENCY // Transparency
            4 -> RaceAncValue.ADAPTIVE  // Adaptive
            5 -> RaceAncValue.MEDIUM    // Smart → Medium
            6 -> RaceAncValue.LIGHT     // Light
            7 -> RaceAncValue.MEDIUM    // Medium
            8 -> RaceAncValue.STRONG    // Deep → Strong
            else -> return
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (raceValue == 0) {
                // ANC Off command
                val packet = RaceEnums.ancOff()
                sendRaceCommand(packet)
            } else {
                // ANC Set command
                val packet = RaceEnums.ancSet(raceValue)
                sendRaceCommand(packet)
            }
            delay(350)
            // Re-query current state
            queryAncMode()
            queryBattery()
        }
    }

    fun cycleAnc() {
        val cycle = listOf(2, 4, 3, 1) // NC → Adaptive → Transparency → Off
        val currentIndex = cycle.indexOf(if (currentAnc in 5..8) 2 else currentAnc)
        val next = cycle[((currentIndex + 1) % cycle.size + cycle.size) % cycle.size]
        setANCMode(next)
    }

    private suspend fun queryAncMode() {
        val packet = RaceEnums.ancQuery()
        val response = sendRaceCommand(packet) ?: return
        val mode = RaceAncParser.parseAncResponse(response) ?: return
        val internalAnc = raceAncToInternal(mode)
        Log.d(TAG, "ANC query result: race=$mode internal=$internalAnc")
        currentAnc = internalAnc
        changeUIAncStatus(currentAnc)
    }

    /**
     * Map RACE ANC value to internal status code.
     * RACE: 1=Strong, 2=Medium, 3=Light, 4=Adaptive, 9=Transparency
     * Internal: 1=Off, 2=NC, 3=Transparency, 4=Adaptive, 5-8=intensities
     */
    private fun raceAncToInternal(raceValue: Int): Int = when (raceValue) {
        RaceAncValue.STRONG -> 8      // Strong → Deep NC
        RaceAncValue.MEDIUM -> 7     // Medium → Medium NC
        RaceAncValue.LIGHT -> 6      // Light → Light NC
        RaceAncValue.ADAPTIVE -> 4   // Adaptive
        RaceAncValue.TRANSPARENCY -> 3 // Transparency
        else -> 1                     // Off
    }

    // ══════════════════════════════════════════════
    // Battery reading
    // ══════════════════════════════════════════════

    /**
     * Query battery for both ears via RACE protocol (requires pairing).
     * Queries L and R separately and merges into BatteryParams.
     */
    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            var leftLevel: Int? = null
            var rightLevel: Int? = null

            // Query Left (agent=1)
            val leftPacket = RaceEnums.batteryQuery(1)
            val leftResp = sendRaceCommand(leftPacket)
            if (leftResp != null) {
                val result = RaceBatteryParser.parseSingleResponse(leftResp)
                if (result != null && result.first == "L") {
                    leftLevel = result.second
                }
            }

            delay(300) // Small delay between queries

            // Query Right (agent=2)
            val rightPacket = RaceEnums.batteryQuery(2)
            val rightResp = sendRaceCommand(rightPacket)
            if (rightResp != null) {
                val result = RaceBatteryParser.parseSingleResponse(rightResp)
                if (result != null && result.first == "R") {
                    rightLevel = result.second
                }
            }

            // If RACE failed, try BAS fallback
            if (leftLevel == null && rightLevel == null) {
                val basLevel = readBasBattery()
                if (basLevel != null && basLevel != 255) {
                    // BAS only gives one combined value; assign to whichever ear is connected
                    leftLevel = basLevel
                    rightLevel = basLevel
                }
            }

            handleBatteryUpdate(leftLevel, rightLevel)
        }
    }

    /**
     * Read battery via standard BLE Battery Service (BAS).
     * No pairing required, but only returns a single combined value.
     */
    private suspend fun readBasBattery(): Int? {
        val g = gatt ?: return null
        return try {
            // Find BAS characteristic
            for (service in g.services) {
                for (char in service.characteristics) {
                    if (char.uuid.toString().lowercase() == BAS_LEVEL_UUID.toString().lowercase()) {
                        @Suppress("DEPRECATION")
                        g.readCharacteristic(char)
                        // Wait for callback or read directly
                        // On newer Android, readCharacteristic returns a boolean and value comes via callback
                        // For simplicity, read the value from the characteristic after a short delay
                        delay(500)
                        @Suppress("DEPRECATION")
                        val bytes = char.value
                        if (bytes != null && bytes.isNotEmpty()) {
                            val level = bytes[0].toInt() and 0xFF
                            return if (level != 255) level else null
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "BAS read failed", e)
            null
        }
    }

    private fun handleBatteryUpdate(leftLevel: Int?, rightLevel: Int?) {
        val left = if (leftLevel != null) {
            PodParams(battery = leftLevel, isCharging = false, isConnected = true, rawStatus = 0)
        } else {
            PodParams(battery = 0, isCharging = false, isConnected = false, rawStatus = 0)
        }
        val right = if (rightLevel != null) {
            PodParams(battery = rightLevel, isCharging = false, isConnected = true, rawStatus = 0)
        } else {
            PodParams(battery = 0, isCharging = false, isConnected = false, rawStatus = 0)
        }
        // ROG Cetra TWS has no case battery reporting
        val case = PodParams(battery = 0, isCharging = false, isConnected = false, rawStatus = 0)

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (!mShowedConnectedToast) {
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (hasValidData) {
                changeUIConnectionState("connected")
                sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    if (::mDevice.isInitialized) {
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                mShowedConnectedToast = true
            }
        }

        Log.d(TAG, "Battery: L=${leftLevel ?: "?"}% R=${rightLevel ?: "?"}%")
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> 0
        }
    }

    // ══════════════════════════════════════════════
    // Game mode (limited support on RACE)
    // ══════════════════════════════════════════════

    fun setGameMode(enabled: Boolean) {
        Log.d(TAG, "setGameMode: $enabled (limited support on RACE)")
        currentGameMode = enabled
        changeUIGameModeStatus(enabled)
        // ROG Cetra TWS may not support game mode via RACE protocol
        // The command is not defined in the RACE spec from the reference script
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        Log.d(TAG, "setTransparencyVocalEnhancement: $enabled (not supported on RACE)")
        currentTransparencyVocalEnhancement = enabled
        changeUITransparencyVocalEnhancementStatus(enabled)
    }

    // ══════════════════════════════════════════════
    // Status query
    // ══════════════════════════════════════════════

    fun queryStatus(immediateReconnect: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            sendInitQueries()
        }
    }

    private suspend fun sendInitQueries() {
        if (!isRaceReady) return
        Log.d(TAG, "Sending init queries...")
        queryAncMode()
        delay(300)
        queryBattery()
    }

    // ══════════════════════════════════════════════
    // MIUI/HyperOS integration helpers (mirror RfcommController)
    // ══════════════════════════════════════════════

    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int, transparencyVocalEnhancement: Boolean): String {
        return when (anc) {
            8 -> "0102" // Deep / Strong
            7 -> "0100" // Medium
            6 -> "0101" // Light
            5 -> "0103" // Smart
            3 -> if (transparencyVocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
    }

    // ══════════════════════════════════════════════
    // Audio routing (mirrors RfcommController)
    // ══════════════════════════════════════════════

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
    }

    // ══════════════════════════════════════════════
    // Device detection helpers
    // ══════════════════════════════════════════════

    /**
     * Check if a Bluetooth device is a ROG Cetra TWS (or compatible Airoha RACE device).
     * Checks device name for known keywords.
     */
    fun isRogCetra(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return isRogCetraName(name)
    }

    fun isRogCetraName(name: String): Boolean {
        val upper = name.uppercase()
        return upper.contains("ROG CETRA") ||
                upper.contains("CTWSN") ||
                upper.contains("R55") ||
                upper.contains("SPEEDNOVA")
    }
}
