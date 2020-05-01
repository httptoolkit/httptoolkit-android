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

package tech.httptoolkit.android.vpn.transport.tcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;

import tech.httptoolkit.android.vpn.Packet;
import tech.httptoolkit.android.vpn.network.ip.IPPacketFactory;
import tech.httptoolkit.android.vpn.network.ip.IPv4Header;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;
import java.util.Random;

import tech.httptoolkit.android.TagKt;

import static tech.httptoolkit.android.TagKt.formatTag;

/**
 * class to create IPv4 Header, TCP header, and packet data.
 * @author Borey Sao
 * Date: May 8, 2014
 */
public class TCPPacketFactory {
	private final String TAG = TagKt.getTAG(this);
	
	private static TCPHeader copyTCPHeader(TCPHeader tcpheader){
		final TCPHeader tcp = new TCPHeader(tcpheader.getSourcePort(),
				tcpheader.getDestinationPort(), tcpheader.getSequenceNumber(),
				tcpheader.getAckNumber(), tcpheader.getDataOffset(), tcpheader.isNS(),
				tcpheader.getTcpFlags(), tcpheader.getWindowSize(),
				tcpheader.getChecksum(), tcpheader.getUrgentPointer());

		tcp.setMaxSegmentSize(65535);//tcpheader.getMaxSegmentSize());
		tcp.setWindowScale(tcpheader.getWindowScale());
		tcp.setSelectiveAckPermitted(tcpheader.isSelectiveAckPermitted());
		tcp.setTimeStampSender(tcpheader.getTimeStampSender());
		tcp.setTimeStampReplyTo(tcpheader.getTimeStampReplyTo());
		return tcp;
	}

	/**
	 * create FIN-ACK for sending to client
	 * @param iPv4Header IP Header
	 * @param tcpHeader TCP Header
	 * @param ackToClient acknowledge
	 * @param seqToClient sequence
	 * @return byte[]
	 */
	public static byte[] createFinAckData(IPv4Header iPv4Header, TCPHeader tcpHeader,
								   long ackToClient, long seqToClient,
								   boolean isFin, boolean isAck){
		IPv4Header ip = IPPacketFactory.copyIPv4Header(iPv4Header);
		TCPHeader tcp = copyTCPHeader(tcpHeader);
		
		//flip IP from source to dest and vice-versa
		int sourceIp = ip.getDestinationIP();
		int destIp = ip.getSourceIP();
		int sourcePort = tcp.getDestinationPort();
		int destPort = tcp.getSourcePort();
		
		ip.setDestinationIP(destIp);
		ip.setSourceIP(sourceIp);
		tcp.setDestinationPort(destPort);
		tcp.setSourcePort(sourcePort);
		
		tcp.setAckNumber(ackToClient);
		tcp.setSequenceNumber(seqToClient);
		
		ip.setIdentification(PacketUtil.getPacketId());
		
		//ACK
		tcp.setIsACK(isAck);
		tcp.setIsSYN(false);
		tcp.setIsPSH(false);
		tcp.setIsFIN(isFin);
		
		//set response timestamps in options fields
		tcp.setTimeStampReplyTo(tcp.getTimeStampSender());
		Date currentDate = new Date();
		int senderTimestamp = (int)currentDate.getTime();
		tcp.setTimeStampSender(senderTimestamp);
		
		//recalculate IP length
		int totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength();
		
		ip.setTotalLength(totalLength);
		
		return createPacketData(ip, tcp, null);
	}

