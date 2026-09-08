package com.capacitorusbserial.plugin

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BulkStreamManager(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    private val bufferSize: Int,
    private val timeout: Int,
    private val onData: (ByteArray) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            val buffer = ByteArray(bufferSize)
            try {
                while (running.get()) {
                    val count = connection.bulkTransfer(endpoint, buffer, buffer.size, timeout)
                    // Android reports a timed-out bulkTransfer as -1 as well as hard
                    // failures. Keep polling; detach broadcasts own definitive cleanup.
                    if (count > 0) onData(buffer.copyOf(count))
                }
            } catch (e: Exception) {
                if (running.getAndSet(false)) onError(e)
            }
        }
    }

    fun stop() {
        running.set(false)
        executor.shutdownNow()
    }

    fun isRunning(): Boolean = running.get()
}
