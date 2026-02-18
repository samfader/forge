````markdown
# Phase 3: Integration Guide

**Date:** February 18, 2026  
**Target Audience:** Developers integrating Phase 3 components  
**Complexity:** Medium  

---

## Overview

This guide walks through integrating Phase 3 components into the existing Forge multiplayer codebase.

**Key Integration Points:**
1. `FServerManager.java` - Add sequencer, flow controller, metrics
2. `GameClientHandler.java` - Add message buffer
3. `RemoteClient.java` - Add metrics calls
4. Testing and validation

---

## Step 1: Update FServerManager.java

### 1.1 Add Phase 3 Fields

The `FServerManager` class needs to hold references to the three server-side Phase 3 components.

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java`

**Add these fields:**
```java
private MessageSequencer messageSequencer;
private FlowController flowController;
private MessageMetrics messageMetrics;
```

### 1.2 Initialize Components

In the constructor or `initialize()` method, create instances:

```java
public void initializePhase3Components(int expectedPlayerCount) {
    this.messageSequencer = new MessageSequencer();
    this.flowController = new FlowController();
    this.messageMetrics = new MessageMetrics();
    
    System.out.println("[Phase3] Initialized: Sequencer, FlowController, Metrics");
    System.out.println("[Phase3] Expected players: " + expectedPlayerCount);
}
```

### 1.3 Import Required Classes

Add these imports to FServerManager:

```java
import forge.gamemodes.net.FlowController;
import forge.gamemodes.net.event.MessageAckEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.SequencedNetEvent;
import forge.gamemodes.net.server.MessageMetrics;
import forge.gamemodes.net.server.MessageSequencer;
```

---

## Step 2: Integrate Sequencing into Broadcast

### 2.1 Update Broadcast Method

When broadcasting to a player, wrap the event with sequencing:

**Current Code (Before):**
```java
private void broadcastTo(final NetEvent event, final RemoteClient to) {
    event.updateForClient(to);
    to.send(event);
}
```

**Updated Code (After Phase 3):**
```java
private void broadcastTo(final NetEvent event, final RemoteClient to, final int playerId) {
    event.updateForClient(to);
    
    // Phase 3A: Sequence the message
    SequencedNetEvent seqEvent = messageSequencer.sequenceMessage(
        event,
        playerId,
        true  // requiresAck = true for broadcasts
    );
    
    // Phase 3B: Check flow control
    if (!flowController.canSendMessage(playerId)) {
        System.out.println("[Phase3] Flow control blocked send to Player" + playerId);
        // TODO: Implement queue retry mechanism
        return;
    }
    
    // Phase 3C: Actually send the sequenced event
    to.send(seqEvent);
    
    // Phase 3D: Update flow control counters
    flowController.markMessageSent(playerId);
    
    // Phase 3E: Record metrics
    int estimatedSize = 256;  // Rough estimate, could be refined
    messageMetrics.recordMessageSent(playerId, estimatedSize);
}
```

### 2.2 Update Call Sites

Update places that call `broadcastTo()` to pass the player ID:

**Example:**
```java
// Old: broadcastTo(event, remoteClient);
// New: broadcastTo(event, remoteClient, playerIndex);

