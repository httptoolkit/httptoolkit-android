package tech.httptoolkit.android.vpn.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
import tech.httptoolkit.android.vpn.capture.ProxyProtocolHandler;
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory;
import tech.httptoolkit.android.vpn.transport.ip.IPv4Header;
import tech.httptoolkit.android.vpn.transport.PacketHeaderException;
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader;
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory;
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader;
import tech.httptoolkit.android.vpn.transport.udp.UDPPacketFactory;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Date;

import tech.httptoolkit.android.TagKt;

/**
 * Takes a session, and reads all available upstream data back into it.
 *
 * Used by the NIO thread, and run synchronously as part of that non-blocking loop.
 */
class SocketChannelReader {

	private final String TAG = TagKt.getTAG(this);

	private final ClientPacketWriter writer;

	// We're single-threaded, so we can just reuse a single max-size buffer
	// over and over instead of endlessly reallocating it.
	private final ByteBuffer readBuffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);

	public SocketChannelReader(ClientPacketWriter writer) {
		this.writer = writer;
	}

	public void read(Session session) {
		if (session.isAbortingConnection()) {
			Log.d(TAG,"shutting down aborted connection on read -> "+ session);
			session.shutdown();
			return;
		}

		AbstractSelectableChannel channel = session.getChannel();
		if (channel instanceof SocketChannel) {
			readTCP(session);
		} else if (channel instanceof DatagramChannel) {
			readUDP(session);
		} else {
			return;
		}

		// Resubscribe to reads, so that we're triggered again if more data arrives later.
		session.subscribeKey(SelectionKey.OP_READ);
	}
	
	private void readTCP(@NonNull Session session) {
		SocketChannel channel = (SocketChannel) session.getChannel();
		int len;

		try {
			do {
				len = channel.read(readBuffer);
				if (len > 0) { //-1 mean it reach the end of stream
					readBuffer.limit(len);
					readBuffer.flip();
					sendToRequester(readBuffer, session);
				} else if (len == -1) {
					Log.d(TAG,"End of data from remote server, will send FIN to client");
					Log.d(TAG,"send FIN to: " + session);
					sendFin(session);
					session.setAbortingConnection(true);
				}
			} while (len > 0);
		}catch(NotYetConnectedException e){
			Log.e(TAG,"socket not connected");
		}catch(ClosedByInterruptException e){
			Log.e(TAG,"ClosedByInterruptException reading SocketChannel: "+ e.getMessage());
		}catch(ClosedChannelException e){
			Log.e(TAG,"ClosedChannelException reading SocketChannel: "+ e.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error reading data from SocketChannel: "+ e.getMessage());
			session.setAbortingConnection(true);
		}
	}
	
	private void sendToRequester(ByteBuffer buffer, @NonNull Session session) {
		ProxyProtocolHandler proxyHandler = session.getProxySetupHandler();

		if (proxyHandler != null) {
			while (proxyHandler.wantsHandshakeData()) {
				proxyHandler.processHandshakeData(buffer);
			}

			// If the proxy handler now wants to write back, subscribe to do so:
			if (proxyHandler.hasHandshakeDataToSend()) {
				session.subscribeKey(SelectionKey.OP_WRITE);
			}

			if (proxyHandler.isPending() || !buffer.hasRemaining()) {
				return;
			}

			// Proxy setup is done - if the client is ready to write, subscribe to do so:
			if (session.hasDataToSend() && session.isDataForSendingReady()) {
				session.subscribeKey(SelectionKey.OP_WRITE);
			}

			// If the setup handler is done and the buffer has data left, pass that on down
			// to the client themselves like normal.
		}

		// Last piece of data is usually smaller than MAX_RECEIVE_BUFFER_SIZE. We use this as a
		// trigger to set PSH on the resulting TCP packet that goes to the VPN.
		session.setHasReceivedLastSegment(
				buffer.remaining() < DataConst.MAX_RECEIVE_BUFFER_SIZE
		);

		session.addReceivedData(buffer); // Copies into the internal stream

		//pushing all data to vpn client
		while(session.hasReceivedData()){
			pushDataToClient(session);
		}
	}
	/**
	 * create packet data and send it to VPN client
	 * @param session Session
	 */
	private void pushDataToClient(@NonNull Session session){
		if (!session.hasReceivedData()) {
			//no data to send
			Log.d(TAG,"no data for vpn client");
		}

		IPv4Header ipHeader = session.getLastIpHeader();
		TCPHeader tcpheader = session.getLastTcpHeader();
		// TODO What does 60 mean?
		int max = session.getMaxSegmentSize() - 60;

		if(max < 1) {
			max = 1024;
		}

		byte[] packetBody = session.getReceivedData(max);
		if(packetBody != null && packetBody.length > 0) {
			long unAck = session.getSendNext();
			long nextUnAck = session.getSendNext() + packetBody.length;
			session.setSendNext(nextUnAck);
			//we need this data later on for retransmission
			session.setUnackData(packetBody);
			session.setResendPacketCounter(0);

			byte[] data = TCPPacketFactory.createResponsePacketData(ipHeader,
					tcpheader, packetBody, session.hasReceivedLastSegment(),
					session.getRecSequence(), unAck,
					session.getTimestampSender(), session.getTimestampReplyto());

			writer.write(data);
		}
	}
	private void sendFin(Session session){
		final IPv4Header ipHeader = session.getLastIpHeader();
		final TCPHeader tcpheader = session.getLastTcpHeader();
		final byte[] data = TCPPacketFactory.createFinData(ipHeader, tcpheader,
				session.getRecSequence(), session.getSendNext(),
				session.getTimestampSender(), session.getTimestampReplyto());

		writer.write(data);
	}

	private void readUDP(Session session){
		DatagramChannel channel = (DatagramChannel) session.getChannel();
		int len;

		try {
			do {
				len = channel.read(readBuffer);
				if (len > 0) {
					readBuffer.limit(len);
					readBuffer.flip();

					//create UDP packet
					byte[] packetData = UDPPacketFactory.createResponsePacket(
							session.getLastIpHeader(), session.getLastUdpHeader(), readBuffer);

					//write to client
					writer.write(packetData);

					Log.d(TAG,"SDR: sent " + len + " bytes to UDP client, packetData.length: "
							+ packetData.length);
				}
			} while(len > 0);
		} catch(NotYetConnectedException ex){
			Log.e(TAG,"failed to read from unconnected UDP socket");
		} catch (IOException e) {
			Log.e(TAG,"Failed to read from UDP socket, aborting connection");
			session.setAbortingConnection(true);
		}
	}
}
