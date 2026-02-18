# Phase 2 Integration & Testing Guide

**Status:** Ready for Integration Testing  
**Build:** 2.0.10-SNAPSHOT (February 18, 2026)

---

## What You're Getting

### Phase 1 + Phase 2 Combined
Your Forge installation now includes:

**Phase 1 (Critical Fixes):**
- Correct player type creation (AI vs Human)
- Proper player order preservation
- Removed blocking network calls

**Phase 2 (New Features):**
- Connection state tracking
- Dynamic timeout scaling
- Backpressure monitoring
- Thread-safe game state
- Per-client metrics

---

## Quick Start for Testing

### 1. Extract the JAR
```bash
cd /home/dano/IdeaProjects/forge/forge-gui-desktop/target/
ls -lh forge-gui-desktop-2.0.10-SNAPSHOT-jar-with-dependencies.jar
```

### 2. Run a 3-Player Game

**Server Side (Host):**
```bash
java -Xmx4096m \
  --add-opens java.desktop/java.beans=ALL-UNNAMED \
  --add-opens java.desktop/javax.swing=ALL-UNNAMED \
  -jar forge-gui-desktop-2.0.10-SNAPSHOT-jar-with-dependencies.jar
```

**Client Sides (Players 2 & 3):**
```bash
# Same command, but join the server when prompted
```

### 3. Monitor for Success

**Look for in logs:**
```
✅ Client Player1 state changed to: CONNECTED
✅ Set timeout multiplier to 3 for player Player1
✅ Sending event LobbyUpdateEvent to ...
✅ No timeout errors after 30 minutes
```

---

## Verification Checklist

### Before Game Starts
- [ ] All 3+ players in lobby
- [ ] All player names visible and correct
- [ ] All avatars loading correctly
- [ ] Server shows state: CONNECTED for each player

### During Game
- [ ] Game state consistent across all players
- [ ] Card positions match for all players
- [ ] Player actions execute in correct order
- [ ] No timeout messages in logs

### Network Issues
- [ ] Simulate 1000ms latency with `tc` command
- [ ] Game should still work (30-minute timeout)
- [ ] Players should see "backpressure" warning in logs
- [ ] Game should recover when network normalizes

### Disconnection Test
- [ ] Kill client process during game
- [ ] Should see "RECONNECTING" state
- [ ] Host should wait for reconnect
- [ ] Reconnecting player can rejoin within timeout
- [ ] Game continues normally

---

## Key Changes to Test

### 1. Connection State (RemoteClient.java)

**What to Look For:**
```
Console Output Examples:
"Client Player1 state changed to: CONNECTING"
"Client Player1 state changed to: CONNECTED"
"Client Player2 state changed to: RECONNECTING"
```

**How to Verify:**
- [ ] 2 players = 1 CONNECTED state
- [ ] 3 players = 2 CONNECTED states
- [ ] Disconnect shows RECONNECTING or DISCONNECTED
- [ ] Reconnect shows CONNECTED again

### 2. Timeout Scaling (ReplyPool.java)

**What to Verify:**
- [ ] 2 players: 10-minute timeout
- [ ] 3 players: 30-minute timeout
- [ ] 4 players: 40-minute timeout

**How to Test:**
```bash
# Add network delay to simulate slow response
tc qdisc add dev eth0 root netem delay 2000ms

# Game should NOT timeout within 30 minutes
# Remove delay
tc qdisc del dev eth0 root
```

### 3. Backpressure Handling (RemoteClient.java)

**What to Look For:**
```
Console Output:
"Channel write buffer is full for Player2, but sending anyway (backpressure)"
```

**How to Trigger:**
```bash
# Simulate packet loss
tc qdisc add dev eth0 root netem loss 10%

# Or add extreme latency
tc qdisc add dev eth0 root netem delay 5000ms

# Watch for backpressure warnings
```

### 4. Thread Safety (Tracker.java)

**What to Verify:**
- [ ] No "ConcurrentModificationException" errors
- [ ] No "NullPointerException" in game state
- [ ] Consistent game state across players
- [ ] Cards not disappearing or duplicating

