package tech.httptoolkit.android.vpn.socket;

import android.util.Log;

import tech.httptoolkit.android.vpn.IClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
import tech.httptoolkit.android.vpn.SessionHandler;
import tech.httptoolkit.android.vpn.SessionManager;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import tech.httptoolkit.android.TagKt;

/**
 * A service that processes the events around our session connections. It uses a Selector that
 * fires on outgoing socket events (connected, readable, writable), and follows the queue of
 * sessions waiting to write data, from SessionHandler.
 *
 * The actual reading & writing is pushed to Reader/WriterWorkers which run in a thread pool.
 */
public class SocketNIODataService implements Runnable {

	private final String TAG = TagKt.getTAG(this);
	public static final Object syncSelector = new Object();
	public static final Object syncSelector2 = new Object();

	private static IClientPacketWriter writer;
	private volatile boolean shutdown = false;
	private Selector selector;
	//create thread pool for reading/writing data to socket
	private ThreadPoolExecutor workerPool;
	
	public SocketNIODataService(IClientPacketWriter iClientPacketWriter) {
		final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
		workerPool = new ThreadPoolExecutor(8, 100, 10, TimeUnit.SECONDS, taskQueue);
		writer = iClientPacketWriter;
	}

	@Override
	public void run() {
		Log.d(TAG,"SocketNIODataService starting in background...");
		selector = SessionManager.INSTANCE.getSelector();
		runTask();
	}
	/**
	 * notify long running task to shutdown
	 * @param shutdown to be shutdown or not
	 */
	public void setShutdown(boolean shutdown){
		this.shutdown = shutdown;
		SessionManager.INSTANCE.getSelector().wakeup();
	}

	private void runTask(){
		Log.d(TAG, "Selector is running...");
		
		while(!shutdown){
			try {
				synchronized(syncSelector){
					selector.select();
				}
			} catch (IOException e) {
				Log.e(TAG,"Error in Selector.select(): " + e.getMessage());
				try {
					Thread.sleep(100);
				} catch (InterruptedException ex) {
					Log.e(TAG, e.toString());
				}
				continue;
			}

			if (shutdown) {
				break;
			}

			synchronized (syncSelector2) {
				Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

				while (iterator.hasNext()) {
					SelectionKey key = iterator.next();
					SelectableChannel selectableChannel = key.channel();

					if (selectableChannel instanceof SocketChannel) {
						try {
							processTCPSelectionKey(key);
						} catch (IOException e) {
							synchronized (key) {
								key.cancel();
							}
						}
					} else if (selectableChannel instanceof DatagramChannel) {
						processUDPSelectionKey(key);
					}

					iterator.remove();
					if (shutdown) {
						break;
					}
				}

				Queue<Session> sessions = SessionHandler.getInstance().getWritableSessions();

				Session session = sessions.poll();
				while (session != null) {
					if (session.isConnected()) {
						processPendingWrite(session);
					}

					if (shutdown) break;
					session = sessions.poll();
				}
			}
		}
	}

	private void processUDPSelectionKey(SelectionKey key){
		if(!key.isValid()){
			Log.d(TAG,"Invalid SelectionKey for UDP");
			return;
		}
		DatagramChannel channel = (DatagramChannel) key.channel();
		Session session = SessionManager.INSTANCE.getSessionByChannel(channel);
		if(session == null){
			return;
		}
		
		if(!session.isConnected() && key.isConnectable()){
			String ips = PacketUtil.intToIPAddress(session.getDestIp());
			int port = session.getDestPort();
			SocketAddress address = new InetSocketAddress(ips,port);
			try {
				Log.d(TAG,"selector: connecting to remote UDP server: "+ips+":"+port);
				channel = channel.connect(address);
				session.setChannel(channel);
				session.setConnected(channel.isConnected());
			}catch (Exception e) {
				e.printStackTrace();
				session.setAbortingConnection(true);
			}
		}
		if(channel.isConnected()){
			processSelectorRead(key, session);
			processPendingWrite(session);
		}
	}

	private void processTCPSelectionKey(SelectionKey key) throws IOException{
		if(!key.isValid()){
			Log.d(TAG,"Invalid SelectionKey for TCP");
			return;
		}
		SocketChannel channel = (SocketChannel)key.channel();
		Session session = SessionManager.INSTANCE.getSessionByChannel(channel);
		if(session == null){
			return;
		}
		
		if(!session.isConnected() && key.isConnectable()){
			String ips = PacketUtil.intToIPAddress(session.getDestIp());
			int port = session.getDestPort();
			SocketAddress address = new InetSocketAddress(ips, port);
			Log.d(TAG,"connecting to remote tcp server: " + ips + ":" + port);
			boolean connected = false;
			if(!channel.isConnected() && !channel.isConnectionPending()){
				try{
					connected = channel.connect(address);
				} catch (ClosedChannelException | UnresolvedAddressException |
						UnsupportedAddressTypeException | SecurityException e) {
					Log.e(TAG, e.toString());
					session.setAbortingConnection(true);
				} catch (IOException e) {
					Log.e(TAG, e.toString());
					session.setAbortingConnection(true);
				}
			}
			
			if (connected) {
				session.setConnected(connected);
				Log.d(TAG,"connected immediately to remote tcp server: "+ips+":"+port);
			} else {
				if(channel.isConnectionPending()){
					connected = channel.finishConnect();
					session.setConnected(connected);
					Log.d(TAG,"connected to remote tcp server: "+ips+":"+port);
				}
			}
		}
		if(channel.isConnected()){
			// Once connected, we no longer want connect events; we want read events instead.
			session.unsubscribeKey(SelectionKey.OP_CONNECT);
			session.subscribeKey(SelectionKey.OP_READ);
			processSelectorRead(key, session);
			processPendingWrite(session);
		}
	}

	private void processSelectorRead(SelectionKey selectionKey, Session session) {
		boolean canRead;
		synchronized (selectionKey) {
			// There's a race here that requires a lock, as isReadable requires isValid
			canRead = selectionKey.isValid() && selectionKey.isReadable() && !session.isBusyRead();
		}

		if (canRead) {
			session.setBusyread(true);
			final SocketDataReaderWorker worker = new SocketDataReaderWorker(writer, session.getSessionKey());
			workerPool.execute(worker);
		}
	}

	private void processPendingWrite(Session session) {
		if (!session.isBusywrite() && session.hasDataToSend() && session.isDataForSendingReady()) {
			session.setBusywrite(true);
			final SocketDataWriterWorker worker = new SocketDataWriterWorker(writer, session.getSessionKey());
			workerPool.execute(worker);
		}
	}
}
