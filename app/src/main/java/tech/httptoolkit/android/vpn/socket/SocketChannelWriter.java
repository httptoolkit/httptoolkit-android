package tech.httptoolkit.android.vpn.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
import tech.httptoolkit.android.vpn.capture.ProxyProtocolHandler;
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Date;

import tech.httptoolkit.android.TagKt;

/**
 * Takes a VPN session, and writes all received data from it to the upstream channel.
 *
 * If any writes fail, it resubscribes to OP_WRITE, and tries again next time
 * that fires (as soon as the channel is ready for more data).
 *
 * Used by the NIO thread, and run synchronously as part of that non-blocking loop.
 */
public class SocketChannelWriter {
	private final String TAG = TagKt.getTAG(this);

	private final ClientPacketWriter writer;

	SocketChannelWriter(ClientPacketWriter writer) {
		this.writer = writer;
	}

	public boolean isReadyToWrite(@NonNull Session session) {
		ProxyProtocolHandler handler = session.getProxySetupHandler();

		if (handler != null) {
			return handler.hasHandshakeDataToSend();
		} else {
			return session.hasDataToSend() && session.isDataForSendingReady();
		}
	}

	public void write(@NonNull Session session) {
		AbstractSelectableChannel channel = session.getChannel();
		if (channel instanceof SocketChannel) {
			writeTCP(session);
		} else if(channel instanceof DatagramChannel) {
			writeUDP(session);
		} else {
			// We only ever create TCP & UDP channels, so this should never happen
			throw new IllegalArgumentException("Unexpected channel type: " + channel);
		}

		if (session.isAbortingConnection()) {
			Log.d(TAG,"removing aborted connection -> " + session);
			session.cancelKey();

			if (channel instanceof SocketChannel) {
				try {
					SocketChannel socketChannel = (SocketChannel) channel;
					if (socketChannel.isConnected()) {
						socketChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (channel instanceof DatagramChannel) {
				try {
					DatagramChannel datagramChannel = (DatagramChannel) channel;
					if (datagramChannel.isConnected()) {
						datagramChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			session.closeSession();
		}
	}

	private void writeUDP(Session session) {
		try {
			writePendingData(session);
			Date dt = new Date();
			session.connectionStartTime = dt.getTime();
		}catch(NotYetConnectedException ex2){
			session.setAbortingConnection(true);
			Log.e(TAG,"Error writing to unconnected-UDP server, will abort current connection: "+ex2.getMessage());
		} catch (IOException e) {
			session.setAbortingConnection(true);
			e.printStackTrace();
			Log.e(TAG,"Error writing to UDP server, will abort connection: "+e.getMessage());
		}
	}
	
	private void writeTCP(Session session) {
		if (!this.isReadyToWrite(session)) return;

		try {
			ProxyProtocolHandler setupHandler = session.getProxySetupHandler();

			if (setupHandler != null) {
				writeProxySetupData(setupHandler, session);
			} else {
				writePendingData(session);
			}
		} catch (NotYetConnectedException ex) {
			Log.e(TAG,"failed to write to unconnected socket: " + ex.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error writing to server: " + e.toString());
			
			//close connection with vpn client
			byte[] rstData = TCPPacketFactory.createRstData(
					session.getLastIpHeader(), session.getLastTcpHeader(), 0);

			writer.write(rstData);

			//remove session
			Log.e(TAG,"failed to write to remote socket, aborting connection");
			session.setAbortingConnection(true);
		}
	}

	private void writePendingData(Session session) throws IOException {
		AbstractSelectableChannel channel = session.getChannel();

		byte[] data = session.getSendingData();
		ByteBuffer buffer = ByteBuffer.allocate(data.length);
		buffer.put(data);
		buffer.flip();

		while (buffer.hasRemaining()) {
			int bytesWritten = channel instanceof SocketChannel
				? ((SocketChannel) channel).write(buffer)
				: ((DatagramChannel) channel).write(buffer);

			if (bytesWritten == 0) {
				break;
			}
		}

		if (buffer.hasRemaining()) {
			// The channel's own buffer is full, so we have to save this for later.
			Log.i(TAG, buffer.remaining() + " bytes unwritten for " + channel.toString());

			// Put the remaining data from the buffer back into the session
			session.setSendingData(buffer);

			// Subscribe to WRITE events, so we know when this is ready to resume.
			session.subscribeKey(SelectionKey.OP_WRITE);
		} else {
			// All done, all good -> wait until the next TCP PSH / UDP packet
			session.setDataForSendingReady(false);

			// We don't need to know about WRITE events any more, we've written all our data.
			// This is safe from races with new data, due to the session lock in NIO.
			session.unsubscribeKey(SelectionKey.OP_WRITE);
		}
	}

	private void writeProxySetupData(ProxyProtocolHandler proxyProtocolHandler, Session session) throws IOException {
		AbstractSelectableChannel channel = session.getChannel();

		ByteBuffer buffer = proxyProtocolHandler.getNextHandshakeData();

		while (buffer.hasRemaining()) {
			int bytesWritten = channel instanceof SocketChannel
					? ((SocketChannel) channel).write(buffer)
					: ((DatagramChannel) channel).write(buffer);

			if (bytesWritten == 0) {
				break;
			}
		}

		if (buffer.hasRemaining()) {
			// Wait for the next write event so we can continue:
			session.subscribeKey(SelectionKey.OP_WRITE);
			return;
		}

		proxyProtocolHandler.confirmHandshakeDataWritten();

		if (this.isReadyToWrite(session)) {
			// It's possible that this finishes the proxy protocol, and the session is now ready to write.
			// If so, we make sure to resubscribe, so this will fire again shortly:
			session.subscribeKey(SelectionKey.OP_WRITE);
		} else {
			// If not, we can unsubscribe from WRITE events for now:
			session.unsubscribeKey(SelectionKey.OP_WRITE);
		}
	}
}
