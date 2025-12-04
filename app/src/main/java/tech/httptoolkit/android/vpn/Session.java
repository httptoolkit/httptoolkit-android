/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package tech.httptoolkit.android.vpn;

import android.util.Log;

import androidx.annotation.Nullable;

import tech.httptoolkit.android.vpn.capture.ProxyProtocolHandler;
import tech.httptoolkit.android.vpn.transport.ip.IPv4Header;
import tech.httptoolkit.android.vpn.socket.ICloseSession;
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader;
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;

/**
 * store information about a socket connection from a VPN client.
 * Each session is used by background worker to server request from client.
 * @author Borey Sao
 * Date: May 19, 2014
 */
public class Session {
	private final String TAG = "Session";

	private AbstractSelectableChannel channel;

	private final SessionProtocol protocol;
	
	private final int destIp;
	private final int destPort;
	
	private final int sourceIp;
	private final int sourcePort;
	
	//sequence received from client
	private long recSequence = 0;
	
	//track ack we sent to client and waiting for ack back from client
	private long sendUnack = 0;
	private boolean isacked = false;//last packet was acked yet?
	
	//the next ack to send to client
	private long sendNext = 0;
	
	//sent by client during SYN inside tcp options
	private int maxSegmentSize = 0;
	
	//indicate that 3-way handshake has been completed or not
	private boolean isConnected = false;
	
	//receiving buffer for storing data from remote host
	private final ByteArrayOutputStream receivingStream;
	
	//sending buffer for storing data from vpn client to be send to destination host
	private final ByteArrayOutputStream sendingStream;
	
	private boolean hasReceivedLastSegment = false;
	
	//last packet received from client
	private IPv4Header lastIpHeader;
	private TCPHeader lastTcpHeader;
	private UDPHeader lastUdpHeader;

	//true when connection is about to be close
	private boolean closingConnection = false;
	
	//indicate data from client is ready for sending to destination
	private volatile boolean isDataForSendingReady = false;
	
	//store data for retransmission
	private byte[] unackData = null;
	
	//in ACK packet from client, if the previous packet was corrupted, client will send flag in options field
	private boolean packetCorrupted = false;
	
	//track how many time a packet has been retransmitted => avoid loop
	private int resendPacketCounter = 0;
	
	private int timestampSender = 0;
	private int timestampReplyto = 0;
	
	//indicate that vpn client has sent FIN flag and it has been acked
	private boolean ackedToFin = false;
	
	//closing session and aborting connection, will be done by background task
	private volatile boolean abortingConnection = false;
	
	private SelectionKey selectionkey = null;
	
	public long connectionStartTime = 0;

	private final ICloseSession sessionCloser;
	
	// Proxy protocol handler - set only while the proxy connection
	// is being established, then null afterwards.
	private ProxyProtocolHandler proxyHandler;
	
	Session(
		SessionProtocol protocol,
		int sourceIp,
		int sourcePort,
		int destinationIp,
		int destinationPort,
		ICloseSession sessionCloser,
		ProxyProtocolHandler proxyHandler
	) {
		receivingStream = new ByteArrayOutputStream();
		sendingStream = new ByteArrayOutputStream();

		this.protocol = protocol;
		this.sourceIp = sourceIp;
		this.sourcePort = sourcePort;
		this.destIp = destinationIp;
		this.destPort = destinationPort;

		this.sessionCloser = sessionCloser;
		this.proxyHandler = proxyHandler;
	}

	@Nullable
	public ProxyProtocolHandler getProxySetupHandler() {
		if (this.proxyHandler == null) {
			return null;
		} else if (!this.proxyHandler.isPending()) {
			this.proxyHandler = null;
			return null;
		} else {
			return this.proxyHandler;
		}
	}

	/**
	 * append more data
	 * @param data Data
	 */
	public synchronized void addReceivedData(ByteBuffer data){
	   receivingStream.write(data.array(), data.position(), data.remaining());
	}

	/**
	 * get all data received in the buffer and empty it.
	 * @return byte[]
	 */
	public synchronized byte[] getReceivedData(int maxSize){
		byte[] data = receivingStream.toByteArray();
		receivingStream.reset();
		if(data.length > maxSize){
			byte[] small = new byte[maxSize];
			System.arraycopy(data, 0, small, 0, maxSize);
			int len = data.length - maxSize;
			receivingStream.write(data, maxSize, len);
			data = small;
		}
		return data;
	}

	/**
	 * buffer has more data for vpn client
	 * @return boolean
	 */
	public boolean hasReceivedData(){
		return receivingStream.size() > 0;
	}

	/**
	 * set data to be sent to destination server
	 * @param data Data to be sent
	 * @return boolean Success or not
	 */
	public synchronized int setSendingData(ByteBuffer data) {
		final int remaining = data.remaining();
		sendingStream.write(data.array(), data.position(), data.remaining());
		return remaining;
	}

	/**
	 * dequeue data for sending to server
	 * @return byte[]
	 */
	public synchronized byte[] getSendingData(){
		byte[] data = sendingStream.toByteArray();
		sendingStream.reset();
		return data;
	}
	/**
	 * buffer contains data for sending to destination server
	 * @return boolean
	 */
	public boolean hasDataToSend(){
		return sendingStream.size() > 0;
	}

	public SessionProtocol getProtocol() {
		return this.protocol;
	}

	public int getDestIp() {
		return destIp;
	}

