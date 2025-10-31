package tech.httptoolkit.android

import android.os.ParcelFileDescriptor
import android.util.Log
import tech.httptoolkit.android.vpn.ClientPacketWriter
import tech.httptoolkit.android.vpn.SessionHandler
import tech.httptoolkit.android.vpn.SessionManager
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import io.sentry.Sentry
import tech.httptoolkit.android.vpn.capture.CaptureController
import tech.httptoolkit.android.vpn.capture.DirectHandler
import tech.httptoolkit.android.vpn.capture.SOCKS5Handler
import tech.httptoolkit.android.vpn.transport.PacketHeaderException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.nio.ByteBuffer

// Set on our VPN as the MTU, which should guarantee all packets fit this
const val MAX_PACKET_LEN = 1500

class ProxyVpnRunnable(
    vpnInterface: ParcelFileDescriptor,
    proxyConfig: ProxyConfig,
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

    private val captureController = CaptureController(proxyConfig, redirectPorts.toList())
    private val manager = SessionManager(captureController)
    private val handler = SessionHandler(manager, nioService, vpnPacketWriter)

    // Allocate the buffer for a single packet.
    private val packet = ByteBuffer.allocate(MAX_PACKET_LEN)

    override fun run() {
        if (running) {
            Log.w(TAG, "Vpn runnable started, but it's already running")
            return
        }

        Log.i(TAG, "Vpn thread starting")

        dataServiceThread.start()
        vpnPacketWriterThread.start()

        var data: ByteArray
        var length: Int

        running = true
        while (running) {
            try {
                data = packet.array()

                length = vpnReadStream.read(data)
                if (length > 0) {
                    try {
                        packet.limit(length)
                        handler.handlePacket(packet)
                    } catch (e: Exception) {
                        val errorMessage = (e.message ?: e.toString())
                        Log.e(TAG, errorMessage)

                        val isIgnorable =
                            (e is ConnectException && errorMessage == "Permission denied") ||
                            // Nothing we can do if the internet goes down:
                            (e is ConnectException && errorMessage == "Network is unreachable") ||
                            (e is ConnectException && errorMessage.contains("ENETUNREACH")) ||
                            // Too many open files - can't make more sockets, not much we can do:
                            (e is ConnectException && errorMessage == "Too many open files") ||
                            (e is ConnectException && errorMessage.contains("EMFILE")) ||
                            // IPv6 is not supported here yet:
                            (e is PacketHeaderException && errorMessage.contains("IP version should be 4 but was 6"))

                        if (!isIgnorable) {
                            Sentry.captureException(e)
                        }
                    }

                    packet.clear()
                } else {
                    Thread.sleep(10)
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Sleep interrupted: " + e.message)
            } catch (e: InterruptedIOException) {
                Log.i(TAG, "Read interrupted: " + e.message)
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