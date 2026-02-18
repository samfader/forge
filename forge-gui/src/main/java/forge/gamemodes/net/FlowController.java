package forge.gamemodes.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flow control for preventing sender from overwhelming receiver.
 *
 * Problem: Server sends messages faster than slow client can process.
 * Solution: Limit messages "in flight" (sent but not acked) per player.
 *
 * How it works:
 * 1. Server has a "window" of allowed in-flight messages per player
 * 2. When sending message, increment in-flight count
 * 3. When receiving ACK, decrement in-flight count
 * 4. If in-flight count reaches window size, server must wait for ACKs
 * 5. Window size adapts based on network latency (RTT)
 *
 * Network Adaptation:
 * - Fast network (RTT < 50ms):    Large window (50 messages)
 * - Normal network (RTT 50-200ms):Medium window (25 messages)
 * - Slow network (RTT > 200ms):   Small window (10 messages)
 *
 * This prevents:
 * - Queue overflow on client side
 * - Memory exhaustion on server
 * - Cascade failures from one slow client
 *
 * Usage:
 *   FlowController flow = new FlowController();
 *
 *   // Before sending:
 *   if (flow.canSendMessage(playerId)) {
 *       sequencer.sequence(event, playerId);
 *       remoteClient.send(event);
 *       flow.markMessageSent(playerId);
 *   } else {
 *       // Wait and retry later
 *       Thread.sleep(50);
 *   }
 *
 *   // When receiving ACK:
 *   flow.handleAck(playerId, rttMs);
 */
public class FlowController {
    // Window size limits
    private static final int MAX_WINDOW_SIZE = 50;
    private static final int MIN_WINDOW_SIZE = 5;

    // RTT (Round Trip Time) thresholds for adaptation
    private static final long FAST_NETWORK_RTT_MS = 50;
    private static final long NORMAL_NETWORK_RTT_MS = 200;

    // Per-player window sizes (how many messages can be in-flight)
    private final Map<Integer, Integer> playerWindowSizes = new ConcurrentHashMap<>();

    // Per-player in-flight message counts (sent but not acked)
    private final Map<Integer, Integer> playerInflightCount = new ConcurrentHashMap<>();

    // Last measured RTT for each player (for window adaptation)
    private final Map<Integer, Long> playerLastRtt = new ConcurrentHashMap<>();

    // Initial window size for new players
    private static final int INITIAL_WINDOW_SIZE = 25;

    /**
     * Check if we can send another message to a player.
     * Returns true if in-flight count is below window size.
     *
     * @param playerId The player we want to send to
     * @return true if we can send, false if we need to wait for ACKs
     */
    public boolean canSendMessage(final int playerId) {
        int window = playerWindowSizes.getOrDefault(playerId, INITIAL_WINDOW_SIZE);
        int inflight = playerInflightCount.getOrDefault(playerId, 0);
        return inflight < window;
    }

    /**
     * Mark that we sent a message to a player.
     * Increments the in-flight counter.
     *
     * @param playerId The player who received the message
     */
    public void markMessageSent(final int playerId) {
        playerInflightCount.merge(playerId, 1, Integer::sum);
        int inflight = playerInflightCount.get(playerId);
        int window = playerWindowSizes.getOrDefault(playerId, INITIAL_WINDOW_SIZE);

        if (inflight % 10 == 0) {  // Log every 10 messages
            System.out.println("[FlowController] Player" + playerId +
                             ": inflight=" + inflight + " / window=" + window);
        }
    }

    /**
     * Handle acknowledgment from a player.
     * Decrements in-flight counter and adapts window based on RTT.
     *
     * @param playerId The player sending ACK
     * @param rttMs The round-trip time in milliseconds
     */
    public void handleAck(final int playerId, final long rttMs) {
        // Decrement inflight count
        int newCount = Math.max(0, playerInflightCount.getOrDefault(playerId, 1) - 1);
        playerInflightCount.put(playerId, newCount);

        // Update RTT and adapt window
        playerLastRtt.put(playerId, rttMs);
        adjustWindow(playerId, rttMs);
    }

    /**
     * Adjust the window size based on measured RTT.
     * Fast networks get larger windows, slow networks get smaller windows.
     *
     * @param playerId The player
     * @param rttMs The measured round-trip time
     */
    private void adjustWindow(final int playerId, final long rttMs) {
        int newWindow;

        if (rttMs < FAST_NETWORK_RTT_MS) {
            // Fast network: large window
            newWindow = MAX_WINDOW_SIZE;
        } else if (rttMs < NORMAL_NETWORK_RTT_MS) {
            // Normal network: medium window
            newWindow = (MAX_WINDOW_SIZE + MIN_WINDOW_SIZE) / 2;
        } else {
            // Slow network: small window
            newWindow = MIN_WINDOW_SIZE;
        }

        int oldWindow = playerWindowSizes.getOrDefault(playerId, INITIAL_WINDOW_SIZE);
        if (newWindow != oldWindow) {
            playerWindowSizes.put(playerId, newWindow);
            System.out.println("[FlowController] Player" + playerId + " window adjusted: " +
                             oldWindow + " → " + newWindow + " (RTT=" + rttMs + "ms)");
        }
    }

    /**
     * Get the current window size for a player.
     *
     * @param playerId The player
     * @return Current window size (max in-flight messages)
     */
    public int getWindowSize(final int playerId) {
        return playerWindowSizes.getOrDefault(playerId, INITIAL_WINDOW_SIZE);
    }

    /**
     * Get the current in-flight count for a player.
     *
     * @param playerId The player
     * @return Number of messages sent but not acked
     */
    public int getInflightCount(final int playerId) {
        return playerInflightCount.getOrDefault(playerId, 0);
    }

    /**
     * Get the last measured RTT for a player.
     *
     * @param playerId The player
     * @return Round-trip time in milliseconds (0 if not measured)
     */
    public long getLastRtt(final int playerId) {
        return playerLastRtt.getOrDefault(playerId, 0L);
    }

    /**
     * Clean up tracking for a disconnected player.
     * Call when player leaves the game.
     *
     * @param playerId The player who disconnected
     */
    public void cleanup(final int playerId) {
        playerWindowSizes.remove(playerId);
        playerInflightCount.remove(playerId);
        playerLastRtt.remove(playerId);
        System.out.println("[FlowController] Cleanup for Player" + playerId);
    }

    /**
     * Get a diagnostic report of flow control state.
     *
     * @return Formatted string with all player states
     */
    public String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("FlowController Report:\n");

        for (int playerId : playerWindowSizes.keySet()) {
            int window = getWindowSize(playerId);
            int inflight = getInflightCount(playerId);
            long rtt = getLastRtt(playerId);
            int utilization = (inflight * 100) / window;

            sb.append(String.format("  Player%d: window=%d, inflight=%d (%d%%), RTT=%dms\n",
                                   playerId, window, inflight, utilization, rtt));
        }

        return sb.toString();
    }
}

