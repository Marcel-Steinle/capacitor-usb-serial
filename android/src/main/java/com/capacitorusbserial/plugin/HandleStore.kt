package com.capacitorusbserial.plugin

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One open serial port plus everything that must be reaped with it: the native
 * connection, the per-port single-threaded executor that serializes its I/O, and the
 * optional active stream manager. [stream] is an AtomicReference because it is written
 * from both the bridge dispatch pool (start/stopReading) and the stream's own error
 * thread (identity-guarded clear on run error).
 */
class PortHandle(
    val portId: String,
    val deviceId: String,
    val port: UsbSerialPort,
    val connection: UsbDeviceConnection,
    val executor: ExecutorService,
    val stream: AtomicReference<SerialStreamManager?> = AtomicReference(null),
)

class BulkHandle(
    val bulkId: String,
    val deviceId: String,
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
    val connection: UsbDeviceConnection,
    val executor: ExecutorService,
    val stream: AtomicReference<BulkStreamManager?> = AtomicReference(null),
)

/**
 * Single owner of deviceId/portId handles. Thread-safe. Nothing else in the plugin
 * generates or reaps IDs.
 *
 * - A `deviceId` is valid only for the lifetime of an attachment; [reapDevice] invalidates
 *   it (and all its ports) on detach, after which lookups throw NO_DEVICE.
 * - `requestedPermission` records which devices a permission prompt was issued for, so the
 *   impl can distinguish NEEDS_PERMISSION (never asked) from PERMISSION_DENIED (asked + no).
 */
class HandleStore {
    private val devices = ConcurrentHashMap<String, UsbDevice>()
    private val ports = ConcurrentHashMap<String, PortHandle>()
    private val bulkHandles = ConcurrentHashMap<String, BulkHandle>()
    private val requestedPermission: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val portSeq = AtomicLong(0)
    private val bulkSeq = AtomicLong(0)

    // --- Device registry (keyed by a stable per-attachment id) ---

    /** A stable id for the current attachment of [device]. */
    fun deviceIdFor(device: UsbDevice): String = "dev_${device.deviceId}_${device.deviceName.hashCode()}"

    fun registerDevice(device: UsbDevice): String {
        val id = deviceIdFor(device)
        devices[id] = device
        return id
    }

    fun getDevice(deviceId: String): UsbDevice =
        devices[deviceId]
            ?: throw UsbSerialError(UsbSerialErrorCode.NO_DEVICE, "No connected device for id $deviceId")

    fun hasDevice(deviceId: String): Boolean = devices.containsKey(deviceId)

    // --- Permission tracking ---

    fun markPermissionRequested(deviceId: String) {
        requestedPermission.add(deviceId)
    }

    fun wasPermissionRequested(deviceId: String): Boolean = requestedPermission.contains(deviceId)

    // --- Port handles ---

    fun addPort(
        deviceId: String,
        port: UsbSerialPort,
        connection: UsbDeviceConnection,
        executor: ExecutorService,
    ): PortHandle {
        val portId = "port_${portSeq.incrementAndGet()}"
        val handle = PortHandle(portId, deviceId, port, connection, executor)
        ports[portId] = handle
        return handle
    }

    fun getPort(portId: String): PortHandle =
        ports[portId]
            ?: throw UsbSerialError(UsbSerialErrorCode.PORT_NOT_OPEN, "No open port for id $portId")

    fun getPortOrNull(portId: String): PortHandle? = ports[portId]

    fun addBulk(
        deviceId: String,
        device: UsbDevice,
        usbInterface: UsbInterface,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        connection: UsbDeviceConnection,
        executor: ExecutorService,
    ): BulkHandle {
        val bulkId = "bulk_${bulkSeq.incrementAndGet()}"
        return BulkHandle(bulkId, deviceId, device, usbInterface, inEndpoint, outEndpoint, connection, executor)
            .also { bulkHandles[bulkId] = it }
    }

    fun getBulk(bulkId: String): BulkHandle =
        bulkHandles[bulkId]
            ?: throw UsbSerialError(UsbSerialErrorCode.PORT_NOT_OPEN, "No open bulk interface for id $bulkId")

    fun getBulkOrNull(bulkId: String): BulkHandle? = bulkHandles[bulkId]

    fun reapBulk(bulkId: String) {
        val handle = bulkHandles.remove(bulkId) ?: return
        runCatching { handle.stream.getAndSet(null)?.stop() }
        runCatching { handle.connection.releaseInterface(handle.usbInterface) }
        runCatching { handle.connection.close() }
        runCatching { handle.executor.shutdownNow() }
    }

    /** Stop+close+reap a single port handle. Safe to call more than once. */
    fun reapPort(portId: String) {
        val handle = ports.remove(portId) ?: return
        closeHandle(handle)
    }

    /** Invalidate a detached device and reap every port that belonged to it. */
    fun reapDevice(deviceId: String) {
        devices.remove(deviceId)
        requestedPermission.remove(deviceId)
        ports.values
            .filter { it.deviceId == deviceId }
            .forEach { reapPort(it.portId) }
        bulkHandles.values
            .filter { it.deviceId == deviceId }
            .forEach { reapBulk(it.bulkId) }
    }

    /** Reap everything (plugin teardown). */
    fun reapAll() {
        ports.keys.toList().forEach { reapPort(it) }
        bulkHandles.keys.toList().forEach { reapBulk(it) }
        devices.clear()
        requestedPermission.clear()
    }

    fun portsForDevice(deviceId: String): List<PortHandle> = ports.values.filter { it.deviceId == deviceId }

    fun bulkForDevice(deviceId: String): List<BulkHandle> = bulkHandles.values.filter { it.deviceId == deviceId }

    private fun closeHandle(handle: PortHandle) {
        runCatching { handle.stream.get()?.stop() }
        runCatching { handle.port.close() }
        runCatching { handle.connection.close() }
        runCatching { handle.executor.shutdownNow() }
    }
}