**How to Test:**
```bash
# Run many simultaneous actions
# 3+ players each taking 10 actions in 1 second
# Should not crash or corrupt state
```

---

## Debugging Commands

### Enable Detailed Logging

**Add to startup command:**
```bash
-Dlog.level=DEBUG \
-Dlog.network=TRACE
```

### Monitor RemoteClient Metrics

**Add to FServerManager:**
```java
// Periodic logging every 10 seconds
for (RemoteClient client : clients.values()) {
    System.out.println(client.toString());
    // Output: RemoteClient{username='Player1', index=1, state=CONNECTED, 
    //         messagesSent=1520, messagesFailed=0}
}
```

### Watch Connection State Changes

**Monitor logs for:**
```bash
grep "state changed to" server.log
```

### Check Timeout Multiplier

**Look for:**
```bash
grep "timeout multiplier" server.log
# Output: "Set timeout multiplier to 3 for player Player1"
```

### Monitor Backpressure

**Look for:**
```bash
grep "backpressure" server.log
# Output: "Channel write buffer is full for Player2, but sending anyway"
```

---

## Common Test Scenarios

### Scenario 1: Basic 3-Player Game
**Setup:** 1 host + 2 clients  
**Expected:** All players see same state, no timeouts  
**Verification:** Check logs for CONNECTED states

### Scenario 2: High Latency
**Setup:** Add 1000ms latency with `tc`  
**Expected:** Game works, maybe slower, no timeout  
**Verification:** Game runs for 30+ minutes without timeout

### Scenario 3: Packet Loss
**Setup:** Add 5% packet loss with `tc`  
**Expected:** Game works, backpressure warnings in logs  
**Verification:** Messages are retransmitted, game recovers

### Scenario 4: Disconnect/Reconnect
**Setup:** Kill client, restart within 5 minutes  
**Expected:** Player reconnects, game continues  
**Verification:** RECONNECTING → CONNECTED state transition

### Scenario 5: Timeout
**Setup:** Kill client, wait 15 minutes  
**Expected:** Player auto-replaced with AI  
**Verification:** RECONNECTING → DISCONNECTED state

### Scenario 6: Stress Test
**Setup:** 5 players, 100 actions per second  
**Expected:** No crashes, consistent state  
**Verification:** No exceptions in logs

---

## Expected Log Output

### Successful 3-Player Game

```
[Server] Starting Forge Server...
[Server] Client connected to server at [IP]:5555
[Server] Client connected to server at [IP]:5556
[Server] Client connected to server at [IP]:5557

[LoginEvent] Client Player1 logged in
[ServerGameLobby] Client Player1 state changed to: CONNECTED

[LoginEvent] Client Player2 logged in
[ServerGameLobby] Client Player2 state changed to: CONNECTED

[LoginEvent] Client Player3 logged in
[ServerGameLobby] Client Player3 state changed to: CONNECTED

[FServerManager] Set timeout multiplier to 3 for player Player1
[FServerManager] Set timeout multiplier to 3 for player Player2
[FServerManager] Set timeout multiplier to 3 for player Player3

[RemoteClient] Sending event GameStateEvent to Player1
[RemoteClient] Sending event GameStateEvent to Player2
[RemoteClient] Sending event GameStateEvent to Player3

[Game] Player1 plays Swamp
[Game] Player2 plays Lightning Bolt
[Game] Player3 casts Counterspell

[Game] Game complete - Player2 wins!
```

### Network Issue Handling

```
[RemoteClient] Channel write buffer is full for Player2, but sending anyway (backpressure)
[RemoteClient] Sending event GameStateEvent to Player2
[RemoteClient] Failed to send event to Player2: Connection timeout

[Server] Player2 disconnected (waiting for reconnect...)
[FServerManager] Set timeout multiplier to 3 for player Player2
[ServerGameLobby] Client Player2 state changed to: RECONNECTING

[Server] 30 seconds remaining to reconnect...
[Server] Player2 reconnected!
[ServerGameLobby] Client Player2 state changed to: CONNECTED
```