	public static byte[] createFinData(IPv4Header ip, TCPHeader tcp, long ackNumber, long seqNumber, int timeSender, int timeReplyTo){
		//flip IP from source to dest and vice-versa
		int sourceIp = ip.getDestinationIP();
		int destIp = ip.getSourceIP();
		int sourcePort = tcp.getDestinationPort();
		int destPort = tcp.getSourcePort();
		
		tcp.setAckNumber(ackNumber);
		tcp.setSequenceNumber(seqNumber);
		
		tcp.setTimeStampReplyTo(timeReplyTo);
		tcp.setTimeStampSender(timeSender);
		
		ip.setDestinationIP(destIp);
		ip.setSourceIP(sourceIp);
		tcp.setDestinationPort(destPort);
		tcp.setSourcePort(sourcePort);
		
		ip.setIdentification(PacketUtil.getPacketId());
		
		tcp.setIsRST(false);
		tcp.setIsACK(true);
		tcp.setIsSYN(false);
		tcp.setIsPSH(false);
		tcp.setIsCWR(false);
		tcp.setIsECE(false);
		tcp.setIsFIN(true);
		tcp.setIsNS(false);
		tcp.setIsURG(false);
		
		//remove any option field
		tcp.setOptions(null);

		//window size should be zero
		tcp.setWindowSize(0);
		
		//recalculate IP length
		int totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength();
		
		ip.setTotalLength(totalLength);
		
		return createPacketData(ip, tcp, null);
	}

	/**
	 * create packet with RST flag for sending to client when reset is required.
	 * @param ipheader IP Header
	 * @param tcpheader TCP Header
	 * @param datalength Data Length
	 * @return byte[]
	 */
	public static byte[] createRstData(IPv4Header ipheader, TCPHeader tcpheader, int datalength){
		IPv4Header ip = IPPacketFactory.copyIPv4Header(ipheader);
		TCPHeader tcp = copyTCPHeader(tcpheader);
		
		//flip IP from source to dest and vice-versa
		int sourceIp = ip.getDestinationIP();
		int destIp = ip.getSourceIP();
		int sourcePort = tcp.getDestinationPort();
		int destPort = tcp.getSourcePort();
		
		long ackNumber = 0;
		long seqNumber = 0;
		
		if(tcp.getAckNumber() > 0){
			seqNumber = tcp.getAckNumber();
		}else{
			ackNumber = tcp.getSequenceNumber() + datalength;
		}
		tcp.setAckNumber(ackNumber);
		tcp.setSequenceNumber(seqNumber);
		
		ip.setDestinationIP(destIp);
		ip.setSourceIP(sourceIp);
		tcp.setDestinationPort(destPort);
		tcp.setSourcePort(sourcePort);
		
		ip.setIdentification(0);
		
		tcp.setIsRST(true);
		tcp.setIsACK(false);
		tcp.setIsSYN(false);
		tcp.setIsPSH(false);
		tcp.setIsCWR(false);
		tcp.setIsECE(false);
		tcp.setIsFIN(false);
		tcp.setIsNS(false);
		tcp.setIsURG(false);
		
		//remove any option field
		tcp.setOptions(null);

		//window size should be zero
		tcp.setWindowSize(0);
		
		//recalculate IP length
		int totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength();
		
		ip.setTotalLength(totalLength);
		
		return createPacketData(ip, tcp, null);
	}

	/**
	 * Acknowledgment to client that server has received request.
	 * @param ipHeader IP Header
	 * @param tcpheader TCP Header
	 * @param ackToClient Acknowledge
	 * @return byte[]
	 */
	public static byte[] createResponseAckData(IPv4Header ipHeader, TCPHeader tcpheader, long ackToClient){
		IPv4Header ip = IPPacketFactory.copyIPv4Header(ipHeader);
		TCPHeader tcp = copyTCPHeader(tcpheader);
		
		//flip IP from source to dest and vice-versa
		int sourceIp = ip.getDestinationIP();
		int destIp = ip.getSourceIP();
		int sourcePort = tcp.getDestinationPort();
		int destPort = tcp.getSourcePort();
		
		long seqNumber = tcp.getAckNumber();
		
		ip.setDestinationIP(destIp);
		ip.setSourceIP(sourceIp);
		tcp.setDestinationPort(destPort);
		tcp.setSourcePort(sourcePort);
		
		tcp.setAckNumber(ackToClient);
		tcp.setSequenceNumber(seqNumber);
		
		ip.setIdentification(PacketUtil.getPacketId());
		
		//ACK
		tcp.setIsACK(true);
		tcp.setIsSYN(false);
		tcp.setIsPSH(false);
		tcp.setIsFIN(false);
		
		//set response timestamps in options fields
		tcp.setTimeStampReplyTo(tcp.getTimeStampSender());
		Date currentdate = new Date();
		int sendertimestamp = (int)currentdate.getTime();
		tcp.setTimeStampSender(sendertimestamp);
		
		//recalculate IP length
		int totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength();
		
		ip.setTotalLength(totalLength);
		
		return createPacketData(ip, tcp, null);
	}

