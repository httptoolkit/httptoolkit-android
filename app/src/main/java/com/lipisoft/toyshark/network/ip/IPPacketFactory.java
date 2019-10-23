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

package com.lipisoft.toyshark.network.ip;

import androidx.annotation.NonNull;

import com.lipisoft.toyshark.transport.tcp.PacketHeaderException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * class for creating packet data, header etc related to IP
 * @author Borey Sao
 * Date: June 30, 2014
 */
public class IPPacketFactory {
	/**
	 * make new instance of IPv4Header
	 * @param iPv4Header instance of IPv4Header
	 * @return IPv4Header
	 */
	@NonNull
	public static IPv4Header copyIPv4Header(@NonNull IPv4Header iPv4Header) {
		return new IPv4Header(iPv4Header.getIpVersion(),
				iPv4Header.getInternetHeaderLength(),
				iPv4Header.getDscpOrTypeOfService(), iPv4Header.getEcn(),
				iPv4Header.getTotalLength(), iPv4Header.getIdentification(),
				iPv4Header.isMayFragment(), iPv4Header.isLastFragment(),
				iPv4Header.getFragmentOffset(), iPv4Header.getTimeToLive(),
				iPv4Header.getProtocol(), iPv4Header.getHeaderChecksum(),
				iPv4Header.getSourceIP(), iPv4Header.getDestinationIP());
	}

	/**
	 * create IPv4 Header array of byte from a given IPv4Header object
	 * @param header instance of IPv4Header
	 * @return array of byte
	 */
	public static byte[] createIPv4HeaderData(@NonNull IPv4Header header){
		final byte[] buffer = new byte[header.getIPHeaderLength()];

		buffer[0] = (byte)((header.getInternetHeaderLength() & 0xF) | 0x40);
		buffer[1] = (byte) ((header.getDscpOrTypeOfService() << 2) & (header.getEcn() & 0xFF));
		buffer[2] = (byte)(header.getTotalLength() >> 8);
		buffer[3] = (byte)header.getTotalLength();
		buffer[4] = (byte)(header.getIdentification() >> 8);
		buffer[5] = (byte)header.getIdentification();

		//combine flags and partial fragment offset
		buffer[6] = (byte)(((header.getFragmentOffset() >> 8) & 0x1F) | header.getFlag());
		buffer[7] = (byte)header.getFragmentOffset();
		buffer[8] = header.getTimeToLive();
		buffer[9]= header.getProtocol();
		buffer[10] = (byte) (header.getHeaderChecksum() >> 8);
		buffer[11] = (byte)header.getHeaderChecksum();

		final ByteBuffer buf = ByteBuffer.allocate(8);

		buf.order(ByteOrder.BIG_ENDIAN);
		buf.putInt(0,header.getSourceIP());
		buf.putInt(4,header.getDestinationIP());
		
		//source ip address
		System.arraycopy(buf.array(), 0, buffer, 12, 4);
		//destination ip address
		System.arraycopy(buf.array(), 4, buffer, 16, 4);

		return buffer;
	}

	/**
	 * create IPv4 Header from a given ByteBuffer stream
	 * @param stream array of byte
	 * @return a new instance of IPv4Header
	 */
	public static IPv4Header createIPv4Header(@NonNull ByteBuffer stream) throws PacketHeaderException{
		//avoid Index out of range
		if (stream.remaining() < 20) {
			throw new PacketHeaderException("Minimum IPv4 header is 20 bytes. There are less "
					+ "than 20 bytes from start position to the end of array.");
		}

		final byte versionAndHeaderLength = stream.get();
		final byte ipVersion = (byte) (versionAndHeaderLength >> 4);
		if (ipVersion != 0x04) {
			throw new PacketHeaderException("Invalid IPv4 header. IP version should be 4.");
		}

		final byte internetHeaderLength = (byte) (versionAndHeaderLength & 0x0F);
		if(stream.capacity() < internetHeaderLength * 4) {
			throw new PacketHeaderException("Not enough space in array for IP header");
		}

		final byte dscpAndEcn = stream.get();
		final byte dscp = (byte) (dscpAndEcn >> 2);
		final byte ecn = (byte) (dscpAndEcn & 0x03);
		final int totalLength = stream.getShort();
		final int identification = stream.getShort();
		final short flagsAndFragmentOffset = stream.getShort();
		final boolean mayFragment = (flagsAndFragmentOffset & 0x4000) != 0;
		final boolean lastFragment = (flagsAndFragmentOffset & 0x2000) != 0;
		final short fragmentOffset = (short) (flagsAndFragmentOffset & 0x1FFF);
		final byte timeToLive = stream.get();
		final byte protocol = stream.get();
		final int checksum = stream.getShort();
		final int sourceIp = stream.getInt();
		final int desIp = stream.getInt();
		if (internetHeaderLength > 5) {
			// drop the IP option
			for (int i = 0; i < internetHeaderLength - 5; i++) {
				stream.getInt();
			}
		}
		return new IPv4Header(ipVersion, internetHeaderLength, dscp, ecn, totalLength, identification,
				mayFragment, lastFragment, fragmentOffset, timeToLive, protocol, checksum, sourceIp, 
				desIp);
	}
}
