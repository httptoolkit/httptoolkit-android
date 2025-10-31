package tech.httptoolkit.android.vpn.capture;

import java.nio.ByteBuffer;

/**
 * SOCKS5 protocol handler for no-authentication connections.
 * Handles the SOCKS5 handshake and CONNECT command according to RFC 1928.
 */
public class SOCKS5Protocol {
    
    // SOCKS5 constants
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte NO_AUTH = 0x00;
    private static final byte CONNECT_COMMAND = 0x01;
    private static final byte IPV4_ADDRESS_TYPE = 0x01;
    private static final byte IPV6_ADDRESS_TYPE = 0x04;
    private static final byte DOMAIN_ADDRESS_TYPE = 0x03;
    private static final byte RESERVED = 0x00;
    
    // Response codes
    private static final byte SUCCESS = 0x00;
    
    public enum State {
        INIT,
        AUTH_REQUEST_SENT,
        CONNECT_REQUEST_SENT,
        ESTABLISHED
    }
    
    /**
     * Creates the initial authentication method selection request.
     * Format: [VER=5][NMETHODS=1][METHOD=0 (no auth)]
     */
    public static ByteBuffer createAuthRequest() {
        ByteBuffer buffer = ByteBuffer.allocate(3);
        buffer.put(SOCKS_VERSION);  // VER
        buffer.put((byte) 1);       // NMETHODS
        buffer.put(NO_AUTH);        // METHOD (no authentication)
        buffer.flip();
        return buffer;
    }
    
    /**
     * Parses the authentication method selection response.
     * Expected format: [VER=5][METHOD=0]
     * 
     * @param response The response bytes from the SOCKS server
     * @return true if no-auth method was selected, false otherwise
     */
    public static boolean parseAuthResponse(ByteBuffer response) {
        if (response.remaining() < 2) {
            return false;
        }
        
        byte version = response.get();
        byte method = response.get();
        
        return version == SOCKS_VERSION && method == NO_AUTH;
    }
    
    /**
     * Creates a CONNECT request for the specified destination.
     * Format: [VER=5][CMD=1][RSV=0][ATYP=1][DST.ADDR][DST.PORT]
     * 
     * @param destIp The destination IP address (IPv4)
     * @param destPort The destination port
     */
    public static ByteBuffer createConnectRequest(String destIp, int destPort) {
        String[] ipParts = destIp.split("\\.");
        if (ipParts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + destIp);
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(10); // 4 bytes header + 4 bytes IP + 2 bytes port
        
        // SOCKS5 CONNECT request header
        buffer.put(SOCKS_VERSION);      // VER
        buffer.put(CONNECT_COMMAND);    // CMD
        buffer.put(RESERVED);           // RSV
        buffer.put(IPV4_ADDRESS_TYPE);  // ATYP
        
        // Destination IP address (4 bytes)
        for (String part : ipParts) {
            buffer.put((byte) Integer.parseInt(part));
        }
        
        // Destination port (2 bytes, big-endian)
        buffer.putShort((short) destPort);
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * Parses the CONNECT response from the SOCKS server and consumes the full response.
     * Format: [VER=5][REP][RSV=0][ATYP][BND.ADDR][BND.PORT]
     * 
     * The bind address length depends on ATYP:
     * - IPv4: 4 bytes
     * - IPv6: 16 bytes  
     * - Domain: 1 byte length + N bytes domain name
     * 
     * @param response The response bytes from the SOCKS server
     * @return true if connection was successful and response fully consumed, false otherwise
     */
    public static boolean parseConnectResponse(ByteBuffer response) {
        if (response.remaining() < 4) { // Need at least VER+REP+RSV+ATYP
            return false;
        }
        
        byte version = response.get();
        byte reply = response.get();
        byte reserved = response.get();
        byte addressType = response.get();
        
        if (version != SOCKS_VERSION || reply != SUCCESS) {
            return false;
        }
        
        // Consume the bind address based on address type
        int addressLength;
        switch (addressType) {
            case IPV4_ADDRESS_TYPE:
                addressLength = 4;
                break;
            case IPV6_ADDRESS_TYPE:
                addressLength = 16;
                break;
            case DOMAIN_ADDRESS_TYPE:
                if (response.remaining() < 1) {
                    return false; // Need domain length byte
                }
                addressLength = response.get() & 0xFF; // Unsigned byte
                break;
            default:
                return false; // Unknown address type
        }
        
        // Check if we have enough bytes for address + port
        if (response.remaining() < addressLength + 2) {
            return false;
        }
        
        // Skip the bind address
        response.position(response.position() + addressLength);
        
        // Skip the bind port (2 bytes)
        response.position(response.position() + 2);
        
        // At this point, any remaining bytes in the buffer are application data
        return true;
    }
    
    /**
     * Helper method to check if a proxy config supports SOCKS5
     */
    public static boolean isSocksSupported(java.util.List<String> protocols) {
        return protocols != null && protocols.contains("socks5");
    }
}