---

## Troubleshooting

### Issue: "Timeout after 10 minutes" in 3-player game
**Root Cause:** 5-minute timeout not scaled  
**Solution:** Verify build includes ReplyPool.java changes  
**Check:** Look for "timeout multiplier to 3" in logs

### Issue: Player names wrong
**Root Cause:** Phase 1 fix not applied  
**Solution:** Verify GameClientHandler.java removes sort  
**Check:** Players should be in order: 0, 1, 2...

### Issue: Game crashes with ConcurrentModificationException
**Root Cause:** Tracker not thread-safe  
**Solution:** Verify Tracker.java changes applied  
**Check:** Look for synchronized blocks around freeze operations

### Issue: Connection state showing DISCONNECTED for active player
**Root Cause:** Channel handler not setting state  
**Solution:** Verify FServerManager.java updates  
**Check:** Look for "state changed to:" messages

### Issue: Frequent "Channel write buffer is full" warnings
**Root Cause:** Network congestion (normal)  
**Solution:** Monitor messagesFailed count  
**Acceptable:** <1% failure rate is normal

---

## Performance Expectations

### 2-Player Game
- Latency: <100ms per message
- Timeout: 10 minutes
- Multiplier: 1x
- Status: ✅ Should work perfectly

### 3-Player Game
- Latency: <150ms per message
- Timeout: 30 minutes (3x)
- Multiplier: 3x
- Status: ✅ Primary target, should work well

### 4+ Player Game
- Latency: <200ms per message
- Timeout: 40+ minutes
- Multiplier: 4x+
- Status: ✅ New feature, should be stable

---

## Rollback Instructions

If critical issues found:

**Step 1: Identify the problem**
```bash
grep "Exception\|Error" server.log | head -20
```

**Step 2: Check which phase affected**
- Player names wrong? → Phase 1 issue
- Timeout errors? → Phase 2 issue
- Thread crash? → Phase 2 issue

**Step 3: Rollback if needed**
```bash
# Revert to previous version from git
git log --oneline | head -5
git revert [commit-hash]
```

---

## Success Criteria

You know Phase 2 is working when:

✅ 3-player game starts without errors  
✅ All player names display correctly  
✅ Server logs show "state changed to: CONNECTED" for all players  
✅ Server logs show "timeout multiplier to 3" for 3 players  
✅ Game runs for 30+ minutes without timeout errors  
✅ Disconnect/reconnect works within 5-minute window  
✅ Backpressure warnings appear under network stress (normal)  
✅ Game state consistent across all players  

---

## Post-Testing Checklist

- [ ] Run all test scenarios
- [ ] Document any failures
- [ ] Check for regressions with 2-player games
- [ ] Verify timeout scaling with different player counts
- [ ] Test disconnect/reconnect
- [ ] Test high latency scenarios
- [ ] Monitor logs for unexpected errors
- [ ] Verify no memory leaks (long-running games)

---

## Getting Help

**For Questions:**
1. Check PHASE2_QUICK_REFERENCE.md
2. Review MULTIPLAYER_BUG_ANALYSIS.md for root causes
3. Look at server logs for specific errors
4. Check RemoteClient metrics

**For Bugs:**
1. Capture full log output
2. Note player count and scenario
3. Include network conditions (latency, loss)
4. Check RemoteClient metrics and connection state

---

## Next Steps After Testing

1. **Success:** Move to Phase 3 (message ordering, flow control)
2. **Issues:** Debug with logs and metrics
3. **Minor Issues:** Create Phase 2.1 patch
4. **Major Issues:** Analyze root cause, plan fix

---

**Ready to Test!** 🎮

For detailed technical information:
- PHASE2_CODE_CHANGES.md - Implementation details
- PHASE2_IMPLEMENTATION_SUMMARY.md - Architecture overview
- PHASE2_QUICK_REFERENCE.md - Quick lookup guide

Generated: February 18, 2026

