package local.mapreview;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;

/** A server-owned actor has no TCP channel and must never disconnect a human. */
public final class DummyReviewConnection extends ClientConnection {
    public DummyReviewConnection() { super(NetworkSide.SERVERBOUND); }
    @Override public void send(Packet<?> packet) { }
    @Override public void send(Packet<?> packet, PacketCallbacks callbacks) { }
    @Override public void send(Packet<?> packet, PacketCallbacks callbacks, boolean flush) { }
    @Override public boolean isOpen() { return true; }
    @Override public boolean isLocal() { return false; }
    @Override public void flush() { }
    @Override public void tryDisableAutoRead() { }
    @Override public SocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 0); }
}