	/**
	 * create packet data for sending back to client
	 * @param ip IP Header
	 * @param tcp TCP Header
	 * @param packetData Packet Data
	 * @return byte[]
	 */
	public static byte[] createResponsePacketData(IPv4Header ip, TCPHeader tcp, byte[] packetData, boolean isPsh,
			long ackNumber, long seqNumber, int timeSender, int timeReplyto){
		IPv4Header ipHeader = IPPacketFactory.copyIPv4Header(ip);
		TCPHeader tcpHeader = copyTCPHeader(tcp);
		
		//flip IP from source to dest and vice-versa
		int sourceIp = ipHeader.getDestinationIP();
		int sourcePort = tcpHeader.getDestinationPort();
		ipHeader.setDestinationIP(ipHeader.getSourceIP());
		ipHeader.setSourceIP(sourceIp);
		tcpHeader.setDestinationPort(tcpHeader.getSourcePort());
		tcpHeader.setSourcePort(sourcePort);
		
		tcpHeader.setAckNumber(ackNumber);
		tcpHeader.setSequenceNumber(seqNumber);
		
		ipHeader.setIdentification(PacketUtil.getPacketId());
		
		//ACK is always sent
		tcpHeader.setIsACK(true);
		tcpHeader.setIsSYN(false);
		tcpHeader.setIsPSH(isPsh);
		tcpHeader.setIsFIN(false);
		
		tcpHeader.setTimeStampSender(timeSender);
		tcpHeader.setTimeStampReplyTo(timeReplyto);

		//recalculate IP length
		int totalLength = ipHeader.getIPHeaderLength() + tcpHeader.getTCPHeaderLength();
		if(packetData != null){
			totalLength += packetData.length;
		}
		ipHeader.setTotalLength(totalLength);
		
		return createPacketData(ipHeader, tcpHeader, packetData);
	}

	/**
	 * create SYN-ACK packet data from writing back to client stream
	 * @param ip IP Header
	 * @param tcp TCP Header
	 * @return class Packet
	 */
	public static Packet createSynAckPacketData(IPv4Header ip, TCPHeader tcp){
		IPv4Header ipheader = IPPacketFactory.copyIPv4Header(ip);
		TCPHeader tcpheader = copyTCPHeader(tcp);
		
		//flip IP from source to dest and vice-versa
		int sourceIp = ipheader.getDestinationIP();
		int destIp = ipheader.getSourceIP();
		int sourcePort = tcpheader.getDestinationPort();
		int destPort = tcpheader.getSourcePort();
		long ackNumber = tcpheader.getSequenceNumber() + 1;
		long seqNumber;
		Random random = new Random();
		seqNumber = random.nextInt();
		if(seqNumber < 0){
			seqNumber = seqNumber * -1;
		}
		ipheader.setDestinationIP(destIp);
		ipheader.setSourceIP(sourceIp);
		tcpheader.setDestinationPort(destPort);
		tcpheader.setSourcePort(sourcePort);
		
		//ack = received sequence + 1
		tcpheader.setAckNumber(ackNumber);
		
		//initial sequence number generated by server
		tcpheader.setSequenceNumber(seqNumber);
		Log.d(formatTag(TCPPacketFactory.class.getName()),"Set Initial Sequence number: "+seqNumber);
		
		//SYN-ACK
		tcpheader.setIsACK(true);
		tcpheader.setIsSYN(true);
		
		//timestamp in options fields
		tcpheader.setTimeStampReplyTo(tcpheader.getTimeStampSender());
		Date currentdate = new Date();
		int sendertimestamp = (int)currentdate.getTime();
		tcpheader.setTimeStampSender(sendertimestamp);
		
		return new Packet(ipheader, tcpheader, createPacketData(ipheader, tcpheader, null));
	}

