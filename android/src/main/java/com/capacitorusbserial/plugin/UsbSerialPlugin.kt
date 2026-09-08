package com.capacitorusbserial.plugin

import android.content.IntentFilter
import android.hardware.usb.UsbManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Converts any thrown exception into a coded `call.reject`, guaranteeing nothing escapes
 * across the bridge (Req 14.2). Extracted as a top-level function so it is unit-testable
 * without standing up the whole plugin.
 */
internal fun rejectWith(call: PluginCall, e: Throwable) {
    val code = mapException(e)
    call.reject(e.message ?: code.name, code.name)
}

@CapacitorPlugin(name = "UsbSerial")
class UsbSerialPlugin : Plugin() {
    private lateinit var impl: UsbSerialImpl
    private var receiver: UsbEventReceiver? = null
    private lateinit var io: ExecutorService

    override fun load() {
        io = Executors.newCachedThreadPool()
        impl =
            UsbSerialImpl(
                context = context.applicationContext,
                emitter = { name, payload -> notifyListeners(name, payload) },
                resolvePermission = { callbackId, granted, coalesced ->
                    resolvePermission(callbackId, granted, coalesced)
                },
                rejectPermission = { callbackId, code, message ->
                    rejectPermission(callbackId, code, message)
                },
            )
        registerReceiver()
        consumeLaunchAttachIntent()
    }

    /**
     * Cold-start half of the auto-attach flow: when the app was launched by a
     * USB_DEVICE_ATTACHED intent, buffer the attach for replay to the first 'attached'
     * listener. Warm attaches are NOT handled here — the runtime receiver gets the
     * system broadcast, so inspecting onNewIntent too would double-emit.
     */
    private fun consumeLaunchAttachIntent() {
        val intent = activity?.intent ?: return
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = UsbIntents.usbDevice(intent) ?: return
        // removeExtra only mutates the process-local copy; after process death the
        // original intent is re-delivered from recents, so also verify the device is
        // still attached before replaying a potentially stale attach.
        intent.removeExtra(UsbManager.EXTRA_DEVICE)
        val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        val current = usbManager.deviceList[device.deviceName]
        if (current == null || current.vendorId != device.vendorId || current.productId != device.productId) {
            return
        }
        impl.handleAttached(current, coldStart = true)
    }

