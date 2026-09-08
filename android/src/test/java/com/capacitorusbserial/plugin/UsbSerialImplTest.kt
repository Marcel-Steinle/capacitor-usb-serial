package com.capacitorusbserial.plugin

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.getcapacitor.JSObject
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.EnumSet

/**
 * Tier A coverage: drives the real UsbSerialImpl orchestration against a mocked USB stack
 * (UsbManager + the library's UsbSerialProber/UsbSerialDriver/UsbSerialPort). No hardware.
 * Verifies permission gating, listDevices shaping, read/write semantics, the streaming
 * exclusivity rule, enum mapping at the port boundary, control/flow lines, and detach cleanup.
 */
@RunWith(RobolectricTestRunner::class)
class UsbSerialImplTest {
    private lateinit var impl: UsbSerialImpl
    private lateinit var usbManager: UsbManager
    private lateinit var proberStatic: MockedStatic<UsbSerialProber>
    private lateinit var prober: UsbSerialProber

    private val events = mutableListOf<Pair<String, JSObject>>()
    private val permissions = mutableListOf<Triple<String, Boolean, Boolean>>()
    private val rejections = mutableListOf<Pair<String, UsbSerialErrorCode>>()

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        usbManager = mock(UsbManager::class.java)
        shadowOf(app).setSystemService(Context.USB_SERVICE, usbManager)

        prober = mock(UsbSerialProber::class.java)
        proberStatic = mockStatic(UsbSerialProber::class.java)
        proberStatic.`when`<UsbSerialProber> { UsbSerialProber.getDefaultProber() }.thenReturn(prober)