	/**
	 * create packet data from IP Header, TCP header and data
	 * @param ipHeader IPv4Header object
	 * @param tcpheader TCPHeader object
	 * @param data array of byte (packet body)
	 * @return array of byte
	 */
    private static byte[] createPacketData(IPv4Header ipHeader, TCPHeader tcpheader, @Nullable byte[] data){
		int dataLength = 0;
		if(data != null){
			dataLength = data.length;
		}
		byte[] buffer = new byte[ipHeader.getIPHeaderLength() + tcpheader.getTCPHeaderLength() + dataLength];
		byte[] ipBuffer = IPPacketFactory.createIPv4HeaderData(ipHeader);
		byte[] tcpBuffer = createTCPHeaderData(tcpheader);
		
		System.arraycopy(ipBuffer, 0, buffer, 0, ipBuffer.length);
		System.arraycopy(tcpBuffer, 0, buffer, ipBuffer.length, tcpBuffer.length);
		if(dataLength > 0){
			int offset = ipBuffer.length + tcpBuffer.length;
			System.arraycopy(data, 0, buffer, offset, dataLength);
		}
		//calculate checksum for both IP and TCP header
		byte[] zero = {0, 0};
		//zero out checksum first before calculation
		System.arraycopy(zero, 0, buffer, 10, 2);
		byte[] ipChecksum = PacketUtil.calculateChecksum(buffer, 0, ipBuffer.length);
		//write result of checksum back to buffer
		System.arraycopy(ipChecksum, 0, buffer, 10, 2);
		
		//zero out TCP header checksum first
		int tcpStart = ipBuffer.length;
		System.arraycopy(zero, 0, buffer, tcpStart + 16, 2);
		byte[] tcpChecksum = PacketUtil.calculateTCPHeaderChecksum(buffer, tcpStart, tcpBuffer.length + dataLength ,
				ipHeader.getDestinationIP(), ipHeader.getSourceIP());
		
		//write new checksum back to array
		System.arraycopy(tcpChecksum, 0, buffer,tcpStart + 16, 2);

		return buffer;
	}
	
