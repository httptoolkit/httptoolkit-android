package tech.httptoolkit.android

import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import com.lipisoft.toyshark.ClientPacketWriterImpl
import com.lipisoft.toyshark.SessionHandler
import com.lipisoft.toyshark.SessionManager
import com.lipisoft.toyshark.socket.SocketNIODataService
import com.lipisoft.toyshark.transport.tcp.PacketHeaderException
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
    private val clientReader = FileInputStream(vpnInterface.fileDescriptor)

    // Packets from upstream servers, received by this VPN
    private val clientWriter = FileOutputStream(vpnInterface.fileDescriptor)
    private val clientPacketWriter = ClientPacketWriterImpl(clientWriter)

    // Allocate the buffer for a single packet.
    private val packet = ByteBuffer.allocate(MAX_PACKET_LEN)!!

    // SessionHandler, which handles sessions whilst writing packets
    private val handler = SessionHandler.getInstance().apply {
        setWriter(clientPacketWriter)
    }

    // Background service & task for non-blocking socket
    private val dataService = SocketNIODataService(clientPacketWriter)
    private val dataServiceThread = Thread(dataService, "Socket NIO thread")

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

        SessionManager.INSTANCE.setTcpPortRedirections(portRedirections)
        dataServiceThread.start()

        var data: ByteArray
        var length: Int

        running = true
        while (running) {
            data = packet.array()

            length = clientReader.read(data)
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
                    Thread.sleep(100)
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
            dataService.setShutdown(true)
            dataServiceThread.interrupt()
        } else {
            Log.w(TAG, "Vpn runnable stopped, but it's not running")
        }
    }

}