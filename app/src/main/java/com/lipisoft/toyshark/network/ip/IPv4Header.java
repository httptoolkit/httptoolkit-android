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

import androidx.annotation.Nullable;

/**
 * Data structure for IPv4 header as defined in RFC 791.
 * @author Borey Sao
 * Date: May 8, 2014
 */
public class IPv4Header {
	//IP packet is the four-bit version field. For IPv4, this has a value of 4 (hence the name IPv4).
	private byte ipVersion;
	
	//the size of the header (this also coincides with the offset to the data)
	private byte internetHeaderLength;
	
	//Differentiated Services Code Point (DSCP) => 6 bits
	private byte dscpOrTypeOfService = 0;
	
	//Explicit Congestion Notification (ECN)
	private byte ecn = 0;
	
	//The total length in bytes of this IP packet, including the IP header and data (TCP header/UDP + body)
	private int totalLength = 0;
	
	//primarily used for uniquely identifying the group of fragments of a single IP datagram. 
	private int identification = 0;
	
	//3 bits field used to control or identify fragments.
	//bit 0: Reserved; must be zero
	//bit 1: Don't Fragment (DF)
	//bit 2: More Fragments (MF)
	private byte flag = 0;
	private boolean mayFragment;
	private boolean lastFragment;
	
	// The fragment offset for this IP datagram.
	private short fragmentOffset;
	
	//This field limits a datagram's lifetime
	//It is specified in seconds, but time intervals less than 1 second are rounded up to 1
	private byte timeToLive = 0;
	
	//TCP or UDP or other
	private byte protocol = 0;
	
	//for error-checking of the header
	private int headerChecksum = 0;
	
	private int sourceIP;
	
	private int destinationIP;

	/**
	 * create a new IPv4 Header
	 * @param ipVersion the first header field in an IP packet. It is four-bit. For IPv4, this has a value of 4.
	 * @param internetHeaderLength the second field (four bits) is the IP header length (from 20 to 60 bytes)
	 * @param dscpOrTypeOfService type of service
	 * @param ecn Explicit Congestion Notification
	 * @param totalLength total length of this packet including header and body in bytes (max 35535).
	 * @param identification primarily used for uniquely identifying the group of fragments of a single IP datagram
	 * @param mayFragment bit number 1 of Flag. For DF (Don't Fragment)
	 * @param lastFragment bit number 2 of Flag. For MF (More Fragment) 
	 * @param fragmentOffset 13 bits long and specifies the offset of a particular fragment relative to the beginning of 
	 * the original unfragmented IP datagram.
	 * @param timeToLive 8 bits field for preventing datagrams from persisting.
	 * @param protocol defines the protocol used in the data portion of the IP datagram
	 * @param headerChecksum 16-bits field used for error-checking of the header
	 * @param sourceIP IPv4 address of sender.
	 * @param destinationIP IPv4 address of receiver.
	 */
	public IPv4Header(byte ipVersion, byte internetHeaderLength,
					  byte dscpOrTypeOfService, byte ecn, int totalLength,
					  int identification, boolean mayFragment,
					  boolean lastFragment, short fragmentOffset,
					  byte timeToLive, byte protocol, int headerChecksum,
					  int sourceIP, int destinationIP){
		this.ipVersion = ipVersion;
		this.internetHeaderLength = internetHeaderLength;
		this.dscpOrTypeOfService = dscpOrTypeOfService;
		this.ecn = ecn;
		this.totalLength = totalLength;
		this.identification = identification;
		this.mayFragment = mayFragment;
		if(mayFragment){
			this.flag |= 0x40;
		}
		this.lastFragment = lastFragment;
		if(lastFragment){
			this.flag |= 0x20;
		}
		this.fragmentOffset = fragmentOffset;
		this.timeToLive = timeToLive;
		this.protocol = protocol;
		this.headerChecksum = headerChecksum;
		this.sourceIP = sourceIP;
		this.destinationIP = destinationIP;
	}

	public byte getIpVersion() {
		return ipVersion;
	}

	byte getInternetHeaderLength() {
		return internetHeaderLength;
	}

	public byte getDscpOrTypeOfService() {
		return dscpOrTypeOfService;
	}

	byte getEcn() {
		return ecn;
	}

	/**
	 * total length of this packet in bytes including IP Header and body(TCP/UDP header + data)
	 * @return totalLength
	 */
	public int getTotalLength() {
		return totalLength;
	}
	/**
	 * total length of IP header in bytes.
	 * @return IP Header total length
	 */
	public int getIPHeaderLength(){
		return (internetHeaderLength * 4);
	}

	public int getIdentification() {
		return identification;
	}

	public byte getFlag() {
		return flag;
	}

	public boolean isMayFragment() {
		return mayFragment;
	}

	public boolean isLastFragment() {
		return lastFragment;
	}

	public short getFragmentOffset() {
		return fragmentOffset;
	}

	public byte getTimeToLive() {
		return timeToLive;
	}

	public byte getProtocol() {
		return protocol;
	}

	public int getHeaderChecksum() {
		return headerChecksum;
	}

	public int getSourceIP() {
		return sourceIP;
	}

	public int getDestinationIP() {
		return destinationIP;
	}

	public void setTotalLength(int totalLength) {
		this.totalLength = totalLength;
	}

	public void setIdentification(int identification) {
		this.identification = identification;
	}

	public void setMayFragment(boolean mayFragment) {
		this.mayFragment = mayFragment;
		if(mayFragment) {
			this.flag |= 0x40;
		} else {
			this.flag &= 0xBF;
		}
	}

//	public void setLastFragment(boolean lastFragment) {
//		this.lastFragment = lastFragment;
//		if(lastFragment){
//			this.flag |= 0x20;
//		}else{
//			this.flag &= 0xDF;
//		}
//	}
//
	public void setSourceIP(int sourceIP) {
		this.sourceIP = sourceIP;
	}

	public void setDestinationIP(int destinationIP) {
		this.destinationIP = destinationIP;
	}
}
