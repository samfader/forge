package forge.gamemodes.net.server;

import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import io.netty.channel.Channel;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a remote client connection.
 * Tracks connection state, handles backpressure, and manages message sending.
 */
public final class RemoteClient implements IToClient {

    /** Special value indicating the client hasn't been assigned a slot yet. */
    public static final int UNASSIGNED_SLOT = -1;

    /** Connection states */
    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED
    }

    private volatile Channel channel;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private String username;
    private int index = UNASSIGNED_SLOT;
    private volatile ReplyPool replies = new ReplyPool();

    // Metrics for monitoring
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private long connectionTime = 0;

    public RemoteClient(final Channel channel) {
        this.channel = channel;
        this.state = ConnectionState.CONNECTING;
        this.connectionTime = System.currentTimeMillis();
    }

    /**
     * Swap the underlying channel for a reconnecting client.
     * Updates the channel and creates a fresh ReplyPool.
     * This method is synchronized to prevent race conditions with concurrent send() calls.
     */
    public synchronized void swapChannel(final Channel newChannel) {
        this.channel = newChannel;
        this.state = ConnectionState.CONNECTED;
        this.replies = new ReplyPool();
        System.out.println("Channel swapped for client: " + username);
    }

    /**
     * Check if this client has been assigned a valid lobby slot.
     * @return true if the client has a valid slot (index >= 0)
     */
    public boolean hasValidSlot() {
        return index >= 0;
    }

    /**
     * Check if this client is currently connected.
     * @return true if channel is active and state is CONNECTED
     */
    public boolean isConnected() {
        return state == ConnectionState.CONNECTED && channel != null && channel.isActive();
    }

    /**
     * Get the current connection state.
     */
    public ConnectionState getConnectionState() {
        return state;
    }

    /**
     * Set the connection state.
     */
    public void setConnectionState(final ConnectionState newState) {
        this.state = newState;
        System.out.println("Client " + username + " state changed to: " + newState);
    }

    /**
     * Send an event with proper backpressure handling.
     * If the channel write buffer is full, this will not block but will log a warning.
     */
    @Override
    public synchronized void send(final NetEvent event) {
        if (channel == null || !channel.isActive()) {
            System.err.println("Channel is null or inactive, cannot send event to " + username + ": " + event);
            messagesFailed.incrementAndGet();
            return;
        }

        // Check if channel can accept writes (backpressure handling)
        if (!channel.isWritable()) {
            System.err.println("Channel write buffer is full for " + username + ", but sending anyway (backpressure): " + event);
        }

        messagesSent.incrementAndGet();
        channel.writeAndFlush(event).addListener(future -> {
            if (!future.isSuccess()) {
                messagesFailed.incrementAndGet();
                System.err.println("Failed to send event to " + username + " at " + channel.remoteAddress() + ": " + future.cause());
                future.cause().printStackTrace();
                // Don't change connection state here - let ChannelInactive handler do that
            }
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) throws TimeoutException {
        replies.initialize(event.getId());
        send(event);
        return replies.get(event.getId());
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(final int index) {
        this.index = index;
    }

    public ReplyPool getReplyPool() {
        return replies;
    }

    /**
     * Get connection metrics for monitoring.
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    public long getConnectionDurationMs() {
        return System.currentTimeMillis() - connectionTime;
    }

    /**
     * Reset metrics (useful when resuming after reconnect).
     */
    public void resetMetrics() {
        messagesSent.set(0);
        messagesFailed.set(0);
        connectionTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "RemoteClient{" +
                "username='" + username + '\'' +
                ", index=" + index +
                ", state=" + state +
                ", connected=" + isConnected() +
                ", messagesSent=" + messagesSent.get() +
                ", messagesFailed=" + messagesFailed.get() +
                '}';
    }
}
