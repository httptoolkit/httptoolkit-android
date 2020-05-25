package tech.httptoolkit.android

import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import tech.httptoolkit.android.vpn.ClientPacketWriter
import tech.httptoolkit.android.vpn.SessionHandler
import tech.httptoolkit.android.vpn.SessionManager
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import tech.httptoolkit.android.vpn.transport.PacketHeaderException
import io.sentry.Sentry
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

// Taken from ToyShark - I suspect this is somewhat arbitrary
private const val MAX_PACKET_LEN = 1500

class ProxyVpnRunnable(
    vpnInterface: ParcelFileDescriptor,
    proxyHost: String,
    proxyPort: Int,
    redirectPorts: IntArray
) : Runnable {

    @Volatile private var running = false

    // Packets from device apps downstream, heading upstream via this VPN
    private val vpnReadStream = FileInputStream(vpnInterface.fileDescriptor)

    // Packets from upstream servers, received by this VPN
    private val vpnWriteStream = FileOutputStream(vpnInterface.fileDescriptor)
    private val vpnPacketWriter = ClientPacketWriter(vpnWriteStream)
    private val vpnPacketWriterThread = Thread(vpnPacketWriter)

    // Background service & task for non-blocking socket
    private val nioService = SocketNIODataService(vpnPacketWriter)
    private val dataServiceThread = Thread(nioService, "Socket NIO thread")

    private val manager = SessionManager(nioService)
    private val handler = SessionHandler(manager, nioService, vpnPacketWriter)

    // Allocate the buffer for a single packet.
    private val packet = ByteBuffer.allocate(MAX_PACKET_LEN)!!

    // Our redirect rules, defining which traffic should be forwarded to what proxy address
    private val portRedirections = SparseArray<InetSocketAddress>().apply {
        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        redirectPorts.forEach {
            append(it, proxyAddress)
        }
    }

    override fun run() {
        if (running) {
            Log.w(TAG, "Vpn runnable started, but it's already running")
            return
        }

        Log.i(TAG, "Vpn thread starting")

        manager.setTcpPortRedirections(portRedirections)
        dataServiceThread.start()
        vpnPacketWriterThread.start()

        var data: ByteArray
        var length: Int

        running = true
        while (running) {
            data = packet.array()

            length = vpnReadStream.read(data)
            if (length > 0) {
                try {
                    packet.limit(length)
                    handler.handlePacket(packet)
                } catch (e: PacketHeaderException) {
                    Sentry.capture(e)
                    Log.e(TAG, e.message)
                }

                packet.clear()
            } else {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Failed to sleep: " + e.message)
                }

            }
        }

        Log.i(TAG, "Vpn thread shutting down")
    }

    fun stop() {
        if (running) {
            running = false
            nioService.shutdown()
            dataServiceThread.interrupt()

            vpnPacketWriter.shutdown()
            vpnPacketWriterThread.interrupt()
        } else {
            Log.w(TAG, "Vpn runnable stopped, but it's not running")
        }
    }

}