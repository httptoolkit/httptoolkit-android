# Android packet-intercepting VPN

This package contains the logic that captures and then forwards packets, on device, using the Android VPN interface.

It provides the components (woven together in ProxyVpnRunnable) that read raw IP packets from the VPN interface, parse them, make the corresponding upstream TCP/UDP requests, and proxy data between the two.

The code has been very heavily edited, but was originally based on [ToyShark](https://github.com/LipiLee/ToyShark/tree/0963ad8bda35cd2b2e8e0ea0a47873683b604453), which was in turn based on AT&T's [ARO project](https://github.com/attdevsupport/ARO/), all used under the Apache 2 license. The full original source & license is available from those repo URLs.

## Details

Some quick notes on how this all hangs together:

* The VPN interface gives us a file descriptor, from which we can read & write raw IP packets.
* On Android we can't actually send or receive raw IP packets from upstream, without native code, at least.
* To handle this, we parse the IP packets, work out how to do the equivalent TCP/UDP/ICMP upstream, do it, and proxy between that & the VPN interface/
* SessionHandler handles the VPN packet side of this: it receives IP packets in `handlePacket` from a thread that loops on `vpn.read()`, handles ACKs etc, and makes calls to `SessionManager` to create/close upstream connections when required.
* SessionManager allows opening and closing upstream sessions and their channels (TCP/UDP connections), registering each channel with `SocketNIODataService`.
* SocketNIODataService runs on a single thread, using NIO to write VPN-received data from the session to the upstream channel when the channel is available, and to read data from upstream channels when it's received.
* Data is sent back into the VPN (by both SessionHandler and the NIO thread) via ClientPacketWriter, which runs on its own thread, looping on a blocking queue to do each requested write.
* SessionManager can be configured with traffic redirections, to redirect traffic on certain ports to different destinations (e.g. all outgoing traffic on 80/443 to a transparent proxy server).