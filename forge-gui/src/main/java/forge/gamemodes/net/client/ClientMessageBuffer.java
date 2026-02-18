package forge.gamemodes.net.client;

import com.google.common.collect.Lists;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.SequencedNetEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side message buffering and ordering.
 *
 * Problem: Messages can arrive out of order due to network delays.
 * Solution: Buffer messages and deliver them in correct order.
 *
 * How it works:
 * 1. Server sends messages with sequence numbers (1, 2, 3, ...)
 * 2. Client receives them (maybe arrives as 3, 1, 2)
 * 3. ClientMessageBuffer queues them by sequence
 * 4. When message 1 is received, it's delivered immediately
 * 5. When message 2 arrives, it's delivered (we were waiting for it)
 * 6. Message 3 was already waiting, so it's delivered
 * 7. Result: Game sees 1, 2, 3 in correct order
 *
 * Usage:
 *   ClientMessageBuffer buffer = new ClientMessageBuffer();
 *
 *   // When receiving sequenced event:
 *   buffer.addMessage(sequencedEvent);
 *
 *   // Get all messages ready to process:
 *   List<NetEvent> ready = buffer.getOrderedMessages(senderId);
 *   for (NetEvent event : ready) {
 *       gameView.updateFromEvent(event);
 *   }
 */
public class ClientMessageBuffer {
    // Per-sender message queues (ordered by sequence)
    // senderId → ordered map of sequence → message
    private final Map<Integer, TreeMap<Integer, SequencedNetEvent>> playerQueues =
        new ConcurrentHashMap<>();

    // Last sequence number processed from each sender
    // senderId → last sequence we've delivered/processed
    private final Map<Integer, Integer> lastProcessedSequence =
        new ConcurrentHashMap<>();

    // Metrics
    private final Map<Integer, Integer> messagesReceived = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> messagesProcessed = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> outOfOrderCount = new ConcurrentHashMap<>();

    /**
     * Add a received message to the buffer.
     * Will be queued in sequence order.
     *
     * @param message The sequenced message received
     */
    public void addMessage(final SequencedNetEvent message) {
        int senderId = message.getSenderPlayerId();
        int seq = message.getSequenceNumber();

        // Get or create queue for this sender
        TreeMap<Integer, SequencedNetEvent> queue =
            playerQueues.computeIfAbsent(senderId, k -> new TreeMap<>());

        // Add message to queue (TreeMap keeps it sorted by sequence)
        queue.put(seq, message);

        // Track metrics
        messagesReceived.merge(senderId, 1, Integer::sum);

        int lastSeq = lastProcessedSequence.getOrDefault(senderId, -1);
        if (seq <= lastSeq) {
            // This is a duplicate or old message
            System.out.println("[ClientMessageBuffer] Warning: Out-of-order or duplicate " +
                            "from Player" + senderId + ": seq=" + seq +
                            " (last processed=" + lastSeq + ")");
            outOfOrderCount.merge(senderId, 1, Integer::sum);
        }

        System.out.println("[ClientMessageBuffer] Added message from Player" + senderId +
                         ": seq=" + seq + ", queue_size=" + queue.size());
    }

