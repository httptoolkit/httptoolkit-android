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

package com.lipisoft.toyshark.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;

import androidx.annotation.NonNull;
import android.util.Log;

import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.udp.UDPHeader;


/**
 * Helper class to perform various useful task
 * @author Borey Sao
 * Date: May 8, 2014
 */
public class PacketUtil {
	private static final String TAG = "PacketUtil";
	private volatile static int packetId = 0;

	public synchronized static int getPacketId(){
		return packetId++;
	}

	/**
	 * convert int to byte array
	 * https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
	 * @param value int value 32 bits
	 * @param buffer array of byte to write to
	 * @param offset position to write to
	 */
	public static void writeIntToBytes(int value, byte[] buffer, int offset){
		if(buffer.length - offset < 4){
			return;
		}
		buffer[offset] = (byte)((value >>> 24) & 0x000000FF);
		buffer[offset + 1] = (byte)((value >> 16)&0x000000FF);
		buffer[offset + 2] = (byte)((value >> 8)&0x000000FF);
		buffer[offset + 3] = (byte)(value & 0x000000FF);
	}

	/**
	 * convert short to byte array
	 * @param value short value to convert
	 * @param buffer array of byte to put value to
	 * @param offset starting position in array
	 */
	public static void writeShortToBytes(short value, byte[] buffer, int offset){
		if(buffer.length - offset < 2){
			return;
		}
		buffer[offset] = (byte)((value >>> 8)& 0x00FF);
		buffer[offset + 1] = (byte)(value & 0x00FF);
	}

	/**
	 * extract short value from a byte array using Big Endian byte order
	 * @param buffer array of byte
	 * @param start position to start extracting value
	 * @return value of short
	 */
	public static short getNetworkShort(byte[] buffer, int start){
		short value = 0x0000;
		value |= buffer[start] & 0xFF;
		value <<= 8;
		value |= buffer[start+1] & 0xFF;
		return value;
	}

	/**
	 * convert array of max 4 bytes to int
	 * @param buffer byte array
	 * @param start Starting point to be read in byte array
	 * @param length Length to be read
	 * @return value of int
	 */
	public static int getNetworkInt(@NonNull byte[] buffer, int start, int length){
		int value = 0;
		int end = start + (length > 4 ? 4 : length);

		if(end > buffer.length)
			end = buffer.length;

		for(int i = start; i < end; i++) {
			value |= buffer[i] & 0xFF;
			if(i < (end - 1))
				value <<= 8;
		}

		return value;
	}

	/**
	 * convert array of max 4 bytes to long
	 * @param buffer byte array
	 * @param start Starting point to be read in byte array
	 * @param length Length to be read
	 * @return value of long
	 */
	public static long getNetworkLong(@NonNull byte[] buffer, int start, int length){
		long value = 0;
		int end = start + (length > 4 ? 4 : length);

		if(end > buffer.length)
			end = buffer.length;

		for(int i = start; i < end; i++) {
			value |= buffer[i] & 0xFF;
			if(i < (end - 1))
				value <<= 8;
		}

		return value;
	}

	/**
	 * validate TCP header checksum
	 * @param source Source Port
	 * @param destination Destination Port
	 * @param data Payload
	 * @param tcpLength TCP Header length
	 * @param tcpOffset
	 * @return boolean
	 */
	public static boolean isValidTCPChecksum(int source, int destination,
											 byte[] data, short tcpLength, int tcpOffset){
		int buffersize = tcpLength + 12;
		boolean isodd = false;
		if((buffersize % 2) != 0){
			buffersize++;
			isodd = true;
		}

		ByteBuffer buffer = ByteBuffer.allocate(buffersize);
		buffer.putInt(source);
		buffer.putInt(destination);
		buffer.put((byte)0);//reserved => 0
		buffer.put((byte)6);//TCP protocol => 6
		buffer.putShort(tcpLength);
		buffer.put(data, tcpOffset, tcpLength);
		if(isodd){
			buffer.put((byte)0);
		}
		return isValidIPChecksum(buffer.array(), buffersize);
	}

	/**
	 * validate IP Header checksum
	 * @param data byte stream
	 * @param length
	 * @return boolean
	 */
	private static boolean isValidIPChecksum(byte[] data, int length){
		int start = 0;
		int sum = 0;
		while(start < length) {
			sum += getNetworkInt(data, start, 2);
			start = start + 2;
		}

		//carry over one's complement
		while((sum >> 16) > 0)
			sum = (sum & 0xffff) + (sum >> 16);

		//flip the bit to get one' complement
		sum = ~sum;
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(sum);

		return buffer.getShort(2) == 0;
	}