for (RemoteClient client : onlinePlayers.values()) {
    broadcastTo(event, client, client.getIndex());
}
```

---

## Step 3: Handle ACKs from Clients

### 3.1 Create ACK Handler

Add a method to handle `MessageAckEvent` from clients:

```java
public void handleMessageAck(MessageAckEvent ack) {
    int playerId = ack.getPlayerIdSendingAck();
    int lastProcessedSeq = ack.getLastProcessedSequence();
    long ackTimestamp = ack.getAckTimestamp();
    long rttMs = System.currentTimeMillis() - ackTimestamp;
    
    System.out.println("[Phase3] ACK from Player" + playerId + 
                      ": seq=" + lastProcessedSeq + ", RTT=" + rttMs + "ms");
    
    // 1. Tell sequencer about the ACK
    messageSequencer.handleAck(playerId, lastProcessedSeq);
    
    // 2. Tell flow controller (may adjust window)
    flowController.handleAck(playerId, rttMs);
    
    // 3. Record metrics
    messageMetrics.recordRoundTripTime(playerId, rttMs);
}
```

### 3.2 Register ACK Handler

In `GameProtocolHandler` or `GameClientHandler`, route `MessageAckEvent` to the ACK handler:

```java
if (msg instanceof MessageAckEvent) {
    MessageAckEvent ack = (MessageAckEvent) msg;
    fServerManager.handleMessageAck(ack);
} else if (msg instanceof NetEvent) {
    // ... existing code ...
}
```

---

## Step 4: Update GameClientHandler (Client)

### 4.1 Add Message Buffer

Add to `GameClientHandler` class:

```java
private ClientMessageBuffer messageBuffer;

public GameClientHandler() {
    this.messageBuffer = new ClientMessageBuffer();
    System.out.println("[Phase3] ClientMessageBuffer initialized");
}
```

### 4.2 Import Required Classes

```java
import forge.gamemodes.net.client.ClientMessageBuffer;
import forge.gamemodes.net.event.MessageAckEvent;
import forge.gamemodes.net.event.SequencedNetEvent;
```

### 4.3 Handle Incoming Sequenced Events

Update the channel read handler to buffer and order messages:

**Current Code (Before):**
```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof NetEvent) {
        NetEvent event = (NetEvent) msg;
        // Handle event directly
        handleGameEvent(event);
    }
}
```

**Updated Code (After Phase 3):**
```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof SequencedNetEvent) {
        SequencedNetEvent seqEvent = (SequencedNetEvent) msg;
        int senderId = seqEvent.getSenderPlayerId();
        int seq = seqEvent.getSequenceNumber();
        
        System.out.println("[Phase3] Received sequenced event from Player" + 
                          senderId + ": seq=" + seq);
        
        // 1. Buffer the message
        messageBuffer.addMessage(seqEvent);
        
        // 2. Get all messages ready to process (in correct order)
        List<NetEvent> orderedMessages = messageBuffer.getOrderedMessages(senderId);
        
        // 3. Process each message in order
        for (NetEvent event : orderedMessages) {
            System.out.println("[Phase3] Processing ordered event: " + 
                              event.getClass().getSimpleName());
            handleGameEvent(event);
        }
        
        // 4. Send ACK back to server
        if (seqEvent.requiresAck()) {
            int lastSeq = messageBuffer.getLastProcessedSequence(senderId);
            MessageAckEvent ack = new MessageAckEvent(myPlayerId, lastSeq);
            ctx.writeAndFlush(ack);
            System.out.println("[Phase3] Sent ACK: seq=" + lastSeq);
        }
        
    } else if (msg instanceof MessageAckEvent) {
        // Handle ACK on client side if needed (shouldn't normally happen)
        System.out.println("[Phase3] Unexpected ACK received on client");
        
    } else if (msg instanceof NetEvent) {
        // Fallback for non-sequenced events (backward compatibility)
        NetEvent event = (NetEvent) msg;
        System.out.println("[Phase3] Warning: Received non-sequenced event");
        handleGameEvent(event);
    }
}
```

---

## Step 5: Cleanup on Player Disconnect

### 5.1 Update RemoteClient.java

Add cleanup call when a player disconnects:

```java
public void onDisconnect() {
    // Phase 3 cleanup
    messageSequencer.cleanup(this.index);
    flowController.cleanup(this.index);
    messageMetrics.cleanup(this.index);
    
    System.out.println("[Phase3] Cleanup complete for Player" + this.index);
}
```

### 5.2 Update GameClientHandler

Clean up client-side buffer:

```java
@Override
public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    // Phase 3 cleanup
    messageBuffer.cleanup(0);  // Cleanup for server (playerId 0)
    
    System.out.println("[Phase3] Client disconnected, buffer cleaned up");
    super.channelInactive(ctx);
}
```

---

## Step 6: Enable Diagnostic Logging

### 6.1 Add Debug Methods

Add to `FServerManager`:

```java
public void printPhase3Report() {
    System.out.println("\n========== PHASE 3 NETWORK REPORT ==========");
    System.out.println(messageSequencer.getReport());
    System.out.println(flowController.getReport());
    System.out.println(messageMetrics.getFormattedReport());
    System.out.println("==========================================\n");
}