    /**
     * Get all messages from a sender that are ready to process.
     * "Ready" means they form a continuous sequence from where we left off.
     *
     * Example:
     *   lastProcessedSequence = 2
     *   Queue: {3, 4, 5, 7}  (missing 6)
     *   Returns: [3, 4, 5]   (stops at gap)
     *
     * @param senderId The player we're getting messages from
     * @return Messages ready to process (already marked as processed internally)
     */
    public List<NetEvent> getOrderedMessages(final int senderId) {
        TreeMap<Integer, SequencedNetEvent> queue = playerQueues.get(senderId);

        if (queue == null || queue.isEmpty()) {
            return Collections.emptyList();
        }

        List<NetEvent> ready = Lists.newArrayList();
        int lastSeq = lastProcessedSequence.getOrDefault(senderId, -1);
        int expectedNext = lastSeq + 1;

        // Process messages while they form a continuous sequence
        for (Integer seq : queue.keySet()) {
            if (seq == expectedNext) {
                // This is the next expected message
                ready.add(queue.get(seq).getPayload());
                messagesProcessed.merge(senderId, 1, Integer::sum);
                lastProcessedSequence.put(senderId, seq);
                expectedNext++;
            } else if (seq > expectedNext) {
                // Gap in sequence, stop processing
                System.out.println("[ClientMessageBuffer] Gap detected for Player" + senderId +
                                 ": missing seq=" + expectedNext + ", have seq=" + seq);
                break;
            }
            // else: seq < expectedNext (shouldn't happen with TreeMap)
        }

        // Remove processed messages from queue
        queue.headMap(lastProcessedSequence.get(senderId), true).clear();

        System.out.println("[ClientMessageBuffer] Ready " + ready.size() +
                         " messages from Player" + senderId + " (seq up to " +
                         lastProcessedSequence.getOrDefault(senderId, -1) + ")");

        return ready;
    }

    /**
     * Mark messages up to a sequence number as processed.
     * Removes them from buffer and updates last processed.
     *
     * @param senderId Which player
     * @param sequenceNumber The sequence number processed up to
     */
    public void markProcessed(final int senderId, final int sequenceNumber) {
        TreeMap<Integer, SequencedNetEvent> queue = playerQueues.get(senderId);

        if (queue == null) {
            return;
        }

        // Update last processed
        int lastSeq = lastProcessedSequence.getOrDefault(senderId, -1);
        if (sequenceNumber > lastSeq) {
            lastProcessedSequence.put(senderId, sequenceNumber);
        }

        // Remove processed messages
        queue.headMap(sequenceNumber, true).clear();

        System.out.println("[ClientMessageBuffer] Marked processed up to seq=" +
                         sequenceNumber + " for Player" + senderId);
    }

    /**
     * Get the last sequence number we've processed from a sender.
     * This is what we'd put in an ACK message.
     */
    public int getLastProcessedSequence(final int senderId) {
        return lastProcessedSequence.getOrDefault(senderId, -1);
    }

    /**
     * Check if there are any pending messages from a sender.
     */
    public boolean hasPendingMessages(final int senderId) {
        TreeMap<Integer, SequencedNetEvent> queue = playerQueues.get(senderId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * Get count of messages waiting to be processed.
     * Helps identify stuck message queues.
     */
    public int getPendingMessageCount(final int senderId) {
        TreeMap<Integer, SequencedNetEvent> queue = playerQueues.get(senderId);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Clean up buffered messages for a player who left.
     */
    public void cleanup(final int senderId) {
        playerQueues.remove(senderId);
        lastProcessedSequence.remove(senderId);
        messagesReceived.remove(senderId);
        messagesProcessed.remove(senderId);
        outOfOrderCount.remove(senderId);

        System.out.println("[ClientMessageBuffer] Cleaned up buffers for Player" + senderId);
    }

    /**
     * Get metrics for debugging.
     */
    public Map<String, Integer> getMetrics(final int senderId) {
        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("messagesReceived", messagesReceived.getOrDefault(senderId, 0));
        metrics.put("messagesProcessed", messagesProcessed.getOrDefault(senderId, 0));
        metrics.put("pendingCount", getPendingMessageCount(senderId));
        metrics.put("lastProcessedSeq", lastProcessedSequence.getOrDefault(senderId, -1));
        metrics.put("outOfOrderCount", outOfOrderCount.getOrDefault(senderId, 0));
        return metrics;
    }

    /**
     * Get a formatted report of buffering status.
     */
    public String getFormattedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Client Message Buffer Report ===\n");

        for (Integer senderId : playerQueues.keySet()) {
            Map<String, Integer> metrics = getMetrics(senderId);
            sb.append(String.format("Player %d: received=%d, processed=%d, pending=%d, lastSeq=%d\n",
                senderId,
                metrics.get("messagesReceived"),
                metrics.get("messagesProcessed"),
                metrics.get("pendingCount"),
                metrics.get("lastProcessedSeq")
            ));
        }

        return sb.toString();
    }
}


