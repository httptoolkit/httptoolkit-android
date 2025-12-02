package tech.httptoolkit.android.vpn.capture;

import java.nio.ByteBuffer;

/**
 * Abstract interface for handling different proxy protocols (SOCKS5, HTTP CONNECT, etc.)
 */
public abstract class ProxyProtocolHandler {
    
    public enum State {
        INIT,
        SENDING_HANDSHAKE, // We have something to send upstream
        WAITING_FOR_HANDSHAKE, // We want something from upstream
        ESTABLISHED,
        FAILED
    }
    
    protected State state = State.INIT;
    protected String originalDestIp;
    protected int originalDestPort;
    
    public ProxyProtocolHandler(String destIp, int destPort) {
        this.originalDestIp = destIp;
        this.originalDestPort = destPort;
    }
    
    /**
     * Process incoming data from the proxy server during handshake phase.
     * Any unread/unused data will remain in the returned ByteBuffer and should
     * continue processing (e.g. should be sent to the proxy server over the tunnel).
     *
     * @param data Data received from proxy server
     */
    public abstract void processHandshakeData(ByteBuffer data);

    /**
     * Get the next data to send to the proxy server for handshake. After this has been
     * called, returnUnwrittenHandshakeData or confirmHandshakeDataWritten must be called.
     * @return Data to send.
     * @throws RuntimeException if called when no handshake data is available to send.
     */
    public abstract ByteBuffer getNextHandshakeData();

    public abstract void confirmHandshakeDataWritten();

    public boolean hasHandshakeDataToSend() {
        return state == State.SENDING_HANDSHAKE;
    }

    public boolean wantsHandshakeData() {
        return state == State.WAITING_FOR_HANDSHAKE;
    }
    
    /**
     * Check if the proxy connection is established and ready for application data
     */
    public boolean isPending() {
        return !(state == State.ESTABLISHED || state == State.FAILED);
    }
    
    /**
     * Check if the proxy handshake has failed
     */
    public boolean hasFailed() {
        return state == State.FAILED;
    }
}