public String getPhase3MetricsJson() {
    return messageMetrics.toJson();
}
```

### 6.2 Call During Gameplay

Periodically log diagnostics (e.g., every 30 seconds):

```java
private long lastReportTime = 0;

public void tick() {
    long now = System.currentTimeMillis();
    if (now - lastReportTime > 30000) {  // Every 30 seconds
        printPhase3Report();
        lastReportTime = now;
    }
}
```

---

## Step 7: Testing Integration

### 7.1 Create Integration Test

```java
public class Phase3IntegrationTest {
    
    @Test
    public void test2PlayerMessageOrdering() throws Exception {
        // Setup server with 2 players
        FServerManager server = new FServerManager();
        server.initializePhase3Components(2);
        
        // Send sequence of events
        NetEvent event1 = new TestGameEvent("event1");
        NetEvent event2 = new TestGameEvent("event2");
        NetEvent event3 = new TestGameEvent("event3");
        
        // Simulate server sending to player
        SequencedNetEvent seq1 = server.messageSequencer.sequenceMessage(event1, 0, true);
        SequencedNetEvent seq2 = server.messageSequencer.sequenceMessage(event2, 0, true);
        SequencedNetEvent seq3 = server.messageSequencer.sequenceMessage(event3, 0, true);
        
        // Simulate client receiving out-of-order (3, 1, 2)
        ClientMessageBuffer clientBuffer = new ClientMessageBuffer();
        clientBuffer.addMessage(seq3);
        clientBuffer.addMessage(seq1);
        clientBuffer.addMessage(seq2);
        
        // Get ordered messages
        List<NetEvent> ordered = clientBuffer.getOrderedMessages(0);
        
        // Verify order
        assertEquals(3, ordered.size());
        assertEquals("event1", ordered.get(0).toString());
        assertEquals("event2", ordered.get(1).toString());
        assertEquals("event3", ordered.get(2).toString());
    }
    
    @Test
    public void test3PlayerMessageOrdering() throws Exception {
        FServerManager server = new FServerManager();
        server.initializePhase3Components(3);
        
        // Simulate sending from 3 different senders
        NetEvent e1 = new TestGameEvent("p0-1");
        NetEvent e2 = new TestGameEvent("p1-1");
        NetEvent e3 = new TestGameEvent("p2-1");
        
        SequencedNetEvent s1 = server.messageSequencer.sequenceMessage(e1, 0, true);
        SequencedNetEvent s2 = server.messageSequencer.sequenceMessage(e2, 1, true);
        SequencedNetEvent s3 = server.messageSequencer.sequenceMessage(e3, 2, true);
        
        // Verify each sender has seq 0
        assertEquals(0, s1.getSequenceNumber());
        assertEquals(0, s2.getSequenceNumber());
        assertEquals(0, s3.getSequenceNumber());
        
        // Verify senders are different
        assertEquals(0, s1.getSenderPlayerId());
        assertEquals(1, s2.getSenderPlayerId());
        assertEquals(2, s3.getSenderPlayerId());
    }
    
