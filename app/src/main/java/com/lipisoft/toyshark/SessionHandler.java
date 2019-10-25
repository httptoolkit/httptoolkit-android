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

package com.lipisoft.toyshark;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import com.lipisoft.toyshark.network.ip.IPPacketFactory;
import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.socket.SocketData;
import com.lipisoft.toyshark.transport.tcp.PacketHeaderException;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.tcp.TCPPacketFactory;
import com.lipisoft.toyshark.transport.ITransportHeader;
import com.lipisoft.toyshark.transport.udp.UDPHeader;
import com.lipisoft.toyshark.transport.udp.UDPPacketFactory;
import com.lipisoft.toyshark.util.PacketUtil;

import androidx.annotation.NonNull;
import android.util.Log;

/**
 * handle VPN client request and response. it create a new session for each VPN client.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class SessionHandler {
	private static final String TAG = "SessionHandler";

	private static final SessionHandler handler = new SessionHandler();

	private Queue<Session> writableSessionsQueue = new LinkedList<>();
	private IClientPacketWriter writer;
	private SocketData packetData;

	public static SessionHandler getInstance(){
		return handler;
	}

	private SessionHandler(){
		packetData = SocketData.getInstance();
	}

	public Queue<Session> getWritableSessions() {
		return this.writableSessionsQueue;
	}

	public void setWriter(IClientPacketWriter writer){
		this.writer = writer;
	}

	private void handleUDPPacket(ByteBuffer clientPacketData, IPv4Header ipHeader, UDPHeader udpheader){
		Session session = SessionManager.INSTANCE.getSession(ipHeader.getDestinationIP(), udpheader.getDestinationPort(),
				ipHeader.getSourceIP(), udpheader.getSourcePort());

		if(session == null){
			session = SessionManager.INSTANCE.createNewUDPSession(ipHeader.getDestinationIP(), udpheader.getDestinationPort(),
					ipHeader.getSourceIP(), udpheader.getSourcePort());
		}

		if(session == null){
			return;
		}

		session.setLastIpHeader(ipHeader);
		session.setLastUdpHeader(udpheader);
		int len = SessionManager.INSTANCE.addClientData(clientPacketData, session);
		session.setDataForSendingReady(true);

		// Put this on the queue and nudge the socket NIO thread to write it
		this.writableSessionsQueue.add(session);
		session.getSelectionKey().selector().wakeup();
		Log.d(TAG,"added UDP data for bg worker to send: "+len);

		SessionManager.INSTANCE.keepSessionAlive(session);
	}

	private void handleTCPPacket(ByteBuffer clientPacketData, IPv4Header ipHeader, TCPHeader tcpheader){
		int dataLength = clientPacketData.limit() - clientPacketData.position();
		int sourceIP = ipHeader.getSourceIP();
		int destinationIP = ipHeader.getDestinationIP();
		int sourcePort = tcpheader.getSourcePort();
		int destinationPort = tcpheader.getDestinationPort();

		if(tcpheader.isSYN()) {
			//3-way handshake + create new session
			//set windows size and scale, set reply time in options
			replySynAck(ipHeader,tcpheader);
		} else if(tcpheader.isACK()) {
			String key = Session.getSessionKey(destinationIP, destinationPort, sourceIP, sourcePort);
			Session session = SessionManager.INSTANCE.getSessionByKey(key);

			if(session == null) {
				if (tcpheader.isFIN()) {
					sendLastAck(ipHeader, tcpheader);
				} else if (!tcpheader.isRST()) {
					sendRstPacket(ipHeader, tcpheader, dataLength);
				}
				else {
					Log.e(TAG,"**** ==> Session not found: " + key);
				}
				return;
			}

			session.setLastIpHeader(ipHeader);
			session.setLastTcpHeader(tcpheader);

			//any data from client?
			if(dataLength > 0) {
				//accumulate data from client
				if(session.getRecSequence() == 0 || tcpheader.getSequenceNumber() >= session.getRecSequence()) {
					int addedLength = SessionManager.INSTANCE.addClientData(clientPacketData, session);
					//send ack to client only if new data was added
					sendAck(ipHeader, tcpheader, addedLength, session);
				} else {
					sendAckForDisorder(ipHeader, tcpheader, dataLength);
				}
			} else {
				//an ack from client for previously sent data
				acceptAck(tcpheader, session);

				if(session.isClosingConnection()){
					sendFinAck(ipHeader, tcpheader, session);
				}else if(session.isAckedToFin() && !tcpheader.isFIN()){
					//the last ACK from client after FIN-ACK flag was sent
					SessionManager.INSTANCE.closeSession(destinationIP, destinationPort, sourceIP, sourcePort);
					Log.d(TAG,"got last ACK after FIN, session is now closed.");
				}
			}
			//received the last segment of data from vpn client
			if(tcpheader.isPSH()){
				//push data to destination here. Background thread will receive data and fill session's buffer.
				//Background thread will send packet to client
				pushDataToDestination(session, tcpheader);
			} else if(tcpheader.isFIN()){
				//fin from vpn client is the last packet
				//ack it
				Log.d(TAG,"FIN from vpn client, will ack it.");
				ackFinAck(ipHeader, tcpheader, session);
			} else if(tcpheader.isRST()){
				resetConnection(ipHeader, tcpheader);
			}

			if(!session.isClientWindowFull() && !session.isAbortingConnection()){
				SessionManager.INSTANCE.keepSessionAlive(session);
			}
		} else if(tcpheader.isFIN()){
			//case client sent FIN without ACK
			Session session = SessionManager.INSTANCE.getSession(destinationIP, destinationPort, sourceIP, sourcePort);
			if(session == null)
				ackFinAck(ipHeader, tcpheader, null);
			else
				SessionManager.INSTANCE.keepSessionAlive(session);

		} else if(tcpheader.isRST()){
			resetConnection(ipHeader, tcpheader);
		} else {
			Log.d(TAG,"unknown TCP flag");
			String str1 = PacketUtil.getOutput(ipHeader, tcpheader, clientPacketData.array());
			Log.d(TAG,">>>>>>>> Received from client <<<<<<<<<<");
			Log.d(TAG,str1);
			Log.d(TAG,">>>>>>>>>>>>>>>>>>>end receiving from client>>>>>>>>>>>>>>>>>>>>>");
		}
	}

	/**
	 * handle each packet from each vpn client
	 * @param stream ByteBuffer to be read
	 */
	public void handlePacket(@NonNull ByteBuffer stream) throws PacketHeaderException {
		final byte[] rawPacket = new byte[stream.limit()];
		stream.get(rawPacket, 0, stream.limit());
		packetData.addData(rawPacket);
		stream.rewind();

		final IPv4Header ipHeader = IPPacketFactory.createIPv4Header(stream);

		final ITransportHeader transportHeader;
		if(ipHeader.getProtocol() == 6) {
			transportHeader = TCPPacketFactory.createTCPHeader(stream);
		} else if(ipHeader.getProtocol() == 17) {
			transportHeader = UDPPacketFactory.createUDPHeader(stream);
		} else {
			Log.e(TAG, "******===> Unsupported protocol: " + ipHeader.getProtocol());
			return;
		}

		if (transportHeader instanceof TCPHeader) {
			handleTCPPacket(stream, ipHeader, (TCPHeader) transportHeader);
		} else if (ipHeader.getProtocol() == 17){
			handleUDPPacket(stream, ipHeader, (UDPHeader) transportHeader);
		}
	}

	private void sendRstPacket(IPv4Header ip, TCPHeader tcp, int dataLength){
		byte[] data = TCPPacketFactory.createRstData(ip, tcp, dataLength);
		try {
			writer.write(data);
			packetData.addData(data);
			Log.d(TAG,"Sent RST Packet to client with dest => " +
					PacketUtil.intToIPAddress(ip.getDestinationIP()) + ":" +
					tcp.getDestinationPort());
		} catch (IOException e) {
			Log.e(TAG,"failed to send RST packet: " + e.getMessage());
		}
	}

	private void sendLastAck(IPv4Header ip, TCPHeader tcp){
		byte[] data = TCPPacketFactory.createResponseAckData(ip, tcp, tcp.getSequenceNumber()+1);
		try {
			writer.write(data);
			packetData.addData(data);
			Log.d(TAG,"Sent last ACK Packet to client with dest => " +
					PacketUtil.intToIPAddress(ip.getDestinationIP()) + ":" +
					tcp.getDestinationPort());
		} catch (IOException e) {
			Log.e(TAG,"failed to send last ACK packet: " + e.getMessage());
		}
	}

	private void ackFinAck(IPv4Header ip, TCPHeader tcp, Session session){
		long ack = tcp.getSequenceNumber() + 1;
		long seq = tcp.getAckNumber();
		byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq, true, true);
		try {
			writer.write(data);
			packetData.addData(data);
			if(session != null){
				session.cancelKey();
				SessionManager.INSTANCE.closeSession(session);
				Log.d(TAG,"ACK to client's FIN and close session => "+PacketUtil.intToIPAddress(ip.getDestinationIP())+":"+tcp.getDestinationPort()
						+"-"+PacketUtil.intToIPAddress(ip.getSourceIP())+":"+tcp.getSourcePort());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void sendFinAck(IPv4Header ip, TCPHeader tcp, Session session){
		final long ack = tcp.getSequenceNumber();
		final long seq = tcp.getAckNumber();
		final byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq,true,false);
		final ByteBuffer stream = ByteBuffer.wrap(data);
		try {
			writer.write(data);
			packetData.addData(data);
			Log.d(TAG,"00000000000 FIN-ACK packet data to vpn client 000000000000");
			IPv4Header vpnip = null;
			try {
				vpnip = IPPacketFactory.createIPv4Header(stream);
			} catch (PacketHeaderException e) {
				e.printStackTrace();
			}

			TCPHeader vpntcp = null;
			try {
				if (vpnip != null)
					vpntcp = TCPPacketFactory.createTCPHeader(stream);
			} catch (PacketHeaderException e) {
				e.printStackTrace();
			}

			if(vpnip != null && vpntcp != null){
				String sout = PacketUtil.getOutput(vpnip, vpntcp, data);
				Log.d(TAG,sout);
			}
			Log.d(TAG,"0000000000000 finished sending FIN-ACK packet to vpn client 000000000000");

		} catch (IOException e) {
			Log.e(TAG,"Failed to send ACK packet: "+e.getMessage());
		}
		session.setSendNext(seq + 1);
		//avoid re-sending it, from here client should take care the rest
		session.setClosingConnection(false);
	}

	private void pushDataToDestination(Session session, TCPHeader tcp){
		session.setDataForSendingReady(true);
		session.setTimestampReplyto(tcp.getTimeStampSender());
		session.setTimestampSender((int)System.currentTimeMillis());

		// Put this on the queue and nudge the socket NIO thread to write it
		this.writableSessionsQueue.add(session);
		session.getSelectionKey().selector().wakeup();

		Log.d(TAG,"set data ready for sending to dest, bg will do it. data size: "
                + session.getSendingDataSize());
	}
	
	/**
	 * send acknowledgment packet to VPN client
	 * @param ipheader IP Header
	 * @param tcpheader TCP Header
	 * @param acceptedDataLength Data Length
	 * @param session Session
	 */
	private void sendAck(IPv4Header ipheader, TCPHeader tcpheader, int acceptedDataLength, Session session){
		long acknumber = session.getRecSequence() + acceptedDataLength;
		Log.d(TAG,"sent ack, ack# "+session.getRecSequence()+" + "+acceptedDataLength+" = "+acknumber);
		session.setRecSequence(acknumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipheader, tcpheader, acknumber);
		try {
			writer.write(data);
			packetData.addData(data);
		} catch (IOException e) {
			Log.e(TAG,"Failed to send ACK packet: " + e.getMessage());
		}
	}

	private void sendAckForDisorder(IPv4Header ipHeader, TCPHeader tcpheader, int acceptedDataLength) {
		long ackNumber = tcpheader.getSequenceNumber() + acceptedDataLength;
		Log.d(TAG,"sent ack, ack# " + tcpheader.getSequenceNumber() +
				" + " + acceptedDataLength + " = " + ackNumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipHeader, tcpheader, ackNumber);
		try {
			writer.write(data);
			packetData.addData(data);
		} catch (IOException e) {
			Log.e(TAG,"Failed to send ACK packet: " + e.getMessage());
		}
	}

	/**
	 * acknowledge a packet and adjust the receiving window to avoid congestion.
	 * @param tcpHeader TCP Header
	 * @param session Session
	 */
	private void acceptAck(TCPHeader tcpHeader, Session session){
		boolean isCorrupted = PacketUtil.isPacketCorrupted(tcpHeader);
		session.setPacketCorrupted(isCorrupted);
		if(isCorrupted){
			Log.e(TAG,"prev packet was corrupted, last ack# " + tcpHeader.getAckNumber());
		}
		if(tcpHeader.getAckNumber() > session.getSendUnack() ||
				tcpHeader.getAckNumber() == session.getSendNext()){
			session.setAcked(true);

			if(tcpHeader.getWindowSize() > 0){
				session.setSendWindowSizeAndScale(tcpHeader.getWindowSize(), session.getSendWindowScale());
			}
			session.setSendUnack(tcpHeader.getAckNumber());
			session.setRecSequence(tcpHeader.getSequenceNumber());
			session.setTimestampReplyto(tcpHeader.getTimeStampSender());
			session.setTimestampSender((int) System.currentTimeMillis());
		} else {
			Log.d(TAG,"Not Accepting ack# "+tcpHeader.getAckNumber() +" , it should be: "+session.getSendNext());
			Log.d(TAG,"Prev sendUnack: "+session.getSendUnack());
			session.setAcked(false);
		}
	}
	/**
	 * set connection as aborting so that background worker will close it.
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void resetConnection(IPv4Header ip, TCPHeader tcp){
		Session session = SessionManager.INSTANCE.getSession(ip.getDestinationIP(), tcp.getDestinationPort(),
				ip.getSourceIP(), tcp.getSourcePort());
		if(session != null){
			session.setAbortingConnection(true);
		}
	}

	/**
	 * create a new client's session and SYN-ACK packet data to respond to client
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void replySynAck(IPv4Header ip, TCPHeader tcp){
		ip.setIdentification(0);
		Packet packet = TCPPacketFactory.createSynAckPacketData(ip, tcp);
		
		TCPHeader tcpheader = (TCPHeader) packet.getTransportHeader();
		
		Session session = SessionManager.INSTANCE.createNewSession(ip.getDestinationIP(),
				tcp.getDestinationPort(), ip.getSourceIP(), tcp.getSourcePort());
		if(session == null)
			return;
		
		int windowScaleFactor = (int) Math.pow(2, tcpheader.getWindowScale());
		session.setSendWindowSizeAndScale(tcpheader.getWindowSize(), windowScaleFactor);
		Log.d(TAG,"send-window size: " + session.getSendWindow());
		session.setMaxSegmentSize(tcpheader.getMaxSegmentSize());
		session.setSendUnack(tcpheader.getSequenceNumber());
		session.setSendNext(tcpheader.getSequenceNumber() + 1);
		//client initial sequence has been incremented by 1 and set to ack
		session.setRecSequence(tcpheader.getAckNumber());

		try {
			writer.write(packet.getBuffer());
			packetData.addData(packet.getBuffer());
			Log.d(TAG,"Send SYN-ACK to client");
		} catch (IOException e) {
			Log.e(TAG,"Error sending data to client: "+e.getMessage());
		}
	}
}//end class
