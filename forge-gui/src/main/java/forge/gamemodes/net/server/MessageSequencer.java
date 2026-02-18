package forge.gamemodes.net.server;

import com.google.common.collect.Lists;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.SequencedNetEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side message sequencing and acknowledgment tracking.
 *
 * Responsibilities:
 * 1. Assign sequence numbers to outgoing messages
 * 2. Track which messages each player has acknowledged
 * 3. Detect and resend lost messages
 * 4. Monitor player communication health
 *
 * Usage:
 *   MessageSequencer sequencer = new MessageSequencer();
 *
 *   // When sending to a player:
 *   SequencedNetEvent event = sequencer.sequenceMessage(payload, playerId, true);
 *   remoteClient.send(event);
 *
 *   // When receiving ACK from player:
 *   sequencer.handleAck(playerId, ackedSequence);
 *
 *   // To resend any lost messages:
 *   List<SequencedNetEvent> lost = sequencer.getUnackedMessages(playerId);
 */
public class MessageSequencer {
    // Per-player sequence number counters
    private final Map<Integer, Integer> playerSequences =
        new ConcurrentHashMap<>();

    // Sent messages by player (for retransmission if needed)
    // playerId → list of sent messages in order
    private final Map<Integer, LinkedList<SequencedNetEvent>> sentMessages =
        new ConcurrentHashMap<>();

    // Last acknowledged sequence per player
    // playerId → highest seq number they've acked
    private final Map<Integer, Integer> lastAckedSequence =
        new ConcurrentHashMap<>();

    // Metrics
    private final Map<Integer, Integer> messagesSentCount = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> acksReceivedCount = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> messagesResentCount = new ConcurrentHashMap<>();

    /**
     * Sequence a message for sending to a specific player.
     *
     * @param event The event to send
     * @param playerId The player receiving it (0=host, 1=p2, 2=p3, ...)
     * @param requiresAck Whether player must acknowledge
     * @return Sequenced event ready to send
     */
    public SequencedNetEvent sequenceMessage(final NetEvent event,
                                            final int playerId,
                                            final boolean requiresAck) {
        // Get or create sequence counter for this player
        Integer sequence = playerSequences.computeIfAbsent(playerId, k -> 0);

        // Increment for next message
        playerSequences.put(playerId, sequence + 1);

        // Create sequenced event
        SequencedNetEvent sequenced = new SequencedNetEvent(event, playerId, sequence, requiresAck);

        // Track sent message
        sentMessages.computeIfAbsent(playerId, k -> new LinkedList<>())
                   .addLast(sequenced);

        // Update metrics
        messagesSentCount.merge(playerId, 1, Integer::sum);

        // Limit history to last 100 messages (don't keep forever)
        LinkedList<SequencedNetEvent> history = sentMessages.get(playerId);
        if (history.size() > 100) {
            history.removeFirst();
        }

        System.out.println("[MessageSequencer] Sequenced message for Player" + playerId +
                          ": seq=" + sequence + ", requiresAck=" + requiresAck);

        return sequenced;
    }

    /**
     * Handle acknowledgment from a player.
     * Tells us which messages they've confirmed receiving.
     *
     * @param playerId The player sending the ACK
     * @param ackedSequence The highest sequence number they've processed
     */
    public void handleAck(final int playerId, final int ackedSequence) {
        int previousAck = lastAckedSequence.getOrDefault(playerId, -1);
        lastAckedSequence.put(playerId, ackedSequence);
        acksReceivedCount.merge(playerId, 1, Integer::sum);

        if (ackedSequence > previousAck) {
            System.out.println("[MessageSequencer] ACK from Player" + playerId +
                             ": seq=" + ackedSequence + " (+" + (ackedSequence - previousAck) + ")");
        }
    }

    /**
     * Get all messages that haven't been acknowledged yet.
     * These are candidates for resending if we suspect loss.
     *
     * @param playerId Which player
     * @return List of unacked messages in order
     */
    public List<SequencedNetEvent> getUnackedMessages(final int playerId) {
        int ackedSeq = lastAckedSequence.getOrDefault(playerId, -1);
        LinkedList<SequencedNetEvent> history = sentMessages.get(playerId);

        if (history == null) {
            return Collections.emptyList();
        }

        List<SequencedNetEvent> unacked = Lists.newArrayList();
        for (SequencedNetEvent msg : history) {
            if (msg.getSequenceNumber() > ackedSeq) {
                unacked.add(msg);
            }
        }
        return unacked;
    }

    /**
     * Resend all unacked messages to a player.
     * Called when we suspect messages were lost.
     *
     * @param playerId Which player
     * @param client The client connection
     */
    public void resendUnacked(final int playerId, final RemoteClient client) {
        List<SequencedNetEvent> unacked = getUnackedMessages(playerId);

        if (unacked.isEmpty()) {
            System.out.println("[MessageSequencer] No unacked messages to resend for Player" + playerId);
            return;
        }

        System.out.println("[MessageSequencer] Resending " + unacked.size() +
                         " unacked messages to Player" + playerId);

        for (SequencedNetEvent msg : unacked) {
            // Send the sequenced event (which is a NetEvent)
            client.send((NetEvent) msg);
            messagesResentCount.merge(playerId, 1, Integer::sum);
        }
    }

    /**
     * Get the current sequence number for a player.
     * This is the next sequence number that would be assigned.
     */
    public int getCurrentSequence(final int playerId) {
        return playerSequences.getOrDefault(playerId, 0);
    }

    /**
     * Check if there are gaps in the sequence.
     * Gaps indicate lost messages.
     *
     * @param playerId Which player
     * @return true if there are unacked messages (potential loss)
     */
    public boolean hasUnackedMessages(final int playerId) {
        return !getUnackedMessages(playerId).isEmpty();
    }

    /**
     * Clean up tracking for a player who disconnected.
     * Prevents memory leaks.
     */
    public void cleanup(final int playerId) {
        playerSequences.remove(playerId);
        sentMessages.remove(playerId);
        lastAckedSequence.remove(playerId);
        messagesSentCount.remove(playerId);
        acksReceivedCount.remove(playerId);
        messagesResentCount.remove(playerId);

        System.out.println("[MessageSequencer] Cleaned up tracking for Player" + playerId);
    }

    /**
     * Get metrics for a player.
     */
    public Map<String, Integer> getMetrics(final int playerId) {
        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("currentSeq", getCurrentSequence(playerId));
        metrics.put("lastAckedSeq", lastAckedSequence.getOrDefault(playerId, -1));
        metrics.put("unackedCount", getUnackedMessages(playerId).size());
        metrics.put("messagesSent", messagesSentCount.getOrDefault(playerId, 0));
        metrics.put("acksReceived", acksReceivedCount.getOrDefault(playerId, 0));
        metrics.put("messagesResent", messagesResentCount.getOrDefault(playerId, 0));
        return metrics;
    }

    /**
     * Get a formatted report of all players' sequencing status.
     */
    public String getFormattedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Message Sequencer Report ===\n");

        for (Integer playerId : playerSequences.keySet()) {
            Map<String, Integer> metrics = getMetrics(playerId);
            sb.append(String.format("Player %d: seq=%d, acked=%d, unacked=%d, sent=%d, resent=%d\n",
                playerId,
                metrics.get("currentSeq"),
                metrics.get("lastAckedSeq"),
                metrics.get("unackedCount"),
                metrics.get("messagesSent"),
                metrics.get("messagesResent")
            ));
        }

        return sb.toString();
    }
}



