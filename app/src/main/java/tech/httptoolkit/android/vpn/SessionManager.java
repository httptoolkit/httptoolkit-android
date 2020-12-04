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

import org.jetbrains.annotations.NotNull;

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
		if (table.containsKey(key)) {
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

	@NotNull
	public Session createNewUDPSession(int ip, int port, int srcIp, int srcPort) throws IOException {
		String keys = Session.getSessionKey(ip, port, srcIp, srcPort);

		// For TCP, we freak out if you try to create an already existing session.
		// With UDP though, it's totally fine:
		Session existingSession = table.get(keys);
		if (existingSession != null) return existingSession;

		Session session = new Session(srcIp, srcPort, ip, port, this);

		DatagramChannel channel;

		channel = DatagramChannel.open();
		channel.socket().setSoTimeout(0);
		channel.configureBlocking(false);
		protector.protect(channel.socket());

		session.setChannel(channel);

		// Initiate connection early to reduce latency
		String ips = PacketUtil.intToIPAddress(ip);
		String sourceIpAddress = PacketUtil.intToIPAddress(srcIp);
		SocketAddress socketAddress = new InetSocketAddress(ips, port);
		Log.d(TAG,"initialized connection to remote UDP server: " + ips + ":" +
				port + " from " + sourceIpAddress + ":" + srcPort);

		channel.connect(socketAddress);
		session.setConnected(channel.isConnected());

		table.put(keys, session);

		Log.d(TAG,"new UDP session successfully created.");
		return session;
	}

	@NotNull
	public Session createNewTCPSession(int ip, int port, int srcIp, int srcPort) throws IOException {
		String key = Session.getSessionKey(ip, port, srcIp, srcPort);

		Session existingSession = table.get(key);

		// This can happen if we receive two SYN packets somehow. That shouldn't happen,
		// given that our connection is local & should be 100% reliable, but it can.
		// We return the initialized session, which will be reacked to indicate rejection.
		if (existingSession != null) return existingSession;

		Session session = new Session(srcIp, srcPort, ip, port, this);

		SocketChannel channel;
		channel = SocketChannel.open();
		channel.socket().setKeepAlive(true);
		channel.socket().setTcpNoDelay(true);
		channel.socket().setSoTimeout(0);
		channel.socket().setReceiveBufferSize(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		channel.configureBlocking(false);

		String ips = PacketUtil.intToIPAddress(ip);
		Log.d(TAG,"created new SocketChannel for " + key);

		protector.protect(channel.socket());
		Log.d(TAG,"Protected new SocketChannel");

		session.setChannel(channel);

		// Initiate connection straight away, to reduce latency
		// We use the real address, unless tcpPortRedirection redirects us to a different
		// target address for traffic on this port.
		SocketAddress socketAddress = tcpPortRedirection.get(port) != null
			? tcpPortRedirection.get(port)
			: new InetSocketAddress(ips, port);

		Log.d(TAG,"Initiate connecting to remote tcp server: " + socketAddress.toString());
		boolean connected = channel.connect(socketAddress);
		session.setConnected(connected);

		table.put(key, session);

		return session;
	}

	private SparseArray<InetSocketAddress> tcpPortRedirection = new SparseArray<>();

	public void setTcpPortRedirections(SparseArray<InetSocketAddress> tcpPortRedirection) {
		this.tcpPortRedirection = tcpPortRedirection;
	}
}
