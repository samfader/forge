package forge.gamemodes.net.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive metrics tracking for network performance monitoring.
 *
 * Tracks per-player statistics:
 * - Message counts (sent, received, lost, resent)
 * - Bandwidth (bytes sent/received)
 * - Latency (RTT, average, max)
 * - Connection health indicators
 *
 * Usage:
 *   MessageMetrics metrics = new MessageMetrics();
 *
 *   // Record message sent:
 *   metrics.recordMessageSent(playerId, payloadSize);
 *
 *   // Record message received:
 *   metrics.recordMessageReceived(playerId, payloadSize);
 *
 *   // Record round-trip time:
 *   metrics.recordRoundTripTime(playerId, rttMs);
 *
 *   // Get report:
 *   String report = metrics.getFormattedReport();
 *   System.out.println(report);
 */
public class MessageMetrics {
    // Per-player metrics
    private final Map<Integer, PlayerMetrics> playerMetrics = new ConcurrentHashMap<>();

    // Global metrics
    private final AtomicLong totalMessagesProcessed = new AtomicLong(0);
    private final long startTime = System.currentTimeMillis();

    /**
     * Record a message sent to a player.
     *
     * @param playerId The player
     * @param messageSize Size of the message in bytes
     */
    public void recordMessageSent(final int playerId, final int messageSize) {
        PlayerMetrics pm = playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());
        pm.messagesSent.incrementAndGet();
        pm.bytesSent.addAndGet(messageSize);
    }

    /**
     * Record a message received from a player.
     *
     * @param playerId The player
     * @param messageSize Size of the message in bytes
     */
    public void recordMessageReceived(final int playerId, final int messageSize) {
        PlayerMetrics pm = playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());
        pm.messagesReceived.incrementAndGet();
        pm.bytesReceived.addAndGet(messageSize);
    }

    /**
     * Record a round-trip time measurement.
     *
     * @param playerId The player
     * @param rttMs The round-trip time in milliseconds
     */
    public void recordRoundTripTime(final int playerId, final long rttMs) {
        PlayerMetrics pm = playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());

        // Update running average
        long count = pm.rttMeasurements.incrementAndGet();
        long oldAvg = pm.averageRttMs.get();
        long newAvg = ((oldAvg * (count - 1)) + rttMs) / count;
        pm.averageRttMs.set(newAvg);

        // Update max
        long currentMax = pm.maxRttMs.get();
        if (rttMs > currentMax) {
            pm.maxRttMs.set(rttMs);
        }
    }

    /**
     * Record a message loss event.
     *
     * @param playerId The player
     */
    public void recordMessageLoss(final int playerId) {
        PlayerMetrics pm = playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());
        pm.messagesLost.incrementAndGet();
    }

    /**
     * Record a message resend event.
     *
     * @param playerId The player
     */
    public void recordMessageResent(final int playerId) {
        PlayerMetrics pm = playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());
        pm.messagesResent.incrementAndGet();
    }

    /**
     * Get metrics for a specific player.
     *
     * @param playerId The player
     * @return PlayerMetrics object (never null)
     */
    public PlayerMetrics getMetrics(final int playerId) {
        return playerMetrics.computeIfAbsent(playerId, k -> new PlayerMetrics());
    }

    /**
     * Get all player metrics.
     *
     * @return Map of playerId → PlayerMetrics
     */
    public Map<Integer, PlayerMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(playerMetrics);
    }

    /**
     * Get a formatted report of all metrics.
     *
     * @return Multi-line string with metrics for all players
     */
    public String getFormattedReport() {
        StringBuilder sb = new StringBuilder();
        long uptime = System.currentTimeMillis() - startTime;
        long uptimeSeconds = uptime / 1000;

        sb.append("=== Message Metrics Report ===\n");
        sb.append(String.format("Uptime: %d seconds (%.1f minutes)\n\n", uptimeSeconds, uptimeSeconds / 60.0));

        for (Map.Entry<Integer, PlayerMetrics> entry : playerMetrics.entrySet()) {
            int playerId = entry.getKey();
            PlayerMetrics pm = entry.getValue();

            sb.append(String.format("Player %d Metrics:\n", playerId));
            sb.append(String.format("  Messages:  sent=%d, received=%d, lost=%d, resent=%d\n",
                                   pm.messagesSent.get(),
                                   pm.messagesReceived.get(),
                                   pm.messagesLost.get(),
                                   pm.messagesResent.get()));

            long bytesSent = pm.bytesSent.get();
            long bytesReceived = pm.bytesReceived.get();
            double bandwidthOut = (bytesSent * 8.0) / Math.max(1, uptimeSeconds);  // bits per second
            double bandwidthIn = (bytesReceived * 8.0) / Math.max(1, uptimeSeconds);

            sb.append(String.format("  Bandwidth: out=%.2f kbps, in=%.2f kbps (%.2f MB sent, %.2f MB recv)\n",
                                   bandwidthOut / 1024, bandwidthIn / 1024,
                                   bytesSent / (1024.0 * 1024),
                                   bytesReceived / (1024.0 * 1024)));

            sb.append(String.format("  Latency:   avg=%d ms, max=%d ms\n",
                                   pm.averageRttMs.get(),
                                   pm.maxRttMs.get()));

            // Loss rate
            long total = pm.messagesSent.get();
            if (total > 0) {
                double lossRate = (pm.messagesLost.get() * 100.0) / total;
                sb.append(String.format("  Loss Rate: %.2f%% (%d lost out of %d)\n",
                                       lossRate, pm.messagesLost.get(), total));
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Get metrics in JSON format (useful for dashboards/monitoring).
     *
     * @return JSON string
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"uptime_ms\": ").append(System.currentTimeMillis() - startTime).append(",\n");
        sb.append("  \"players\": {\n");

        boolean first = true;
        for (Map.Entry<Integer, PlayerMetrics> entry : playerMetrics.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;

            int playerId = entry.getKey();
            PlayerMetrics pm = entry.getValue();

            sb.append("    \"player_").append(playerId).append("\": {\n");
            sb.append("      \"messages_sent\": ").append(pm.messagesSent.get()).append(",\n");
            sb.append("      \"messages_received\": ").append(pm.messagesReceived.get()).append(",\n");
            sb.append("      \"messages_lost\": ").append(pm.messagesLost.get()).append(",\n");
            sb.append("      \"messages_resent\": ").append(pm.messagesResent.get()).append(",\n");
            sb.append("      \"bytes_sent\": ").append(pm.bytesSent.get()).append(",\n");
            sb.append("      \"bytes_received\": ").append(pm.bytesReceived.get()).append(",\n");
            sb.append("      \"average_rtt_ms\": ").append(pm.averageRttMs.get()).append(",\n");
            sb.append("      \"max_rtt_ms\": ").append(pm.maxRttMs.get()).append("\n");
            sb.append("    }");
        }

        sb.append("\n  }\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Reset all metrics (for testing or new game session).
     */
    public void reset() {
        playerMetrics.clear();
    }

    /**
     * Remove metrics for a player (when they disconnect).
     *
     * @param playerId The player
     */
    public void cleanup(final int playerId) {
        playerMetrics.remove(playerId);
    }

    /**
     * Container for per-player metrics.
     */
    public static class PlayerMetrics {
        public final AtomicLong messagesSent = new AtomicLong(0);
        public final AtomicLong messagesReceived = new AtomicLong(0);
        public final AtomicLong messagesLost = new AtomicLong(0);
        public final AtomicLong messagesResent = new AtomicLong(0);
        public final AtomicLong bytesSent = new AtomicLong(0);
        public final AtomicLong bytesReceived = new AtomicLong(0);
        public final AtomicLong averageRttMs = new AtomicLong(0);
        public final AtomicLong maxRttMs = new AtomicLong(0);
        public final AtomicLong rttMeasurements = new AtomicLong(0);

        /**
         * Get the loss rate as a percentage.
         *
         * @return Loss percentage (0-100)
         */
        public double getLossRate() {
            long total = messagesSent.get();
            if (total == 0) return 0.0;
            return (messagesLost.get() * 100.0) / total;
        }

        /**
         * Get the message rate (messages per second).
         *
         * @param uptimeSeconds How long metrics have been running
         * @return Messages per second
         */
        public double getMessageRate(final long uptimeSeconds) {
            if (uptimeSeconds == 0) return 0.0;
            return messagesSent.get() / (double) uptimeSeconds;
        }

        /**
         * Get bandwidth in kilobits per second.
         *
         * @param uptimeSeconds How long metrics have been running
         * @return Kbps (kilobits per second)
         */
        public double getBandwidthOut(final long uptimeSeconds) {
            if (uptimeSeconds == 0) return 0.0;
            return (bytesSent.get() * 8.0) / (1024.0 * uptimeSeconds);
        }

        /**
         * Check if connection is healthy.
         * Healthy = low loss rate and low latency.
         *
         * @return true if healthy
         */
        public boolean isHealthy() {
            return getLossRate() < 5.0 &&  // Less than 5% loss
                   averageRttMs.get() < 500;  // Less than 500ms average RTT
        }
    }
}

