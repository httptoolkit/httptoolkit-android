package tech.httptoolkit.android.vpn.capture;

import java.net.InetSocketAddress;
import java.util.List;

import tech.httptoolkit.android.ProxyConfig;
import tech.httptoolkit.android.ProxyCaptureProtocol;

/*
Reader: in SocketChannelReader, we get data from the server and then push it to the client.
    This needs to somehow push to the protocol handler instead until it's done.
    Could be in session, but feels like that's doing too much already.

Writer: NIO calls SocketChannelWriter.write, IFF session have data available (need to override that check somehow)
    SCW.write writes client data to upstream server connection
    Should instead write directly into protocol handler
 */

public class CaptureController {

    private final InetSocketAddress proxyAddress;
    private final ProxyCaptureProtocol captureProtocol;
    private final List<Integer> portsToCapture;

    public CaptureController(
            ProxyConfig proxyConfig,
            List<Integer> portsToCapture
    ) {
        this.proxyAddress = new InetSocketAddress(
                proxyConfig.getIp(),
                proxyConfig.getPort()
        );

        this.captureProtocol = proxyConfig.getCaptureProtocol();

        this.portsToCapture = portsToCapture;
    }

    public InetSocketAddress getProxyAddress() {
        return proxyAddress;
    }

    public boolean shouldCapture(
            String destIp,
            int destPort
    ) {
        return this.portsToCapture.contains(destPort);
    }

    public ProxyProtocolHandler getProxyHandler(
            String destIp,
            int destPort
    ) {
        if (captureProtocol == ProxyCaptureProtocol.SOCKS5) {
            return new SOCKS5Handler(destIp, destPort);
        } else if (captureProtocol == ProxyCaptureProtocol.RAW) {
            return new DirectHandler(destIp, destPort);
        } else {
            throw new IllegalArgumentException("Unsupported capture protocol: " + captureProtocol);
        }
    }

}