	public static byte[] calculateChecksum(byte[] data, int offset, int length){
		int start = offset;
		int sum = 0;
		while(start < length){
			sum += PacketUtil.getNetworkInt(data, start, 2);
			start = start + 2;
		}
		//carry over one's complement
		while((sum >> 16) > 0){
			sum = (sum & 0xffff) + (sum >> 16);
		}
		//flip the bit to get one' complement
		sum = ~sum;

		//extract the last two byte of int
		byte[] checksum = new byte[2];
		checksum[0] = (byte)(sum >> 8);
		checksum[1] = (byte)sum;

		return checksum;
	}

	public static byte[] calculateTCPHeaderChecksum(byte[] data, int offset, int tcplength, int destip, int sourceip){
		int buffersize = tcplength + 12;
		boolean odd = false;
		if(buffersize % 2 != 0){
			buffersize++;
			odd = true;
		}
		ByteBuffer buffer = ByteBuffer.allocate(buffersize);
		buffer.order(ByteOrder.BIG_ENDIAN);

		//create virtual header
		buffer.putInt(sourceip);
		buffer.putInt(destip);
		buffer.put((byte)0);//reserved => 0
		buffer.put((byte)6);//tcp protocol => 6
		buffer.putShort((short)tcplength);

		//add actual header + data
		buffer.put(data, offset, tcplength);

		//padding last byte to zero
		if(odd){
			buffer.put((byte)0);
		}
		byte[] tcparray = buffer.array();
		return calculateChecksum(tcparray, 0, buffersize);
	}

	public static String intToIPAddress(int addressInt)
	{
		return String.valueOf((addressInt >>> 24) & 0x000000FF) + "." +
				String.valueOf((addressInt >>> 16) & 0x000000FF) + "." +
				String.valueOf((addressInt >>> 8) & 0x000000FF) + "." +
				String.valueOf(addressInt & 0x000000FF);
	}

	/**
	 * get IP address of device
	 * @return IP Address
	 */
	public static String getLocalIpAddress() {
		try {
			Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
			while (en.hasMoreElements()) {
				NetworkInterface networkInterface = en.nextElement();
				if (!networkInterface.getDisplayName().equals("tun0")) {
					Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
					while (addresses.hasMoreElements()) {
						InetAddress inetAddress = addresses.nextElement();
						if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address)
							return inetAddress.getHostAddress();
					}
				}
			}
		} catch (SocketException ex) {
			ex.printStackTrace();
		}

