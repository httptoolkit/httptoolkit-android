package tech.httptoolkit.android.vpn.capture;

import android.util.Log;
import java.nio.ByteBuffer;

/**
 * SOCKS5 protocol handler implementation
 */
public class SOCKS5Handler extends ProxyProtocolHandler {
    
    private static final String TAG = "SOCKS5Handler";
    
    private enum SOCKS5State {
        INIT,
        AUTH_REQUEST_PENDING,
        AUTH_ACCEPTED,
        CONNECT_REQUEST_PENDING,
        ESTABLISHED
    }

    private SOCKS5State socksState = SOCKS5State.INIT;
    
    public SOCKS5Handler(String destIp, int destPort) {
        super(destIp, destPort);
        Log.d(TAG, "Created SOCKS5 handler for " + destIp + ":" + destPort);

        // SOCKS5 starts with us sending the first message
        this.state = State.SENDING_HANDSHAKE;
    }

    // We might return handshake data that is then only partially written, so we need to track that
    // and continue writing the remaining data until we get confirmation. Note that ByteBuffer is
    // mutable, tracking it's own position (remaining data to write) automatically.
    private ByteBuffer pendingDataToSend = null;
    
    @Override
    public ByteBuffer getNextHandshakeData() {
        if (pendingDataToSend != null && pendingDataToSend.hasRemaining()) {
            Log.d(TAG, "Resuming handshake data for state : " + socksState);
            return pendingDataToSend;
        }

        Log.d(TAG, "Getting handshake data for state : " + socksState);

        switch (socksState) {
            case INIT:
                return pendingDataToSend = SOCKS5Protocol.createAuthRequest();
                
            case AUTH_ACCEPTED:
                return pendingDataToSend = SOCKS5Protocol.createConnectRequest(originalDestIp, originalDestPort);
                
            default:
                throw new RuntimeException("SOCKS5 asked for data in invalid state: " + socksState);
        }
    }

    public void confirmHandshakeDataWritten() {
        Log.d(TAG, "Handshake data written OK from " + socksState);
        this.pendingDataToSend = null;

        switch (socksState) {
            case INIT:
                socksState = SOCKS5State.AUTH_REQUEST_PENDING;
                state = State.WAITING_FOR_HANDSHAKE;
                break;

            case AUTH_ACCEPTED:
                socksState = SOCKS5State.CONNECT_REQUEST_PENDING;
                state = State.WAITING_FOR_HANDSHAKE;
                break;

            default:
                throw new RuntimeException("SOCKS5 write confirmed in invalid state: " + socksState);
        }
    }
    
    @Override
    public void processHandshakeData(ByteBuffer data) {
        ByteBuffer view = data.asReadOnlyBuffer();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);

        switch (socksState) {
            case AUTH_REQUEST_PENDING:
                if (SOCKS5Protocol.parseAuthResponse(data)) {
                    socksState = SOCKS5State.AUTH_ACCEPTED;
                    state = State.SENDING_HANDSHAKE;
                } else {
                    Log.e(TAG, "SOCKS5 auth failed");
                    state = State.FAILED;
                }
                return;
                
            case CONNECT_REQUEST_PENDING:
                if (SOCKS5Protocol.parseConnectResponse(data)) {
                    Log.d(TAG, "SOCKS5 connection established");
                    socksState = SOCKS5State.ESTABLISHED;
                    state = State.ESTABLISHED;
                } else {
                    Log.e(TAG, "SOCKS5 connect failed");
                    state = State.FAILED;
                }
                return;
                
            default:
                Log.w(TAG, "Unexpected data in SOCKS5 state: " + socksState);
        }
    }
}