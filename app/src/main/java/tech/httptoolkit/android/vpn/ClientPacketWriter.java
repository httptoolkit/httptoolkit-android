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

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Selector;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import tech.httptoolkit.android.TagKt;

/**
 * write packet data back to VPN client stream. This class is thread safe.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class ClientPacketWriter implements Runnable {

	private final String TAG = TagKt.getTAG(this);

	private final FileOutputStream clientWriter;

	private volatile boolean shutdown = false;
	private final BlockingDeque<byte[]> packetQueue = new LinkedBlockingDeque<>();

	public ClientPacketWriter(FileOutputStream clientWriter) {
		this.clientWriter = clientWriter;
	}

	public void write(byte[] data) {
		if (data.length > 30000) throw new Error("Packet too large");
		Log.i(TAG, "Putting " + data.length + " bytes on the write queue");
		packetQueue.addLast(data);
	}

	public void shutdown() {
		this.shutdown = true;
	}

	@Override
	public void run() {
		while (!this.shutdown) {
			try {
				byte[] data = this.packetQueue.take();
				try {
					this.clientWriter.write(data);
				} catch (IOException e) {
					Log.e(TAG, "Error writing " + data.length + " bytes to the VPN");
					e.printStackTrace();

					this.packetQueue.addFirst(data); // Put the data back, so it's resent
					Thread.sleep(10); // Add an arbitrary tiny pause, in case that helps
				}
			} catch (InterruptedException e) { }
		}
	}
}
