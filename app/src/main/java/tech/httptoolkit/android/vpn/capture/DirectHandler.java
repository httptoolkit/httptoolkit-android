package tech.httptoolkit.android.vpn.capture;

import java.nio.ByteBuffer;

public class DirectHandler extends ProxyProtocolHandler {
    
    public DirectHandler(String destIp, int destPort) {
        super(destIp, destPort);
        state = State.ESTABLISHED;
    }

    @Override
    public ByteBuffer getNextHandshakeData() {
        // No handshake needed for direct connections
        throw new RuntimeException("DirectHandler has no handshake data to send");
    }

    @Override
    public void confirmHandshakeDataWritten() {
        // No handshake needed for direct connections
        throw new RuntimeException("DirectHandler has no handshake data to confirm");
    }


    @Override
    public void processHandshakeData(ByteBuffer data) {
        // No handshake data to process for direct connections
        throw new RuntimeException("DirectHandler should receive no handshake data");
    }
}