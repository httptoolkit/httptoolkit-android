package tech.httptoolkit.android.vpn.socket;

import java.net.DatagramSocket;
import java.net.Socket;

public interface IProtectSocket {
	boolean protect(Socket socket);
	boolean protect(DatagramSocket socket);
}