	/**
	 * create array of byte from a given TCPHeader object
	 * @param header instance of TCPHeader
	 * @return array of byte
	 */
	private static byte[] createTCPHeaderData(TCPHeader header){
		final byte[] buffer = new byte[header.getTCPHeaderLength()];
		buffer[0] = (byte)(header.getSourcePort() >> 8);
		buffer[1] = (byte)(header.getSourcePort());
		buffer[2] = (byte)(header.getDestinationPort() >> 8);
		buffer[3] = (byte)(header.getDestinationPort());

		final ByteBuffer sequenceNumber = ByteBuffer.allocate(4);
		sequenceNumber.order(ByteOrder.BIG_ENDIAN);
		sequenceNumber.putInt((int)header.getSequenceNumber());
		
		//sequence number
		System.arraycopy(sequenceNumber.array(), 0, buffer, 4, 4);

		final ByteBuffer ackNumber = ByteBuffer.allocate(4);
		ackNumber.order(ByteOrder.BIG_ENDIAN);
		ackNumber.putInt((int)header.getAckNumber());
		System.arraycopy(ackNumber.array(), 0, buffer, 8, 4);
		
		buffer[12] = (byte) (header.isNS() ? (header.getDataOffset() << 4) | 0x1
				: header.getDataOffset() << 4);
		buffer[13] = (byte)header.getTcpFlags();

		buffer[14] = (byte)(header.getWindowSize() >> 8);
		buffer[15] = (byte)header.getWindowSize();

		buffer[16] = (byte)(header.getChecksum() >> 8);
		buffer[17] = (byte)header.getChecksum();

		buffer[18] = (byte)(header.getUrgentPointer() >> 8);
		buffer[19] = (byte)header.getUrgentPointer();

		//set timestamp for both sender and reply to
		final byte[] options = header.getOptions();
		if (options != null) {
			for (int i = 0; i < options.length; i++) {
				final byte kind = options[i];
				if (kind > 1) {
					if (kind == 8) {//timestamp
						i += 2;
						if ((i + 7) < options.length) {
							PacketUtil.writeIntToBytes(header.getTimeStampSender(), options, i);
							i += 4;
							PacketUtil.writeIntToBytes(header.getTimeStampReplyTo(), options, i);
						}
						break;
					} else if ((i + 1) < options.length) {
						final byte len = options[i + 1];
						i = i + len - 1;
					}
				}
			}
			if (options.length > 0) {
				System.arraycopy(options, 0, buffer, 20, options.length);
			}
		}

		return buffer;
	}
	/**
	 * create a TCP Header from a given byte array
	 * @param stream array of byte
	 * @return a new instance of TCPHeader
	 * @throws PacketHeaderException throws PacketHeaderException
	 */
	public static TCPHeader createTCPHeader(@NonNull ByteBuffer stream) throws PacketHeaderException {
		if(stream.remaining() < 20) {
			throw new PacketHeaderException("There is not enough space for TCP header from provided starting position");
		}

		final int sourcePort = stream.getShort() & 0xFFFF;
		final int destPort = stream.getShort() & 0xFFFF;
		final long sequenceNumber = stream.getInt();
		final long ackNumber = stream.getInt();
		final int dataOffsetAndNs = stream.get();

		final int dataOffset = (dataOffsetAndNs & 0xF0) >> 4;
		if(stream.remaining() < (dataOffset - 5) * 4) {
			throw new PacketHeaderException("invalid array size for TCP header from given starting position");
		}
		
		final boolean isNs = (dataOffsetAndNs & 0x1) > 0x0;
		final int tcpFlag = stream.get();
		final int windowSize = stream.getShort();
		final int checksum = stream.getShort();
		final int urgentPointer = stream.getShort();

		final TCPHeader header = new TCPHeader(sourcePort, destPort, sequenceNumber, ackNumber, dataOffset, isNs, tcpFlag, windowSize, checksum, urgentPointer);
		if (dataOffset > 5) {
			handleTcpOptions(header, stream, dataOffset * 4 - 20);
		}

		return header;
	}

	private static final int END_OF_OPTIONS_LIST = 0;
	private static final int NO_OPERATION = 1;
	private static final int MAX_SEGMENT_SIZE = 2;
	private static final int WINDOW_SCALE = 3;
	private static final int SELECTIVE_ACK_PERMITTED = 4;
//	private static final int SELECTIVE_ACK = 5;
	private static final int TIME_STAMP = 8;

	private static void handleTcpOptions(@NonNull TCPHeader header, @NonNull ByteBuffer packet, int optionsSize) {
		int index = 0;

		while (index < optionsSize) {
			final byte optionKind = packet.get();
			index++;

			if (optionKind == END_OF_OPTIONS_LIST || optionKind == NO_OPERATION) {
				continue;
			}

			final byte size = packet.get();
			index++;

			switch (optionKind) {
				case MAX_SEGMENT_SIZE:
					header.setMaxSegmentSize(packet.getShort());
					index += 2;
					break;
				case WINDOW_SCALE:
					header.setWindowScale(packet.get());
					index++;
					break;
				case SELECTIVE_ACK_PERMITTED:
					header.setSelectiveAckPermitted(true);
					break;
				case TIME_STAMP:
					header.setTimeStampSender(packet.getInt());
					header.setTimeStampReplyTo(packet.getInt());
					index += 8;
					break;
				default:
					skipRemainingOptions(packet, size);
					index = index + size - 2;
					break;
			}
		}
	}

	private static void skipRemainingOptions(@NonNull ByteBuffer packet, int size) {
		for (int i = 2; i < size; i++) {
			packet.get();
		}
	}
}
