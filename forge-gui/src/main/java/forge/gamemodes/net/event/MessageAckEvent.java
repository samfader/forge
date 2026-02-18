package forge.gamemodes.net.event;

import forge.gamemodes.net.server.RemoteClient;

/**
 * Acknowledgment message sent by client to server.
 *
 * Purpose: Tell server which messages have been processed
 *
 * When server sends to Player 1:
 *   Msg1 (seq=1), Msg2 (seq=2), Msg3 (seq=3)
 *
 * Client processes Msg1 and Msg2, then sends:
 *   MessageAckEvent(playerId=1, lastProcessedSeq=2)
 *
 * Server then knows:
 *   - Msg1 is confirmed received and processed
 *   - Msg2 is confirmed received and processed
 *   - Msg3 may still be in flight or processing
 *
 * This enables:
 * 1. Detecting lost messages (gaps in ack sequence)
 * 2. Flow control (server doesn't send too many before acks)
 * 3. Retransmission (server can resend unacked messages)
 */
public class MessageAckEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final int playerIdSendingAck;  // Which client is sending this ACK
    private final int lastProcessedSequence;  // Up to and including this sequence number
    private final long ackTimestamp;       // When ACK was sent (for RTT calculation)

    /**
     * Creates an ACK message from a client to server.
     *
     * @param playerIdSendingAck Which player is sending this ACK (0=host, 1=p2, 2=p3, ...)
     * @param lastProcessedSequence The highest sequence number we've processed
     */
    public MessageAckEvent(final int playerIdSendingAck,
                          final int lastProcessedSequence) {
        this.playerIdSendingAck = playerIdSendingAck;
        this.lastProcessedSequence = lastProcessedSequence;
        this.ackTimestamp = System.currentTimeMillis();
    }

    /**
     * Which player is sending this acknowledgment?
     */
    public int getPlayerIdSendingAck() {
        return playerIdSendingAck;
    }

    /**
     * What's the last sequence number this player has processed?
     *
     * All messages from sender with seq <= this number have been processed.
     * Messages with seq > this number may be pending or lost.
     */
    public int getLastProcessedSequence() {
        return lastProcessedSequence;
    }

    /**
     * When was this ACK sent (for RTT calculation)?
     */
    public long getAckTimestamp() {
        return ackTimestamp;
    }

    @Override
    public String toString() {
        return "MessageAckEvent{" +
                "from_player=" + playerIdSendingAck +
                ", last_processed_seq=" + lastProcessedSequence +
                "}";
    }

    @Override
    public void updateForClient(final RemoteClient client) {
        // No-op for ACK events
    }
}


