package tech.httptoolkit.android.vpn.socket;

import android.annotation.SuppressLint;
import android.util.Log;

import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
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
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import tech.httptoolkit.android.TagKt;

/**
 * A service that single-threadedly processes the events around our session connections,
 * entirely via non-blocking NIO.
 *
 * It uses a Selector that fires on outgoing socket events (connected, readable, writable),
 * handles the resulting operations, and keeps those subscriptions up to date.
 */
public class SocketNIODataService implements Runnable {

	private final String TAG = TagKt.getTAG(this);
	private final ReentrantLock nioSelectionLock = new ReentrantLock();
	private final ReentrantLock nioHandlingLock = new ReentrantLock();
	private final Selector selector = Selector.open();

	private final SocketChannelReader reader;
	private final SocketChannelWriter writer;

	private volatile boolean shutdown = false;

	
	public SocketNIODataService(ClientPacketWriter clientPacketWriter) throws IOException {
		reader = new SocketChannelReader(clientPacketWriter);
		writer = new SocketChannelWriter(clientPacketWriter);
	}

	@Override
	public void run() {
		Log.d(TAG,"SocketNIODataService starting in background...");
		runTask();
	}

	public void registerSession(Session session) throws ClosedChannelException {
		AbstractSelectableChannel channel = session.getChannel();

		boolean isConnected = channel instanceof DatagramChannel
				? ((DatagramChannel) channel).isConnected()
				: ((SocketChannel) channel).isConnected();

		Log.i(TAG, "Registering new session: " + session);

		Lock selectorLock = lockSelector(selector);
		try {
			SelectionKey selectionKey = channel.register(selector,
					isConnected
							? SelectionKey.OP_READ
							: SelectionKey.OP_CONNECT
			);
			session.setSelectionKey(selectionKey);
			selectionKey.attach(session);
			Log.d(TAG, "Registered selector successfully");
		} finally {
			selectorLock.unlock();
		}
	}

	private Lock lockSelector(Selector selector) {
		boolean gotSelectionLock = nioSelectionLock.tryLock();
		if (gotSelectionLock) return nioSelectionLock;

		nioHandlingLock.lock(); // Ensure the NIO thread can't do anything on wakeup
		selector.wakeup();

		nioSelectionLock.lock(); // Actually get the lock we want
		nioHandlingLock.unlock(); // Release the handling lock, which we no longer care about

		return nioSelectionLock;
	}

	/**
	 * If the selector is currently select()ing, wake it up (e.g. to register changes to
	 * interestOps). If it's not (and so it probably will select() very soon anyway) do nothing.
	 * This is designed to be run after changing readyOps, to ensure the new ops get monitored
	 * immediately (and fire immediately, if already ready). Without this, that blocks.
	 */
	public void refreshSelect(Session session) {
		boolean gotLock = nioSelectionLock.tryLock();

		if (!gotLock) {
			session.getSelectionKey().selector().wakeup();
		} else {
			nioSelectionLock.unlock();
		}
	}

	/**
	 * Shut down the NIO thread
	 */
	public void shutdown(){
		this.shutdown = true;
		selector.wakeup();
	}

	private void runTask(){
		Log.i(TAG, "NIO selector is running...");
		
		while(!shutdown){
			try {
				nioSelectionLock.lockInterruptibly();
				selector.select();
			} catch (IOException e) {
				Log.e(TAG,"Error in Selector.select(): " + e.getMessage());
				try {
					Thread.sleep(100);
				} catch (InterruptedException ex) {
					Log.e(TAG, e.toString());
				}
				continue;
			} catch (InterruptedException ex) {
				Log.i(TAG, "Select() interrupted");
			} finally {
				if (nioSelectionLock.isHeldByCurrentThread()) {
					nioSelectionLock.unlock();
				}
			}

			if (shutdown) {
				break;
			}

			// A lock here makes it possible to reliably grab the selection lock above
			nioHandlingLock.lock();
			try {
				Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

				while (iterator.hasNext()) {
					SelectionKey key = iterator.next();
					SelectableChannel selectableChannel = key.channel();

					Session session = ((Session) key.attachment());
					synchronized (session) { // Sessions are locked during processing (no VPN data races)
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
					}

					iterator.remove();
					if (shutdown) {
						break;
					}
				}
			} finally {
				nioHandlingLock.unlock();
			}
		}
		Log.i(TAG, "NIO selector shutdown");
	}

	private void processUDPSelectionKey(SelectionKey key){
		if(!key.isValid()){
			Log.d(TAG,"Invalid SelectionKey for UDP");
			return;
		}
		DatagramChannel channel = (DatagramChannel) key.channel();

		Session session = ((Session) key.attachment());
		if (session == null) {
			return;
		}
		
		if (!session.isConnected() && key.isConnectable()) {
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
			processConnectedSelection(key, session);
		}
	}

	private void processTCPSelectionKey(SelectionKey key) throws IOException{
		if (!key.isValid()) {
			Log.d(TAG,"Invalid SelectionKey for TCP");
			return;
		}

		SocketChannel channel = (SocketChannel)key.channel();
		Session session = ((Session) key.attachment());
		if(session == null){
			return;
		}
		
		if (!session.isConnected() && key.isConnectable()) {
			String ips = PacketUtil.intToIPAddress(session.getDestIp());
			int port = session.getDestPort();
			SocketAddress address = new InetSocketAddress(ips, port);
			Log.d(TAG,"connecting to remote tcp server: " + ips + ":" + port);
			boolean connected = false;
			if(!channel.isConnected() && !channel.isConnectionPending()){
				try{
					connected = channel.connect(address);
				} catch (
					UnresolvedAddressException |
					UnsupportedAddressTypeException |
					SecurityException |
					IOException e
				) {
					Log.e(TAG, e.toString());
					session.setAbortingConnection(true);
				}
			}
			
			if (connected) {
				session.setConnected(true);
				Log.d(TAG,"connected immediately to remote tcp server: "+ips+":"+port);
			} else {
				if (channel.isConnectionPending()) {
					connected = channel.finishConnect();
					session.setConnected(connected);
					Log.d(TAG,"connected to remote tcp server: "+ips+":"+port);
				}
			}
		}

		if (channel.isConnected()) {
			processConnectedSelection(key, session);
		}
	}

	private void processConnectedSelection(SelectionKey key, Session session) {
		// Whilst connected, we always want READ and not CONNECT events
		session.unsubscribeKey(SelectionKey.OP_CONNECT);
		session.subscribeKey(SelectionKey.OP_READ);
		processSelectorRead(key, session);
		processPendingWrite(key, session);
	}

	private void processSelectorRead(SelectionKey selectionKey, Session session) {
		boolean canRead;
		synchronized (selectionKey) {
			// There's a race here that requires a lock, as isReadable requires isValid
			canRead = selectionKey.isValid() && selectionKey.isReadable();
		}

		if (canRead) reader.read(session);
	}

	private void processPendingWrite(SelectionKey selectionKey, Session session) {
		// Nothing to write? Skip this entirely, and make sure we're not subscribed
		if (!session.hasDataToSend() || !session.isDataForSendingReady()) return;

		boolean canWrite;
		synchronized (selectionKey) {
			// There's a race here that requires a lock, as isReadable requires isValid
			canWrite = selectionKey.isValid() && selectionKey.isWritable();
		}

		if (canWrite) {
			session.unsubscribeKey(SelectionKey.OP_WRITE);
			writer.write(session); // This will resubscribe to OP_WRITE if it can't complete
		}
	}
}
