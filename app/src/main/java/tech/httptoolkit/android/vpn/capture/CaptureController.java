package tech.httptoolkit.android.vpn.capture;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import tech.httptoolkit.android.ProxyConfig;
import tech.httptoolkit.android.ProxyCaptureProtocol;

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

    public boolean shouldCapture(InetSocketAddress destAddress) {
        return !destAddress.equals(proxyAddress) &&
                this.portsToCapture.contains(destAddress.getPort());
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