    override fun handleOnDestroy() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (this::impl.isInitialized) impl.teardown()
        if (this::io.isInitialized) io.shutdownNow()
        super.handleOnDestroy()
    }

    private fun registerReceiver() {
        val r = UsbEventReceiver(impl)
        val filter =
            IntentFilter().apply {
                addAction(UsbSerialImpl.ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun resolvePermission(callbackId: String, granted: Boolean, coalesced: Boolean) {
        val saved = bridge.getSavedCall(callbackId) ?: return
        val result = JSObject().put("granted", granted)
        // Only coalesced results carry the marker; the first caller's shape is unchanged.
        if (coalesced) result.put("coalesced", true)
        saved.resolve(result)
        bridge.releaseCall(saved)
    }

    private fun rejectPermission(callbackId: String, code: UsbSerialErrorCode, message: String) {
        val saved = bridge.getSavedCall(callbackId) ?: return
        saved.reject(message, code.name)
        bridge.releaseCall(saved)
    }

    /**
     * Shared bridge wrapper: runs [block] off the main thread and converts any thrown
     * exception into a coded rejection. A null return resolves with no data. Guarantees no
     * exception escapes across the bridge (Req 14.2).
     */
    private fun dispatch(call: PluginCall, block: () -> JSObject?) {
        io.execute {
            try {
                val result = block()
                if (result != null) call.resolve(result) else call.resolve()
            } catch (e: Throwable) {
                rejectWith(call, e)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Task 32 — discovery / permission / lifecycle wrappers
    // ----------------------------------------------------------------------

    @PluginMethod
    fun listDevices(call: PluginCall) = dispatch(call) { impl.listDevices() }

    @PluginMethod
    fun listBulkDevices(call: PluginCall) = dispatch(call) { impl.listBulkDevices() }

    @PluginMethod
    fun registerDriver(call: PluginCall) =
        dispatch(call) {
            impl.registerDriver(
                vendorId = call.reqInt("vendorId"),
                productId = call.reqInt("productId"),
                driverType = call.reqString("driverType"),
                replaceDefaults = call.getBoolean("replaceDefaults", false) ?: false,
            )
            null
        }

    @PluginMethod
    fun hasPermission(call: PluginCall) = dispatch(call) { impl.hasPermission(call.reqString("deviceId")) }

    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    fun requestPermission(call: PluginCall) {
        val deviceId =
            call.getString("deviceId")
                ?: return call.reject("Missing deviceId", UsbSerialErrorCode.INVALID_PARAMS.name)
        call.setKeepAlive(true)
        bridge.saveCall(call)
        io.execute {
            try {
                impl.requestPermission(deviceId, call.callbackId)
            } catch (e: Throwable) {
                rejectWith(call, e)
                bridge.releaseCall(call)
            }
        }
    }

    @PluginMethod
    fun open(call: PluginCall) =
        dispatch(call) { impl.open(call.reqString("deviceId"), call.getInt("portNum", 0) ?: 0) }

    @PluginMethod
    fun close(call: PluginCall) = dispatch(call) { impl.close(call.reqString("portId")); null }

    @PluginMethod
    fun isOpen(call: PluginCall) = dispatch(call) { impl.isOpen(call.reqString("portId")) }

    @PluginMethod
    fun getPortInfo(call: PluginCall) = dispatch(call) { impl.getPortInfo(call.reqString("portId")) }

    @PluginMethod
    fun openBulk(call: PluginCall) =
        dispatch(call) { impl.openBulk(call.reqString("deviceId"), call.getInt("interfaceNumber")) }

    @PluginMethod
    fun closeBulk(call: PluginCall) = dispatch(call) { impl.closeBulk(call.reqString("bulkId")); null }

    @PluginMethod
    fun isBulkOpen(call: PluginCall) = dispatch(call) { impl.isBulkOpen(call.reqString("bulkId")) }

    @PluginMethod
    fun getBulkInfo(call: PluginCall) = dispatch(call) { impl.getBulkInfo(call.reqString("bulkId")) }

    @PluginMethod
    fun bulkRead(call: PluginCall) =
        dispatch(call) { impl.bulkRead(call.reqString("bulkId"), call.getInt("length"), call.getInt("timeout")) }

    @PluginMethod
    fun bulkWrite(call: PluginCall) =
        dispatch(call) { impl.bulkWrite(call.reqString("bulkId"), call.reqString("data"), call.getInt("timeout")) }

    @PluginMethod
    fun startBulkReading(call: PluginCall) =
        dispatch(call) {
            impl.startBulkReading(call.reqString("bulkId"), call.getInt("bufferSize"), call.getInt("timeout"))
            null
        }

    @PluginMethod
    fun stopBulkReading(call: PluginCall) = dispatch(call) { impl.stopBulkReading(call.reqString("bulkId")); null }

    @PluginMethod
    fun getBulkStreamState(call: PluginCall) = dispatch(call) { impl.getBulkStreamState(call.reqString("bulkId")) }

    // ----------------------------------------------------------------------
    // Task 33 — I/O / config wrappers
    // ----------------------------------------------------------------------

    @PluginMethod
    fun setParameters(call: PluginCall) =
        dispatch(call) {
            impl.setParameters(
                portId = call.reqString("portId"),
                baudRate = call.reqInt("baudRate"),
                dataBits = call.reqInt("dataBits"),
                stopBits = call.reqDouble("stopBits"),
                parity = call.reqString("parity"),
            )
            null
        }

    @PluginMethod
    fun read(call: PluginCall) =
        dispatch(call) { impl.read(call.reqString("portId"), call.getInt("length"), call.getInt("timeout")) }

    @PluginMethod
    fun write(call: PluginCall) =
        dispatch(call) {
            impl.write(call.reqString("portId"), call.reqString("data"), call.getInt("timeout"))
        }

    @PluginMethod
    fun writeAsync(call: PluginCall) =
        dispatch(call) { impl.writeAsync(call.reqString("portId"), call.reqString("data")); null }

    @PluginMethod
    fun startReading(call: PluginCall) =
        dispatch(call) {
            impl.startReading(
                portId = call.reqString("portId"),
                readTimeout = call.getInt("readTimeout"),
                writeTimeout = call.getInt("writeTimeout"),
                readBufferSize = call.getInt("readBufferSize"),
                writeBufferSize = call.getInt("writeBufferSize"),
                readQueueBufferCount = call.getInt("readQueueBufferCount"),
                threadPriority = call.getInt("threadPriority"),
            )
            null
        }

    @PluginMethod
    fun stopReading(call: PluginCall) = dispatch(call) { impl.stopReading(call.reqString("portId")); null }

    @PluginMethod
    fun getStreamState(call: PluginCall) = dispatch(call) { impl.getStreamState(call.reqString("portId")) }

    @PluginMethod
    fun getStreamConfig(call: PluginCall) = dispatch(call) { impl.getStreamConfig(call.reqString("portId")) }

    @PluginMethod
    fun getControlLines(call: PluginCall) = dispatch(call) { impl.getControlLines(call.reqString("portId")) }

    @PluginMethod
    fun getSupportedControlLines(call: PluginCall) =
        dispatch(call) { impl.getSupportedControlLines(call.reqString("portId")) }

    @PluginMethod fun getCD(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "cd") }

    @PluginMethod fun getCTS(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "cts") }

    @PluginMethod fun getDSR(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "dsr") }

    @PluginMethod fun getDTR(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "dtr") }

    @PluginMethod fun getRI(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "ri") }

    @PluginMethod fun getRTS(call: PluginCall) = dispatch(call) { impl.getControlLine(call.reqString("portId"), "rts") }

    @PluginMethod
    fun setDTR(call: PluginCall) =
        dispatch(call) { impl.setDTR(call.reqString("portId"), call.reqBoolean("value")); null }

    @PluginMethod
    fun setRTS(call: PluginCall) =
        dispatch(call) { impl.setRTS(call.reqString("portId"), call.reqBoolean("value")); null }

    @PluginMethod
    fun setFlowControl(call: PluginCall) =
        dispatch(call) { impl.setFlowControl(call.reqString("portId"), call.reqString("mode")); null }

    @PluginMethod
    fun getFlowControl(call: PluginCall) = dispatch(call) { impl.getFlowControl(call.reqString("portId")) }

    @PluginMethod
    fun getSupportedFlowControl(call: PluginCall) =
        dispatch(call) { impl.getSupportedFlowControl(call.reqString("portId")) }

    @PluginMethod
    fun getXON(call: PluginCall) = dispatch(call) { impl.getXON(call.reqString("portId")) }

    @PluginMethod
    fun purgeHwBuffers(call: PluginCall) =
        dispatch(call) {
            impl.purgeHwBuffers(
                call.reqString("portId"),
                call.reqBoolean("purgeWrite"),
                call.reqBoolean("purgeRead"),
            )
            null
        }

    @PluginMethod
    fun setBreak(call: PluginCall) =
        dispatch(call) { impl.setBreak(call.reqString("portId"), call.reqBoolean("value")); null }

    @PluginMethod
    fun setReadQueue(call: PluginCall) =
        dispatch(call) {
            impl.setReadQueue(call.reqString("portId"), call.reqInt("bufferCount"), call.reqInt("bufferSize"))
            null
        }

    @PluginMethod
    fun getReadQueueConfig(call: PluginCall) = dispatch(call) { impl.getReadQueueConfig(call.reqString("portId")) }

    // ----------------------------------------------------------------------
    // Listener registration — flush any buffered cold-start attach (Req 12.5)
    // ----------------------------------------------------------------------

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    override fun addListener(call: PluginCall) {
        super.addListener(call)
        // Replay the buffered cold-start attach only to an 'attached' listener — flushing
        // on any registration would emit to zero listeners and lose the payload.
        if (this::impl.isInitialized && call.getString("eventName") == "attached") {
            impl.flushBufferedAttach()
        }
    }

    // ----------------------------------------------------------------------
    // PluginCall arg helpers (throw INVALID_PARAMS so the shared catch rejects)
    // ----------------------------------------------------------------------

    private fun PluginCall.reqString(key: String): String =
        getString(key) ?: throw UsbSerialError(UsbSerialErrorCode.INVALID_PARAMS, "Missing '$key'")

    private fun PluginCall.reqInt(key: String): Int =
        getInt(key) ?: throw UsbSerialError(UsbSerialErrorCode.INVALID_PARAMS, "Missing '$key'")

    private fun PluginCall.reqDouble(key: String): Double =
        getDouble(key) ?: throw UsbSerialError(UsbSerialErrorCode.INVALID_PARAMS, "Missing '$key'")

    private fun PluginCall.reqBoolean(key: String): Boolean =
        getBoolean(key) ?: throw UsbSerialError(UsbSerialErrorCode.INVALID_PARAMS, "Missing '$key'")
}
