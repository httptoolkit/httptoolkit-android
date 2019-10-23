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

package com.lipisoft.toyshark.transport.udp;

import com.lipisoft.toyshark.transport.ITransportHeader;

/**
 * data structure for UDP packet header
 * @author Borey Sao
 * Date: June 24, 2014
 */
public class UDPHeader implements ITransportHeader{
	private int sourcePort;
	private int destinationPort;
	private int length;
	private int checksum;

	UDPHeader(int srcPort, int destPort, int length, int checksum){
		this.sourcePort = srcPort;
		this.destinationPort = destPort;
		this.length = length;
		this.checksum = checksum;
	}
	public int getSourcePort() {
		return sourcePort;
	}
	public void setSourcePort(int sourcePort) {
		this.sourcePort = sourcePort;
	}
	public int getDestinationPort() {
		return destinationPort;
	}
	public void setDestinationPort(int destinationPort) {
		this.destinationPort = destinationPort;
	}
	public int getLength() {
		return length;
	}
	public void setLength(int length) {
		this.length = length;
	}
	public int getChecksum() {
		return checksum;
	}
	public void setChecksum(int checksum) {
		this.checksum = checksum;
	}
	
}
