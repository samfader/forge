package forge.gamemodes.net.event;

import java.io.Serializable;

/**
 * Wraps a NetEvent with sequence information to guarantee ordering.
 *
 * Each SequencedNetEvent has:
 * - messageId: Global unique identifier (assigned by server)
 * - senderPlayerId: Who sent it
 * - sequenceNumber: Per-sender ordering (0, 1, 2, ...)
 * - timestamp: When sent
 * - requiresAck: Whether client must confirm receipt
 *
 * This allows clients to:
 * 1. Detect and reorder messages that arrive out of order
 * 2. Detect missing messages (gaps in sequence)
 * 3. Send back ACKs to confirm processing
 *
 * Example:
 * Server sends to Player 1:
 *   SequencedNetEvent(id=101, sender=0, seq=1, payload=TapCard)
 *   SequencedNetEvent(id=102, sender=0, seq=2, payload=AddMana)
 *   SequencedNetEvent(id=103, sender=0, seq=3, payload=CastSpell)
 *
 * If they arrive as 103, 101, 102, client can reorder them correctly
 * and process in order: TapCard → AddMana → CastSpell
 */
public class SequencedNetEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int messageId;           // Global unique ID assigned by server
    private final int senderPlayerId;      // Player who sent this (0=host, 1=p2, 2=p3, ...)
    private final int sequenceNumber;      // Order from this sender (0, 1, 2, ...)
    private final long timestamp;          // When sent (System.currentTimeMillis())
    private final NetEvent payload;        // Actual message to be processed
    private final boolean requiresAck;     // Does sender need ACK confirmation?

    // Transient field (not serialized) - only exists on server after receiving
    private transient boolean acknowledged = false;

    /**
     * Creates a new sequenced network event.
     *
     * @param payload The actual NetEvent to send
     * @param senderPlayerId The player sending this (0=host/first client, 1=second, etc)
     * @param sequenceNumber The order number for this sender
     * @param requiresAck Whether client must send back ACK
     */
    public SequencedNetEvent(final NetEvent payload,
                            final int senderPlayerId,
                            final int sequenceNumber,
                            final boolean requiresAck) {
        this.payload = payload;
        this.senderPlayerId = senderPlayerId;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = System.currentTimeMillis();
        this.requiresAck = requiresAck;
        this.messageId = generateMessageId();
    }

    /**
     * Alternative constructor for messages that don't need ACK (like broadcasts).
     */
    public SequencedNetEvent(final NetEvent payload,
                            final int senderPlayerId,
                            final int sequenceNumber) {
        this(payload, senderPlayerId, sequenceNumber, false);
    }

    /**
     * Get the globally unique message ID.
     * This ID is unique across all players and all games.
     */
    public int getMessageId() {
        return messageId;
    }

    /**
     * Get the player who sent this message.
     * @return Player index (0=host, 1=client2, 2=client3, ...)
     */
    public int getSenderPlayerId() {
        return senderPlayerId;
    }

    /**
     * Get the sequence number for ordering from this specific sender.
     * Messages from sender X should be processed in order of sequence number.
     * @return Sequence number (0, 1, 2, ...)
     */
    public int getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Get when this message was sent.
     * @return Milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the actual payload (the real NetEvent).
     * @return The wrapped NetEvent
     */
    public NetEvent getPayload() {
        return payload;
    }

    /**
     * Check if this message requires acknowledgment.
     * Critical messages (game-affecting) typically do.
     * @return true if client must send ACK
     */
    public boolean requiresAck() {
        return requiresAck;
    }

    /**
     * Mark this message as acknowledged by the client.
     * Only used on server-side for tracking.
     */
    public void markAcknowledged() {
        this.acknowledged = true;
    }

    /**
     * Check if this message has been acknowledged by the client.
     * @return true if client confirmed receipt
     */
    public boolean isAcknowledged() {
        return acknowledged;
    }

    /**
     * Get the age of this message in milliseconds.
     * Useful for detecting stale messages.
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - timestamp;
    }

    @Override
    public String toString() {
        return "SequencedNetEvent{" +
                "messageId=" + messageId +
                ", sender=" + senderPlayerId +
                ", seq=" + sequenceNumber +
                ", requiresAck=" + requiresAck +
                ", payload=" + payload.getClass().getSimpleName() +
                "}";
    }

    /**
     * Generate a unique message ID.
     * In production, this would use a global counter synchronized across the server.
     * For now, use a simple timestamp-based approach.
     */
    private static int generateMessageId() {
        // In real implementation, use atomic counter on server
        // For now, use hash of current time + random
        return (int) (System.nanoTime() ^ (System.nanoTime() >> 32));
    }
}

