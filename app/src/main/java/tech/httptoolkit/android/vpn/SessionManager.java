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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;

import tech.httptoolkit.android.TagKt;
import tech.httptoolkit.android.vpn.socket.DataConst;
import tech.httptoolkit.android.vpn.socket.ICloseSession;
import tech.httptoolkit.android.vpn.socket.SocketNIODataService;
import tech.httptoolkit.android.vpn.socket.SocketProtector;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manage in-memory storage for VPN client session.
 * @author Borey Sao
 * Date: May 20, 2014
 */
public class SessionManager implements ICloseSession {

	private final String TAG = TagKt.getTAG(this);
	private final Map<String, Session> table = new ConcurrentHashMap<>();
	private SocketProtector protector = SocketProtector.getInstance();

	private SocketNIODataService nioService;

	public SessionManager(SocketNIODataService nioService) {
		this.nioService = nioService;
	}

	/**
	 * keep java garbage collector from collecting a session
	 * @param session Session
	 */
	public void keepSessionAlive(Session session) {
		if(session != null){
			String key = Session.getSessionKey(session.getDestIp(), session.getDestPort(),
					session.getSourceIp(), session.getSourcePort());
			table.put(key, session);
		}
	}

	/**
	 * add data from client which will be sending to the destination server later one when receiving PSH flag.
	 * @param buffer Data
	 * @param session Data
	 */
	public int addClientData(ByteBuffer buffer, Session session) {
		if (buffer.limit() <= buffer.position())
			return 0;
		//appending data to buffer
		return session.setSendingData(buffer);
	}

	public Session getSession(int ip, int port, int srcIp, int srcPort) {
		String key = Session.getSessionKey(ip, port, srcIp, srcPort);

		return getSessionByKey(key);
	}

	@Nullable
	public Session getSessionByKey(String key) {
		if(table.containsKey(key)){
			return table.get(key);
		}

		return null;
	}

	/**
	 * remove session from memory, then close socket connection.
	 * @param ip Destination IP Address
	 * @param port Destination Port
	 * @param srcIp Source IP Address
	 * @param srcPort Source Port
	 */
	public void closeSession(int ip, int port, int srcIp, int srcPort){
		String key = Session.getSessionKey(ip, port, srcIp, srcPort);
		Session session = table.remove(key);

		if(session != null){
			final AbstractSelectableChannel channel = session.getChannel();
			try {
				if (channel != null) {
					channel.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			Log.d(TAG,"closed session -> " + key);
		}
	}

	public void closeSession(@NonNull Session session){
		closeSession(session.getDestIp(),
				session.getDestPort(), session.getSourceIp(),
				session.getSourcePort());
	}

	@Nullable
	public Session createNewUDPSession(int ip, int port, int srcIp, int srcPort) {
		String keys = Session.getSessionKey(ip, port, srcIp, srcPort);

		if (table.containsKey(keys))
			return table.get(keys);

		Session session = new Session(srcIp, srcPort, ip, port, this);

		DatagramChannel channel;

		try {
			channel = DatagramChannel.open();
			channel.socket().setSoTimeout(0);
			channel.configureBlocking(false);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		protector.protect(channel.socket());

		session.setChannel(channel);

		//initiate connection to reduce latency
		String ips = PacketUtil.intToIPAddress(ip);
		String sourceIpAddress = PacketUtil.intToIPAddress(srcIp);
		SocketAddress socketAddress = new InetSocketAddress(ips, port);
		Log.d(TAG,"initialized connection to remote UDP server: " + ips + ":" +
				port + " from " + sourceIpAddress + ":" + srcPort);

		try {
			channel.connect(socketAddress);
			session.setConnected(channel.isConnected());
		} catch(IOException e) {
			e.printStackTrace();
			return null;
		}

		try {
			nioService.registerSession(session);
		} catch (ClosedChannelException e) {
			e.printStackTrace();
			Log.e(TAG,"failed to register udp channel with selector: "+ e.getMessage());
			return null;
		}

		if (table.containsKey(keys)) {
			try {
				channel.close();
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		} else {
			table.put(keys, session);
		}

		Log.d(TAG,"new UDP session successfully created.");
		return session;
	}

	@Nullable
	public Session createNewTCPSession(int ip, int port, int srcIp, int srcPort){
		String key = Session.getSessionKey(ip, port, srcIp, srcPort);
		if (table.containsKey(key)) {
			Log.e(TAG, "Session " + key + " was already created.");
			return null;
		}

		Session session = new Session(srcIp, srcPort, ip, port, this);

		SocketChannel channel;
		try {
			channel = SocketChannel.open();
			channel.socket().setKeepAlive(true);
			channel.socket().setTcpNoDelay(true);
			channel.socket().setSoTimeout(0);
			channel.socket().setReceiveBufferSize(DataConst.MAX_RECEIVE_BUFFER_SIZE);
			channel.configureBlocking(false);
		} catch(SocketException e) {
			Log.e(TAG, e.toString());
			return null;
		} catch (IOException e) {
			Log.e(TAG,"Failed to create SocketChannel: "+ e.getMessage());
			return null;
		}
		String ips = PacketUtil.intToIPAddress(ip);
		Log.d(TAG,"created new SocketChannel for " + key);

		protector.protect(channel.socket());
		Log.d(TAG,"Protected new SocketChannel");

		session.setChannel(channel);

		//initiate connection to reduce latency
		// Use the real address, unless tcpPortRedirection defines a different
		// target address for traffic on this port.
		SocketAddress socketAddress = tcpPortRedirection.get(port) != null
			? tcpPortRedirection.get(port)
			: new InetSocketAddress(ips, port);

		Log.d(TAG,"initiate connecting to remote tcp server: " + socketAddress.toString());
		boolean connected;
		try{
			connected = channel.connect(socketAddress);
		} catch(IOException e) {
			Log.e(TAG, e.toString());
			return null;
		}

		session.setConnected(connected);

		try {
			nioService.registerSession(session);
		} catch (ClosedChannelException e) {
			e.printStackTrace();
			Log.e(TAG,"Failed to register channel with selector: " + e.getMessage());
			return null;
		}

		if (table.containsKey(key)) {
			try {
				channel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		} else {
			table.put(key, session);
		}
		return session;
	}

	private SparseArray<InetSocketAddress> tcpPortRedirection = new SparseArray<>();

	public void setTcpPortRedirections(SparseArray<InetSocketAddress> tcpPortRedirection) {
		this.tcpPortRedirection = tcpPortRedirection;
	}
}