		return null;
	}

	public static String getUDPoutput(IPv4Header ipheader, UDPHeader udp){
		return "\r\nIP Version: " + ipheader.getIpVersion() +
				"\r\nProtocol: " + ipheader.getProtocol() +
				"\r\nID# " + ipheader.getIdentification() +
				"\r\nIP Total Length: " + ipheader.getTotalLength() +
				"\r\nIP Header length: " + ipheader.getIPHeaderLength() +
				"\r\nIP checksum: " + ipheader.getHeaderChecksum() +
				"\r\nMay fragement? " + ipheader.isMayFragment() +
				"\r\nLast fragment? " + ipheader.isLastFragment() +
				"\r\nFlag: " + ipheader.getFlag() +
				"\r\nFragment Offset: " + ipheader.getFragmentOffset() +
				"\r\nDest: " + intToIPAddress(ipheader.getDestinationIP()) +
						":" + udp.getDestinationPort() +
				"\r\nSrc: " + intToIPAddress(ipheader.getSourceIP()) +
						":" + udp.getSourcePort() +
				"\r\nUDP Length: " + udp.getLength() +
				"\r\nUDP Checksum: " + udp.getChecksum();
	}

	public static String getOutput(IPv4Header ipHeader, TCPHeader tcpheader,
								   byte[] packetData) {
		short tcpLength = (short)(packetData.length -
				ipHeader.getIPHeaderLength());
		boolean isValidChecksum = PacketUtil.isValidTCPChecksum(
				ipHeader.getSourceIP(), ipHeader.getDestinationIP(),
				packetData, tcpLength, ipHeader.getIPHeaderLength());
		boolean isValidIPChecksum = PacketUtil.isValidIPChecksum(packetData,
				ipHeader.getIPHeaderLength());
		int packetBodyLength = packetData.length - ipHeader.getIPHeaderLength()
				- tcpheader.getTCPHeaderLength();

		StringBuilder str = new StringBuilder("\r\nIP Version: ")
		.append(ipHeader.getIpVersion())
		.append("\r\nProtocol: ").append(ipHeader.getProtocol())
		.append("\r\nID# ").append(ipHeader.getIdentification())
		.append("\r\nTotal Length: ").append(ipHeader.getTotalLength())
		.append("\r\nData Length: ").append(packetBodyLength)
		.append("\r\nDest: ").append(intToIPAddress(ipHeader.getDestinationIP()))
				.append(":").append(tcpheader.getDestinationPort())
		.append("\r\nSrc: ").append(intToIPAddress(ipHeader.getSourceIP()))
				.append(":").append(tcpheader.getSourcePort())
		.append("\r\nACK: ").append(tcpheader.getAckNumber())
		.append("\r\nSeq: ").append(tcpheader.getSequenceNumber())
		.append("\r\nIP Header length: ").append(ipHeader.getIPHeaderLength())
		.append("\r\nTCP Header length: ").append(tcpheader.getTCPHeaderLength())
		.append("\r\nACK: ").append(tcpheader.isACK())
		.append("\r\nSYN: ").append(tcpheader.isSYN())
		.append("\r\nCWR: ").append(tcpheader.isCWR())
		.append("\r\nECE: ").append(tcpheader.isECE())
		.append("\r\nFIN: ").append(tcpheader.isFIN())
		.append("\r\nNS: ").append(tcpheader.isNS())
		.append("\r\nPSH: ").append(tcpheader.isPSH())
		.append("\r\nRST: ").append(tcpheader.isRST())
		.append("\r\nURG: ").append(tcpheader.isURG())
		.append("\r\nIP checksum: ").append(ipHeader.getHeaderChecksum())
		.append("\r\nIs Valid IP Checksum: ").append(isValidIPChecksum)
		.append("\r\nTCP Checksum: ").append(tcpheader.getChecksum())
		.append("\r\nIs Valid TCP checksum: ").append(isValidChecksum)
		.append("\r\nMay fragement? ").append(ipHeader.isMayFragment())
		.append("\r\nLast fragment? ").append(ipHeader.isLastFragment())
		.append("\r\nFlag: ").append(ipHeader.getFlag())
		.append("\r\nFragment Offset: ").append(ipHeader.getFragmentOffset())
		.append("\r\nWindow: ").append(tcpheader.getWindowSize())
		.append("\r\nWindow scale: ").append(tcpheader.getWindowScale())
		.append("\r\nData Offset: ").append(tcpheader.getDataOffset());

		final byte[] options =  tcpheader.getOptions();
		if (options != null){
			str.append("\r\nTCP Options: \r\n..........");
			for (int i = 0; i < options.length ; i++) {
				final byte kind = options[i];
				if(kind == 0){
					str.append("\r\n...End of options list");
				}else if(kind == 1){
					str.append("\r\n...NOP");
				}else if(kind == 2){
					i += 2;
					int maxSegmentSize = PacketUtil.getNetworkInt(options, i, 2);
					i++;
					str.append("\r\n...Max Seg Size: ").append(maxSegmentSize);
				}else if(kind == 3){
					i += 2;
					int windowSize = PacketUtil.getNetworkInt(options, i, 1);
					str.append("\r\n...Window Scale: ").append(windowSize);
				}else if(kind == 4){
					i++;
					str.append("\r\n...Selective Ack");
				}else if(kind == 5){
					i = i + options[++i] - 2;
					str.append("\r\n...selective ACK (SACK)");
				}else if(kind == 8){
					i += 2;
					int timeStampValue = PacketUtil.getNetworkInt(options, i, 4);
					i += 4;
					int timeStampEchoReply = PacketUtil.getNetworkInt(options, i, 4);
					i += 3;
					str.append("\r\n...Timestamp: ").append(timeStampValue)
							.append("-").append(timeStampEchoReply);
				}else if(kind == 14){
					i +=2;
					str.append("\r\n...Alternative Checksum request");
				}else if(kind == 15){
					i = i + options[++i] - 2;
					str.append("\r\n...TCP Alternate Checksum Data");
				}else{
					str.append("\r\n... unknown option# ").append(kind)
							.append(", int: ").append((int)kind);
				}
			}
		}
		return str.toString();
	}

	/**
	 * detect packet corruption flag in tcp options sent from client ACK
	 * @param tcpHeader TCPHeader
	 * @return boolean
	 */
	public static boolean isPacketCorrupted(@NonNull TCPHeader tcpHeader){
		final byte[] options = tcpHeader.getOptions();

		if (options != null) {
			for (int i = 0; i < options.length; i++) {
				final byte kind = options[i];
				if (kind == 0 || kind == 1) {
				} else if (kind == 2) {
					i += 3;
				} else if (kind == 3 || kind == 14) {
					i += 2;
				} else if (kind == 4) {
					i++;
				} else if (kind == 5 || kind == 15) {
					i = i + options[++i] - 2;
				} else if (kind == 8) {
					i += 9;
				} else if (kind == 23) {
					return true;
				} else {
					Log.e(TAG, "unknown option: " + kind);
				}
			}
		}
		return false;
	}

	public static String bytesToStringArray(byte[] bytes) {
		StringBuilder str = new StringBuilder("{ ");

		for (int i = 0; i < bytes.length; i++) {
			if(i == 0)
				str.append(bytes[i]);
			else
				str.append(", ").append(bytes[i]);
		}
		str.append(" }");

		return str.toString();
	}
}