    @Test
    public void testFlowControlWindowAdaptation() throws Exception {
        FlowController flow = new FlowController();
        
        // Send 25 messages (initial window)
        for (int i = 0; i < 25; i++) {
            assertTrue(flow.canSendMessage(0), "Should allow message " + i);
            flow.markMessageSent(0);
        }
        
        // 26th should be blocked
        assertFalse(flow.canSendMessage(0));
        
        // Simulate ACK with fast RTT
        flow.handleAck(0, 25);  // RTT = 25ms
        
        // Window should adapt to 50 (fast network)
        assertEquals(50, flow.getWindowSize(0));
    }
}
```

### 7.2 Integration Testing Checklist

- [ ] Compile without errors
- [ ] 2-player game works, no crashes
- [ ] 3-player game works, no crashes
- [ ] 4+ player game works
- [ ] Messages arrive in correct order
- [ ] All players see consistent game state
- [ ] ACKs are sent and received
- [ ] Flow control adapts to network
- [ ] Metrics are accurate
- [ ] No memory leaks over time
- [ ] Disconnect/reconnect works
- [ ] High latency network works

---

## Step 8: Performance Verification

### 8.1 Measure Overhead

Create a benchmark test:

```java
@Test
public void benchmarkSequencing() {
    MessageSequencer seq = new MessageSequencer();
    NetEvent event = new TestGameEvent("test");
    
    long startTime = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
        seq.sequenceMessage(event, 0, true);
    }
    long endTime = System.nanoTime();
    
    long nanosPer = (endTime - startTime) / 10000;
    System.out.println("Sequencing: " + nanosPer + " nanos per message");
    
    // Should be < 1000 nanos per message
    assertTrue(nanosPer < 1000);
}
```

### 8.2 Memory Profiling

Monitor memory usage:
- With 2 players: Should increase < 500KB
- With 4 players: Should increase < 1MB
- With 8 players: Should increase < 2MB

---

## Step 9: Rollout Plan

### 9.1 Phase

1. **Integration in development branch** (this step)
2. **Local testing** with 2-4 players
3. **Code review** before merging
4. **Merge to main** branch
5. **Beta testing** with larger player counts
6. **Production deployment**

### 9.2 Rollback Plan

If critical issues found:
1. Keep Phase 2 code in separate branch
2. Can revert Phase 3 changes if needed
3. Phase 3 is backward compatible (wraps Phase 2)

---

## Troubleshooting

### Issue: Messages out of order

**Check:**
- [ ] ClientMessageBuffer is being used
- [ ] `getOrderedMessages()` is called after `addMessage()`
- [ ] Sequence numbers are being assigned

### Issue: Flow control blocking sends

**Check:**
- [ ] `flowController.canSendMessage()` checked before send
- [ ] `flowController.markMessageSent()` called after send
- [ ] ACKs are being received and processed

### Issue: High memory usage

**Check:**
- [ ] `cleanup()` called when players disconnect
- [ ] Message history limit not set too high (max 100)
- [ ] Metrics not accumulating indefinitely

### Issue: Slow network causes crashes

**Check:**
- [ ] Flow controller window adapts based on RTT
- [ ] ReplyPool timeout is set to `playerCount * 10 minutes`
- [ ] No hard-coded 5-minute timeouts remaining

---

## Next Steps After Integration

1. **Run full test suite** - Ensure no regressions
2. **Performance benchmarking** - Measure impact
3. **Load testing** - Test with many messages
4. **Documentation** - Update user guides
5. **Deploy** - Roll out to production

---

## Files Modified Checklist

- [ ] FServerManager.java - Added Phase 3 initialization and logic
- [ ] GameClientHandler.java - Added message buffer and ordering
- [ ] RemoteClient.java - Added cleanup on disconnect
- [ ] GameProtocolHandler.java - Route MessageAckEvent to handler

## Files Created Checklist

- [x] SequencedNetEvent.java
- [x] MessageAckEvent.java
- [x] MessageSequencer.java
- [x] ClientMessageBuffer.java
- [x] FlowController.java
- [x] MessageMetrics.java
- [x] PHASE3_IMPLEMENTATION_SUMMARY.md
- [x] PHASE3_QUICK_REFERENCE.md
- [x] PHASE3_INTEGRATION_GUIDE.md (this file)

---

**Integration Guide Created:** February 18, 2026  
**Status:** Ready for Developer Implementation  
**Estimated Integration Time:** 4-6 hours

````