        impl =
            UsbSerialImpl(
                context = app,
                emitter = { name, payload -> events.add(name to payload) },
                resolvePermission = { id, granted, coalesced -> permissions.add(Triple(id, granted, coalesced)) },
                rejectPermission = { id, code, _ -> rejections.add(id to code) },
            )
    }

    @After
    fun tearDown() {
        proberStatic.close()
    }

    // --- helpers -----------------------------------------------------------

    private class Fixture(
        val deviceId: String,
        val device: UsbDevice,
        val port: UsbSerialPort,
        val connection: UsbDeviceConnection,
    )

    /** Wire a single device/driver/port and register it via listDevices(); returns handles. */
    private fun registerDevice(hasPermission: Boolean): Fixture {
        val device = mock(UsbDevice::class.java)
        `when`(device.deviceId).thenReturn(7)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(device.vendorId).thenReturn(0x0403)
        `when`(device.productId).thenReturn(0x6001)

        val port = mock(UsbSerialPort::class.java)
        `when`(port.portNumber).thenReturn(0)
        `when`(port.device).thenReturn(device)

        val driver = mock(FtdiSerialDriver::class.java)
        `when`(driver.device).thenReturn(device)
        `when`(driver.ports).thenReturn(listOf(port))
        `when`(port.driver).thenReturn(driver)

        val connection = mock(UsbDeviceConnection::class.java)

        `when`(prober.findAllDrivers(usbManager)).thenReturn(listOf(driver))
        `when`(prober.probeDevice(device)).thenReturn(driver)
        `when`(usbManager.hasPermission(device)).thenReturn(hasPermission)
        `when`(usbManager.openDevice(device)).thenReturn(connection)
        `when`(usbManager.deviceList).thenReturn(hashMapOf("/dev/bus/usb/001/002" to device))

        val devices = impl.listDevices().getJSONArray("devices")
        val deviceId = devices.getJSONObject(0).getString("deviceId")!!
        return Fixture(deviceId, device, port, connection)
    }

    private fun openPort(f: Fixture): String = impl.open(f.deviceId, 0).getString("portId")!!

    private class BulkFixture(
        val deviceId: String,
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
        val connection: UsbDeviceConnection,
    )

    private fun registerBulkDevice(hasPermission: Boolean = true): BulkFixture {
        val device = mock(UsbDevice::class.java)
        `when`(device.deviceId).thenReturn(9)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/009")
        `when`(device.vendorId).thenReturn(0x1bc2)
        `when`(device.productId).thenReturn(0x121a)

        val intf = mock(UsbInterface::class.java)
        `when`(intf.id).thenReturn(0)
        `when`(intf.interfaceClass).thenReturn(0xff)
        `when`(intf.interfaceSubclass).thenReturn(0xff)
        `when`(intf.interfaceProtocol).thenReturn(0xff)
        `when`(intf.endpointCount).thenReturn(2)

        val output = mock(UsbEndpoint::class.java)
        `when`(output.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
        `when`(output.direction).thenReturn(UsbConstants.USB_DIR_OUT)
        `when`(output.address).thenReturn(0x01)
        `when`(output.maxPacketSize).thenReturn(512)
        val input = mock(UsbEndpoint::class.java)
        `when`(input.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
        `when`(input.direction).thenReturn(UsbConstants.USB_DIR_IN)
        `when`(input.address).thenReturn(0x81)
        `when`(input.maxPacketSize).thenReturn(512)
        `when`(intf.getEndpoint(0)).thenReturn(output)
        `when`(intf.getEndpoint(1)).thenReturn(input)
        `when`(device.interfaceCount).thenReturn(1)
        `when`(device.getInterface(0)).thenReturn(intf)

        val connection = mock(UsbDeviceConnection::class.java)
        `when`(connection.claimInterface(intf, true)).thenReturn(true)
        `when`(usbManager.hasPermission(device)).thenReturn(hasPermission)
        `when`(usbManager.openDevice(device)).thenReturn(connection)
        `when`(usbManager.deviceList).thenReturn(hashMapOf(device.deviceName to device))
        val info = impl.listBulkDevices().getJSONArray("devices").getJSONObject(0)
        return BulkFixture(info.getString("deviceId")!!, device, intf, input, output, connection)
    }

    private fun b64(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // --- discovery ---------------------------------------------------------

    @Test
    fun listDevicesShapesDescriptor() {
        registerDevice(hasPermission = true)
        val dev = impl.listDevices().getJSONArray("devices").getJSONObject(0)
        assertEquals(0x0403, dev.getInt("vendorId"))
        assertEquals(0x6001, dev.getInt("productId"))
        assertEquals("FtdiSerialDriver", dev.getString("driverType"))
        assertEquals(1, dev.getInt("portCount"))
        assertTrue(dev.getBoolean("hasPermission"))
    }

    @Test
    fun listDevicesEmptyWhenNonePresent() {
        `when`(prober.findAllDrivers(usbManager)).thenReturn(emptyList())
        assertEquals(0, impl.listDevices().getJSONArray("devices").length())
    }

    @Test
    fun listBulkDevicesFindsCbg21aEndpointsWithoutSerialDriver() {
        val f = registerBulkDevice()
        val info = impl.listBulkDevices().getJSONArray("devices").getJSONObject(0)
        assertEquals(f.deviceId, info.getString("deviceId"))
        assertEquals(0x1bc2, info.getInt("vendorId"))
        assertEquals(0x121a, info.getInt("productId"))
        assertEquals(0, info.getInt("interfaceNumber"))
        assertEquals(0x81, info.getInt("inEndpointAddress"))
        assertEquals(0x01, info.getInt("outEndpointAddress"))
        assertEquals(512, info.getInt("inMaxPacketSize"))
    }

    @Test
    fun openAndCloseBulkClaimsAndReleasesInterface() {
        val f = registerBulkDevice()
        val bulkId = impl.openBulk(f.deviceId, 0).getString("bulkId")!!
        assertTrue(bulkId.startsWith("bulk_"))
        verify(f.connection).claimInterface(f.usbInterface, true)
        impl.closeBulk(bulkId)
        verify(f.connection).releaseInterface(f.usbInterface)
        verify(f.connection).close()
    }

    @Test
    fun openBulkRequiresPermission() {
        val f = registerBulkDevice(hasPermission = false)
        try {
            impl.openBulk(f.deviceId, 0)
            fail("expected NEEDS_PERMISSION")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.NEEDS_PERMISSION, e.code)
        }
    }

    // --- permission gating -------------------------------------------------

    @Test
    fun openWithoutEverRequestingThrowsNeedsPermission() {
        val f = registerDevice(hasPermission = false)
        try {
            impl.open(f.deviceId, 0)
            fail("expected NEEDS_PERMISSION")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.NEEDS_PERMISSION, e.code)
        }
    }

    @Test
    fun openAfterDeniedThrowsPermissionDenied() {
        val f = registerDevice(hasPermission = false)
        impl.requestPermission(f.deviceId, "cb1") // marks requested, fires system request
        impl.onPermissionResult(f.deviceId, false) // user denies
        assertEquals(listOf(Triple("cb1", false, false)), permissions)
        try {
            impl.open(f.deviceId, 0)
            fail("expected PERMISSION_DENIED")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun requestPermissionResolvesTrueWhenAlreadyGranted() {
        val f = registerDevice(hasPermission = true)
        impl.requestPermission(f.deviceId, "cb2")
        assertEquals(listOf(Triple("cb2", true, false)), permissions)
    }

    @Test
    fun requestPermissionUnknownDeviceThrowsNoDevice() {
        try {
            impl.requestPermission("nope", "cb")
            fail("expected NO_DEVICE")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.NO_DEVICE, e.code)
        }
    }

    // --- open / params -----------------------------------------------------

    @Test
    fun openReturnsPortIdAndOpensPort() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        assertTrue(portId.startsWith("port_"))
        verify(f.port).open(f.connection)
    }

    @Test
    fun openInvalidPortNumThrowsInvalidParams() {
        val f = registerDevice(hasPermission = true)
        try {
            impl.open(f.deviceId, 5)
            fail("expected INVALID_PARAMS")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.INVALID_PARAMS, e.code)
        }
    }

    @Test
    fun setParametersMapsEnumsToLibraryConstants() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        impl.setParameters(portId, 115200, 8, 1.0, "none")
        verify(f.port).setParameters(
            115200,
            UsbSerialPort.DATABITS_8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE,
        )
    }

    @Test
    fun setParametersInvalidValueRejectsBeforeHardware() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        try {
            impl.setParameters(portId, 9600, 8, 3.0 /* bad stopbits */, "none")
            fail("expected INVALID_PARAMS")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.INVALID_PARAMS, e.code)
        }
    }

    // --- read / write ------------------------------------------------------

    @Test
    fun readReturnsBase64OfBytesRead() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        val payload = byteArrayOf(1, 2, 3, 4)
        `when`(f.port.read(any(), anyInt())).thenAnswer { inv ->
            val buf = inv.getArgument<ByteArray>(0)
            System.arraycopy(payload, 0, buf, 0, payload.size)
            payload.size
        }
        val data = impl.read(portId, 64, 500).getString("data")
        assertArrayEquals(payload, android.util.Base64.decode(data, android.util.Base64.NO_WRAP))
    }

    @Test
    fun readTimeoutReturnsEmptyNotError() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        `when`(f.port.read(any(), anyInt())).thenReturn(0)
        assertEquals("", impl.read(portId, 64, 10).getString("data"))
    }

    @Test
    fun readClosedPortThrowsPortNotOpen() {
        try {
            impl.read("port_999", 8, 10)
            fail("expected PORT_NOT_OPEN")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.PORT_NOT_OPEN, e.code)
        }
    }

    @Test
    fun writeReturnsBytesWrittenAndCallsPort() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        val result = impl.write(portId, b64(byteArrayOf(65, 66, 67)), 250)
        assertEquals(3, result.getInt("bytesWritten"))
        verify(f.port).write(any(), anyInt())
    }

    @Test
    fun writeInvalidBase64ThrowsInvalidParams() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        try {
            impl.write(portId, "not*base64!", null)
            fail("expected INVALID_PARAMS")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.INVALID_PARAMS, e.code)
        }
    }

    // --- streaming exclusivity --------------------------------------------

    @Test
    fun streamStateStoppedBeforeStart() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        assertEquals("stopped", impl.getStreamState(portId).getString("state"))
    }

    @Test
    fun oneShotReadRejectedWhileStreaming() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        // SerialInputOutputManager's constructor reads the endpoints' max packet size.
        val endpoint = mock(android.hardware.usb.UsbEndpoint::class.java)
        `when`(endpoint.maxPacketSize).thenReturn(64)
        `when`(f.port.readEndpoint).thenReturn(endpoint)
        `when`(f.port.writeEndpoint).thenReturn(endpoint)
        `when`(f.port.read(any(), anyInt())).thenReturn(0) // stream produces nothing
        impl.startReading(portId, 50, null, null, null, null, null)
        try {
            val state = impl.getStreamState(portId).getString("state")
            assertTrue("state was $state", state == "running" || state == "starting")
            try {
                impl.read(portId, 8, 10)
                fail("expected INVALID_STATE")
            } catch (e: UsbSerialError) {
                assertEquals(UsbSerialErrorCode.INVALID_STATE, e.code)
            }
        } finally {
            impl.stopReading(portId)
        }
    }

    @Test
    fun getStreamConfigWithoutStreamThrowsInvalidState() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        try {
            impl.getStreamConfig(portId)
            fail("expected INVALID_STATE")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.INVALID_STATE, e.code)
        }
    }

    // --- control lines / flow control -------------------------------------

    @Test
    fun getControlLinesMapsEnumSet() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        `when`(f.port.controlLines)
            .thenReturn(EnumSet.of(UsbSerialPort.ControlLine.RTS, UsbSerialPort.ControlLine.CTS))
        val lines = impl.getControlLines(portId)
        assertTrue(lines.getBoolean("rts"))
        assertTrue(lines.getBoolean("cts"))
        assertFalse(lines.getBoolean("dtr"))
        assertFalse(lines.getBoolean("ri"))
    }

    @Test
    fun setDtrDelegatesToPort() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        impl.setDTR(portId, true)
        verify(f.port).dtr = true
    }

    @Test
    fun getFlowControlMapsToString() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        `when`(f.port.flowControl).thenReturn(UsbSerialPort.FlowControl.RTS_CTS)
        assertEquals("rts_cts", impl.getFlowControl(portId).getString("mode"))
    }

    // --- detach cleanup ----------------------------------------------------

    @Test
    fun detachEmitsEventAndReapsPort() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)

        impl.onDeviceDetached(f.device)

        assertTrue("expected a detached event", events.any { it.first == "detached" })
        try {
            impl.read(portId, 8, 10)
            fail("expected PORT_NOT_OPEN after detach")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.PORT_NOT_OPEN, e.code)
        }
    }

    @Test
    fun disconnectDuringReadThrowsDeviceDisconnected() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        // Device no longer present in the system list -> classified as disconnect.
        `when`(usbManager.deviceList).thenReturn(hashMapOf())
        `when`(f.port.read(any(), anyInt())).thenThrow(IOException("gone"))
        try {
            impl.read(portId, 8, 50)
            fail("expected DEVICE_DISCONNECTED")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.DEVICE_DISCONNECTED, e.code)
        }
    }

    // --- dead-stream gates (a stopped manager must not block or swallow I/O) ---

    /** Inject a never-started manager (state STOPPED => isRunning() false) into the handle. */
    private fun injectDeadStream(f: Fixture, portId: String): SerialStreamManager {
        val endpoint = mock(android.hardware.usb.UsbEndpoint::class.java)
        `when`(endpoint.maxPacketSize).thenReturn(64)
        `when`(f.port.readEndpoint).thenReturn(endpoint)
        `when`(f.port.writeEndpoint).thenReturn(endpoint)
        val manager = SerialStreamManager(portId, f.port, {}, {})
        impl.portHandleForTest(portId).stream.set(manager)
        return manager
    }

    @Test
    fun writeWithDeadStreamFallsThroughToDirectWrite() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        injectDeadStream(f, portId)
        val result = impl.write(portId, b64("hi".toByteArray()), null)
        assertEquals(2, result.getInt("bytesWritten"))
        verify(f.port).write(any(), anyInt())
    }

    @Test
    fun readWithDeadStreamPerformsOneShotRead() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        injectDeadStream(f, portId)
        `when`(f.port.read(any(), anyInt())).thenReturn(0)
        assertEquals("", impl.read(portId, 8, 10).getString("data"))
    }

    @Test
    fun writeAsyncWithDeadStreamUsesPortExecutor() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        injectDeadStream(f, portId)
        impl.writeAsync(portId, b64("hi".toByteArray()))
        impl.portHandleForTest(portId).executor.submit {}.get() // drain the executor
        verify(f.port).write(any(), anyInt())
    }

    // --- permission coalescing / exactly-once settling ----------------------

    @Test
    fun secondRequestCoalescesOntoPendingDialog() {
        val f = registerDevice(hasPermission = false)
        impl.requestPermission(f.deviceId, "cb1")
        impl.requestPermission(f.deviceId, "cb2")
        verify(usbManager, org.mockito.Mockito.times(1))
            .requestPermission(any(UsbDevice::class.java), any(android.app.PendingIntent::class.java))
        impl.onPermissionResult(f.deviceId, true)
        assertEquals(
            listOf(Triple("cb1", true, false), Triple("cb2", true, true)),
            permissions,
        )
    }

    @Test
    fun detachRejectsPendingPermissionsExactlyOnce() {
        val f = registerDevice(hasPermission = false)
        impl.requestPermission(f.deviceId, "cb1")
        impl.requestPermission(f.deviceId, "cb2")
        impl.onDeviceDetached(f.device)
        assertEquals(
            listOf("cb1" to UsbSerialErrorCode.NO_DEVICE, "cb2" to UsbSerialErrorCode.NO_DEVICE),
            rejections,
        )
        // A late broadcast after the detach settle must be a no-op (no double settle).
        impl.onPermissionResult(f.deviceId, true)
        assertTrue(permissions.isEmpty())
        assertEquals(2, rejections.size)
    }

    @Test
    fun teardownRejectsPendingPermissions() {
        val f = registerDevice(hasPermission = false)
        impl.requestPermission(f.deviceId, "cb1")
        impl.teardown()
        assertEquals(listOf("cb1" to UsbSerialErrorCode.NO_DEVICE), rejections)
    }

    // --- cold-start attach buffering ----------------------------------------

    @Test
    fun coldStartAttachBuffersAndFlushesExactlyOnce() {
        val f = registerDevice(hasPermission = true)
        events.clear()
        impl.handleAttached(f.device, coldStart = true)
        assertEquals(1, events.count { it.first == "attached" }) // immediate emit
        impl.flushBufferedAttach()
        assertEquals(2, events.count { it.first == "attached" }) // replay to first listener
        impl.flushBufferedAttach()
        assertEquals(2, events.count { it.first == "attached" }) // second flush is a no-op
    }

    // --- isDisconnect identity check -----------------------------------------

    @Test
    fun reusedDevicePathWithDifferentDeviceClassifiesAsDisconnect() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        // Same /dev path now occupied by a different device (VID mismatch).
        val imposter = mock(UsbDevice::class.java)
        `when`(imposter.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(imposter.vendorId).thenReturn(0x1234)
        `when`(imposter.productId).thenReturn(0x6001)
        `when`(usbManager.deviceList).thenReturn(hashMapOf("/dev/bus/usb/001/002" to imposter))
        `when`(f.port.read(any(), anyInt())).thenThrow(IOException("gone"))
        try {
            impl.read(portId, 8, 50)
            fail("expected DEVICE_DISCONNECTED")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.DEVICE_DISCONNECTED, e.code)
        }
    }

    @Test
    fun presentDeviceWithOpenPortClassifiesAsIoError() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        `when`(f.port.isOpen).thenReturn(true) // Mockito default false would read as disconnect
        `when`(f.port.read(any(), anyInt())).thenThrow(IOException("transient"))
        try {
            impl.read(portId, 8, 50)
            fail("expected IO_ERROR")
        } catch (e: UsbSerialError) {
            assertEquals(UsbSerialErrorCode.IO_ERROR, e.code)
        }
    }

    // --- read() length validation --------------------------------------------

    @Test
    fun readRejectsBadLengths() {
        val f = registerDevice(hasPermission = true)
        val portId = openPort(f)
        for (bad in listOf(0, -1, (1 shl 20) + 1)) {
            try {
                impl.read(portId, bad, 10)
                fail("expected INVALID_PARAMS for length $bad")
            } catch (e: UsbSerialError) {
                assertEquals(UsbSerialErrorCode.INVALID_PARAMS, e.code)
            }
        }
        verify(f.port, org.mockito.Mockito.never()).read(any(), anyInt())
    }

    // --- safeSerial reuses the open connection --------------------------------

    @Test
    fun listDevicesWithOpenPortDoesNotOpenSecondConnection() {
        val f = registerDevice(hasPermission = true) // listDevices in helper: 1st openDevice
        `when`(f.connection.serial).thenReturn("SN123")
        openPort(f) // 2nd openDevice (the real open)
        val dev = impl.listDevices().getJSONArray("devices").getJSONObject(0)
        assertEquals("SN123", dev.getString("serialNumber"))
        verify(usbManager, org.mockito.Mockito.times(2)).openDevice(f.device)
    }
}