	public int getDestPort() {
		return destPort;
	}

	long getSendUnack() {
		return sendUnack;
	}

	void setSendUnack(long sendUnack) {
		this.sendUnack = sendUnack;
	}

	public long getSendNext() {
		return sendNext;
	}

	public void setSendNext(long sendNext) {
		this.sendNext = sendNext;
	}

	public int getMaxSegmentSize() {
		return maxSegmentSize;
	}

	void setMaxSegmentSize(int maxSegmentSize) {
		this.maxSegmentSize = maxSegmentSize;
	}

	public boolean isConnected() {
		return isConnected;
	}

	public void setConnected(boolean isConnected) {
		this.isConnected = isConnected;
	}

	public int getSourceIp() {
		return sourceIp;
	}

	public int getSourcePort() {
		return sourcePort;
	}

	void setAcked(boolean isacked) {
		this.isacked = isacked;
	}

	public long getRecSequence() {
		return recSequence;
	}

	void setRecSequence(long recSequence) {
		this.recSequence = recSequence;
	}

	public AbstractSelectableChannel getChannel() {
		return channel;
	}

	public void setChannel(AbstractSelectableChannel channel) {
		this.channel = channel;
	}

	public boolean hasReceivedLastSegment() {
		return hasReceivedLastSegment;
	}
	public void setHasReceivedLastSegment(boolean hasReceivedLastSegment) {
		this.hasReceivedLastSegment = hasReceivedLastSegment;
	}
	public synchronized IPv4Header getLastIpHeader() {
		return lastIpHeader;
	}
	synchronized void setLastIpHeader(IPv4Header lastIpHeader) {
		this.lastIpHeader = lastIpHeader;
	}
	public synchronized TCPHeader getLastTcpHeader() {
		return lastTcpHeader;
	}
	synchronized void setLastTcpHeader(TCPHeader lastTcpHeader) {
		this.lastTcpHeader = lastTcpHeader;
	}
	
	public synchronized UDPHeader getLastUdpHeader() {
		return lastUdpHeader;
	}
	synchronized void setLastUdpHeader(UDPHeader lastUdpHeader) {
		this.lastUdpHeader = lastUdpHeader;
	}
	boolean isClosingConnection() {
		return closingConnection;
	}
	void setClosingConnection(boolean closingConnection) {
		this.closingConnection = closingConnection;
	}
	public boolean isDataForSendingReady() {
		return isDataForSendingReady;
	}
	public void setDataForSendingReady(boolean isDataForSendingReady) {
		this.isDataForSendingReady = isDataForSendingReady;
	}
	public void setUnackData(byte[] unackData) {
		this.unackData = unackData;
	}
	
	void setPacketCorrupted(boolean packetCorrupted) {
		this.packetCorrupted = packetCorrupted;
	}
	public void setResendPacketCounter(int resendPacketCounter) {
		this.resendPacketCounter = resendPacketCounter;
	}
	public int getTimestampSender() {
		return timestampSender;
	}
	void setTimestampSender(int timestampSender) {
		this.timestampSender = timestampSender;
	}
	public int getTimestampReplyto() {
		return timestampReplyto;
	}
	void setTimestampReplyto(int timestampReplyto) {
		this.timestampReplyto = timestampReplyto;
	}
	boolean isAckedToFin() {
		return ackedToFin;
	}

	public boolean isAbortingConnection() {
		return abortingConnection;
	}
	public void setAbortingConnection(boolean abortingConnection) {
		this.abortingConnection = abortingConnection;
	}
	public SelectionKey getSelectionKey() {
		return selectionkey;
	}
	public void setSelectionKey(SelectionKey selectionkey) {
		this.selectionkey = selectionkey;
	}

	public void cancelKey() {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.cancel();
		}
	}

	public void subscribeKey(int OP) {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.interestOps(this.selectionkey.interestOps() | OP);
		}
	}

	public void unsubscribeKey(int OP) {
		synchronized (this.selectionkey) {
			if (!this.selectionkey.isValid()) return;
			this.selectionkey.interestOps(this.selectionkey.interestOps() & ~OP);
		}
	}

	// Cleanly close a session - stopping all events, closing the upstream connection, and unregistering
	// from the session manager
	public void shutdown() {
		AbstractSelectableChannel channel = this.getChannel();
		this.cancelKey();

		try {
			if (channel instanceof SocketChannel) {
				SocketChannel socketChannel = (SocketChannel) channel;
				if (socketChannel.isConnected()) {
					socketChannel.close();
				}
			} else {
				DatagramChannel datagramChannel = (DatagramChannel) channel;
				if (datagramChannel.isConnected()) {
					datagramChannel.close();
				}
			}
		} catch (IOException e) {
			Log.e(TAG, e.toString());
		}

		this.sessionCloser.closeSession(this);
	}

	public String getSessionKey() {
		return Session.getSessionKey(this.protocol, this.destIp, this.destPort, this.sourceIp, this.sourcePort);
	}

	public static String getSessionKey(SessionProtocol protocol, int destIp, int destPort, int sourceIp, int sourcePort) {
		return protocol.name() + "|" +
			PacketUtil.intToIPAddress(sourceIp) + ":" + sourcePort +
			"->" +
			PacketUtil.intToIPAddress(destIp) + ":" + destPort;
	}

	public String toString() {
		return "Session (" + this.getSessionKey() + ")";
	}
}
