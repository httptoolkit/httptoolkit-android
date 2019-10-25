package com.lipisoft.toyshark.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import com.lipisoft.toyshark.IClientPacketWriter;
import com.lipisoft.toyshark.Session;
import com.lipisoft.toyshark.SessionManager;
import com.lipisoft.toyshark.transport.tcp.TCPPacketFactory;
import com.lipisoft.toyshark.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Date;

public class SocketDataWriterWorker implements Runnable {
	private static final String TAG = "SocketDataWriterWorker";

	private static IClientPacketWriter writer;
	@NonNull private String sessionKey;

	SocketDataWriterWorker(IClientPacketWriter writer, @NonNull String sessionKey) {
		this.writer = writer;
		this.sessionKey = sessionKey;
	}

	@Override
	public void run() {
		final Session session = SessionManager.INSTANCE.getSessionByKey(sessionKey);
		if(session == null) {
			Log.d(TAG, "No session related to " + sessionKey + "for write");
			return;
		}

		session.setBusywrite(true);

		AbstractSelectableChannel channel = session.getChannel();
		if(channel instanceof SocketChannel){
			writeTCP(session);
		}else if(channel instanceof DatagramChannel){
			writeUDP(session);
		} else {
			return;
		}
		session.setBusywrite(false);

		if(session.isAbortingConnection()){
			Log.d(TAG,"removing aborted connection -> " + sessionKey);
			session.cancelKey();

			if(channel instanceof SocketChannel) {
				try {
					SocketChannel socketChannel = (SocketChannel) channel;
					if (socketChannel.isConnected()) {
						socketChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if(channel instanceof DatagramChannel) {
				try {
					DatagramChannel datagramChannel = (DatagramChannel) channel;
					if (datagramChannel.isConnected()) {
						datagramChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			SessionManager.INSTANCE.closeSession(session);
		}
	}

	private void writeUDP(Session session) {
		String name = PacketUtil.intToIPAddress(session.getDestIp())+":"+session.getDestPort()+
				"-"+PacketUtil.intToIPAddress(session.getSourceIp())+":"+session.getSourcePort();

		try {
			Log.d(TAG,"writing data to remote UDP: "+name);
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
		String name = PacketUtil.intToIPAddress(session.getDestIp())+":"+session.getDestPort()+
				"-"+PacketUtil.intToIPAddress(session.getSourceIp())+":"+session.getSourcePort();

		try {
			Log.d(TAG,"writing TCP data to: " + name);
			writePendingData(session);
		} catch (NotYetConnectedException ex) {
			Log.e(TAG,"failed to write to unconnected socket: " + ex.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error writing to server: " + e.getMessage());
			
			//close connection with vpn client
			byte[] rstData = TCPPacketFactory.createRstData(
					session.getLastIpHeader(), session.getLastTcpHeader(), 0);
			try {
				writer.write(rstData);
				SocketData socketData = SocketData.getInstance();
				socketData.addData(rstData);
			} catch (IOException ex) {
				ex.printStackTrace();
			}
			//remove session
			Log.e(TAG,"failed to write to remote socket, aborting connection");
			session.setAbortingConnection(true);
		}
	}

	private void writePendingData(Session session) throws IOException {
		if (!session.hasDataToSend()) return;
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

			// Put the remaining data from the buffer back into the session
			session.setSendingData(buffer);

			// Subscribe to WRITE events, so we know when this is ready to resume
			session.subscribeKey(SelectionKey.OP_WRITE);
		} else {
			// All done, all good -> wait until the next TCP PSH / UDP packet
			session.setDataForSendingReady(false);

			// If we were interested in WRITE events, we're definitely not now
			session.unsubscribeKey(SelectionKey.OP_WRITE);
		}
	}
}
