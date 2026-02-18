# Phase 3: Message Ordering and Advanced Flow Control - Implementation Plan

**Project:** Forge Multiplayer Stability  
**Phase:** 3 (In Progress)  
**Start Date:** February 18, 2026  
**Target Completion:** Week of February 24, 2026  
**Status:** Planning Phase

---

## Executive Summary

Phase 3 builds on Phase 1 and Phase 2 to add **message ordering guarantees** and **advanced flow control** to ensure 3+ player games remain consistent and stable under network stress.

**Key Objectives:**
1. ✅ Add sequence numbers to guarantee message ordering
2. ✅ Implement ACK-based flow control
3. ✅ Create resend logic for lost messages
4. ✅ Add connection metrics and monitoring
5. ✅ Implement adaptive message batching
6. ✅ Create message priority system

---

## Problem Statement

### Current Issues (Phase 2)

**Message Ordering:** Messages can arrive out of order
- Player A's action arrives after Player B's action despite being sent first
- Different clients see game state in different order
- Card states can be inconsistent across players

**Flow Control:** No backpressure management
- Fast client can overwhelm slow client with messages
- No way to know if message was received
- Retransmissions not handled

**Example Scenario with 3 Players:**
```
Server → Player1: "Create token"
Server → Player2: "Create token"  
Server → Player3: "Tap token"

But Player 1 receives messages in wrong order:
1. "Tap token" 
2. "Create token"
3. "Create token"

Result: Token doesn't exist when tapped! Crash!
```

---

## Solution Architecture

### 1. Message Sequencing

**Concept:** Each message gets a sequence number and sender ID

```
┌─────────────────────────────────────────┐
│ SequencedNetEvent                       │
├─────────────────────────────────────────┤
│ + messageId: int (global counter)       │
│ + senderPlayerId: int                   │
│ + sequenceNumber: int (per sender)      │
│ + timestamp: long                       │
│ + payload: NetEvent                     │
│ + requiresAck: boolean                  │
└─────────────────────────────────────────┘
```

**Benefits:**
- Unambiguous ordering
- Easy to detect missing messages
- Enables retransmission logic

### 2. ACK-Based Flow Control

**Concept:** Sender waits for ACK before sending too many messages

```
Sender (Server)          Receiver (Client)
    │                          │
    ├─ Message #1 ────────────>│
    │                          ├─ Queue
    ├─ Message #2 ────────────>│
    │                          ├─ Queue
    ├─ Message #3 ────────────>│
    │                          │
    │<───── ACK #2 ────────────┤ (processed up to #2)
    │                          │
    ├─ Message #4 ────────────>│
    │                          │
    │<───── ACK #3 ────────────┤
```

**Benefits:**
- Sender knows what was received
- Prevents queue overflow
- Enables intelligent retransmission
- Adapts to slow clients

### 3. Message Resend Logic

**Concept:** Track sent messages, resend on timeout

```
SentMessageBuffer:
├─ #1: Card tapped (ack received ✓)
├─ #2: Mana added (ack received ✓)
├─ #3: Spell cast (ack timeout → resend)
└─ #4: New message (pending)
```

**Benefits:**
- Handles transient network failures
- Detects packet loss
- Maintains consistency

### 4. Message Priority Queue

**Concept:** Critical messages sent first

```
Priority Levels:
├─ 0: Critical (game state corruption would result)
├─ 1: High (time-sensitive, game-affecting)
├─ 2: Normal (regular updates)
└─ 3: Low (cosmetic, metadata)
```

**Examples:**
- Priority 0: Player wins/loses, card enters battlefield
- Priority 1: Player takes action, combat happens
- Priority 2: Life total update, zone visibility
- Priority 3: Cosmetic animations, metadata

**Benefits:**
- Critical messages delivered first
- Better experience under network stress
- Graceful degradation

### 5. Adaptive Message Batching

**Concept:** Group messages when network is slow

```
Batching Strategy:
┌──────────────────────────────────────┐
│ If network RTT < 50ms:               │
│   Send immediately (unbatched)       │
├──────────────────────────────────────┤
│ If network RTT 50-200ms:             │
│   Batch up to 10 messages or 100ms   │
├──────────────────────────────────────┤
│ If network RTT > 200ms:              │
│   Batch up to 20 messages or 200ms   │
└──────────────────────────────────────┘
```

**Benefits:**
- Reduced overhead on slow networks
- Better bandwidth utilization
- Adapts to network conditions

### 6. Metrics and Monitoring

**Metrics to Track:**
```
Per Player:
├─ messageSequenceNumber (current)
├─ lastAckedSequence (confirmed received)
├─ pendingMessages (awaiting ack)
├─ averageRoundTripTime (latency)
├─ messagesLost (estimated)
├─ messagesResent (count)
└─ lastMessageTime (for timeout)

Per Connection:
├─ messagesIn (received)
├─ messagesOut (sent)
├─ bytesIn (received)
├─ bytesOut (sent)
├─ messageRate (per second)
├─ bandwidthIn (bytes/sec)
└─ bandwidthOut (bytes/sec)
```

---

## Implementation Components

### Component 1: SequencedNetEvent

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/event/SequencedNetEvent.java`

**Responsibilities:**
- Wrap existing NetEvent with sequence info
- Provide serialization
- Track ACK status

**Key Methods:**
```java
public class SequencedNetEvent implements Serializable {
    private int messageId;           // Global unique ID
    private int senderPlayerId;      // Who sent it
    private int sequenceNumber;      // Per-sender ordering
    private long timestamp;          // When sent
    private NetEvent payload;        // Actual message
    private boolean requiresAck;     // Needs confirmation
    private transient boolean acknowledged = false;
    
    public SequencedNetEvent(NetEvent payload, int senderPlayerId, 
                            int sequenceNumber, boolean requiresAck)
    public void markAcknowledged()
    public int getMessageId()
    public int getSenderPlayerId()
    public int getSequenceNumber()
    public NetEvent getPayload()
    public boolean isAcknowledged()
}
```

### Component 2: MessageAckEvent

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/event/MessageAckEvent.java`

**Responsibilities:**
- Confirm message receipt
- Report last processed sequence

**Key Methods:**
```java
public class MessageAckEvent implements NetEvent {
    private int playerIdConfirming;
    private int lastProcessedSequence;
    
    public MessageAckEvent(int playerIdConfirming, int lastProcessedSequence)
    public int getPlayerIdConfirming()
    public int getLastProcessedSequence()
}
```

### Component 3: MessageSequencer

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/server/MessageSequencer.java`

**Responsibilities:**
- Assign sequence numbers
- Maintain per-player sequences
- Track sent messages
- Handle retransmissions

**Key Methods:**
```java
public class MessageSequencer {
    private Map<Integer, Integer> playerSequences;      // playerId → next sequence
    private Map<Integer, LinkedList<SequencedNetEvent>> sentMessages;  // playerId → sent
    private Map<Integer, Integer> lastAckedSequence;    // playerId → acked seq
    
    public SequencedNetEvent sequenceMessage(NetEvent event, int senderId, boolean requiresAck)
    public void handleAck(int playerId, int ackedSequence)
    public List<SequencedNetEvent> getUnackedMessages(int playerId)
    public void resendUnacked(int playerId, RemoteClient client)
    public void cleanup(int playerId)
}
```

### Component 4: ClientMessageBuffer

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/client/ClientMessageBuffer.java`

**Responsibilities:**
- Buffer incoming messages
- Sort by sequence number
- Deliver in order
- Send ACKs

**Key Methods:**
```java
public class ClientMessageBuffer {
    private Map<Integer, TreeMap<Integer, SequencedNetEvent>> playerQueues;
    private Map<Integer, Integer> lastProcessedSequence;
    
    public void addMessage(SequencedNetEvent message)
    public List<NetEvent> getOrderedMessages(int playerId)
    public void markProcessed(int playerId, int sequenceNumber)
    public void sendAck(int playerId, int lastProcessed)
}
```

### Component 5: FlowController

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/FlowController.java`

**Responsibilities:**
- Limit messages in flight
- Implement adaptive windowing
- Monitor network conditions

**Key Methods:**
```java
public class FlowController {
    private static final int MAX_WINDOW_SIZE = 50;
    private static final int MIN_WINDOW_SIZE = 5;
    private Map<Integer, Integer> playerWindowSizes;    // Current window
    private Map<Integer, Integer> playerInflightCount;  // Messages sent, not acked
    private Map<Integer, Long> playerLastRtt;           // Round trip time
    
    public boolean canSendMessage(int playerId)
    public void markMessageSent(int playerId)
    public void handleAck(int playerId, long rttMs)
    public void adjustWindow(int playerId)
}
```

### Component 6: MessageMetrics

**File to Create:** `forge-gui/src/main/java/forge/gamemodes/net/server/MessageMetrics.java`

**Responsibilities:**
- Track per-player statistics
- Monitor performance
- Enable diagnostics

**Key Methods:**
```java
public class MessageMetrics {
    private Map<Integer, PlayerMetrics> playerMetrics;
    
    public void recordMessageSent(int playerId, int messageSize)
    public void recordMessageReceived(int playerId, int messageSize)
    public void recordRoundTripTime(int playerId, long rttMs)
    public PlayerMetrics getMetrics(int playerId)
    public String getFormattedReport()
}

public class PlayerMetrics {
    public int messagesSent;
    public int messagesReceived;
    public int bytesSent;
    public int bytesReceived;
    public int messagesLost;
    public int messagesResent;
    public long averageRttMs;
    public long maxRttMs;
}
```

---

## Implementation Phases

### Phase 3A: Core Message Sequencing (Week 1)
**Goal:** Basic message ordering

**Tasks:**
- [ ] Create `SequencedNetEvent` wrapper
- [ ] Create `MessageAckEvent` for confirmations
- [ ] Create `MessageSequencer` in server
- [ ] Create `ClientMessageBuffer` on client
- [ ] Update `RemoteClient` to use sequences
- [ ] Update `GameClientHandler` to handle sequences
- [ ] Add sequence numbers to broadcast

**Testing:**
- [ ] Messages arrive in correct order
- [ ] 3+ player games show consistent state
- [ ] ACKs are received correctly

### Phase 3B: Flow Control (Week 2)
**Goal:** Backpressure and flow control

**Tasks:**
- [ ] Create `FlowController` class
- [ ] Implement window-based flow control
- [ ] Add RTT measurement
- [ ] Implement adaptive windowing
- [ ] Handle window overflow gracefully
- [ ] Add slow-client detection

**Testing:**
- [ ] Slow client doesn't block others
- [ ] Messages still arrive in order
- [ ] Window size adapts to network

### Phase 3C: Message Metrics & Monitoring (Week 2)
**Goal:** Visibility and diagnostics

**Tasks:**
- [ ] Create `MessageMetrics` class
- [ ] Track per-player statistics
- [ ] Add logging of metrics
- [ ] Create metrics reporting
- [ ] Add dashboard-ready JSON export
- [ ] Create performance threshold alerts

**Testing:**
- [ ] Metrics accurately reflect network state
- [ ] Reports help diagnose issues
- [ ] No performance overhead

### Phase 3D: Adaptive Batching (Week 3)
**Goal:** Bandwidth optimization

**Tasks:**
- [ ] Implement message batching logic
- [ ] Add RTT-based batching decisions
- [ ] Create batch timeout logic
- [ ] Handle priority in batches
- [ ] Add batch serialization

**Testing:**
- [ ] Batching reduces message overhead
- [ ] Low-latency networks unbatch automatically
- [ ] No loss of message ordering

---

## Files to Modify

### New Files
1. `SequencedNetEvent.java` - Message wrapper with sequence
2. `MessageAckEvent.java` - ACK message
3. `MessageSequencer.java` - Server-side sequencing
4. `ClientMessageBuffer.java` - Client-side buffering
5. `FlowController.java` - Flow control logic
6. `MessageMetrics.java` - Metrics tracking
7. `MessageBatcher.java` - Batch optimization
8. `PHASE3_IMPLEMENTATION_SUMMARY.md` - Documentation
9. `PHASE3_QUICK_REFERENCE.md` - Quick guide
10. `PHASE3_INTEGRATION_GUIDE.md` - Testing guide

### Existing Files to Modify
1. `RemoteClient.java` - Add sequence support
2. `GameClientHandler.java` - Add buffer support
3. `FServerManager.java` - Add sequencer integration
4. `FGameClient.java` - Add buffer support
5. `RemoteClient.java` - Add flow control
6. `FServerManager.java` - Add metrics

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          Server                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GameProtocolHandler                                            │
│  ├─ FServerManager                                              │
│  │  ├─ MessageSequencer ─────┐                                  │
│  │  ├─ FlowController ───────┤                                  │
│  │  ├─ MessageMetrics ───────┤                                  │
│  │  └─ MessageBatcher ───────┤                                  │
│  │                           │                                  │
│  └─ RemoteClient ◄──────────┘                                   │
│     ├─ send(SequencedNetEvent)                                  │
│     ├─ handleAck()                                              │
│     └─ metrics tracking                                         │
│                                                                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
                    Netty Channel
                      (TCP/IP)
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                          Client                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GameClientHandler                                              │
│  ├─ FGameClient                                                 │
│  │  ├─ ClientMessageBuffer ───┐                                │
│  │  ├─ FlowController ────────┤                                │
│  │  ├─ MessageMetrics ────────┤                                │
│  │  └─ MessageBatcher ────────┤                                │
│  │                            │                                │
│  └─ ChannelHandler ◄─────────┘                                 │
│     ├─ receive(SequencedNetEvent)                              │
│     ├─ sendAck()                                               │
│     └─ process ordered messages                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Dependencies

### No New External Dependencies
- Uses existing Netty infrastructure
- Uses existing Forge event system
- No additional libraries needed

### Java Features Used
- `TreeMap` for ordered message buffering
- `LinkedList` for sent message tracking
- `AtomicInteger` for thread-safe counters
- `ConcurrentHashMap` for player tracking
- Lambdas for event callbacks

---

## Testing Strategy

### Unit Tests
- [ ] SequencedNetEvent serialization
- [ ] MessageSequencer sequence assignment
- [ ] ClientMessageBuffer ordering
- [ ] FlowController window logic
- [ ] MessageMetrics calculations

### Integration Tests
- [ ] 2-player ordering test
- [ ] 3-player ordering test
- [ ] 4+ player stress test
- [ ] Message loss simulation
- [ ] Slow client handling
- [ ] Flow control under load

### Performance Tests
- [ ] Message overhead (sequence + ack)
- [ ] Memory usage (buffers, tracking)
- [ ] CPU usage (sorting, metrics)
- [ ] Network bandwidth (with/without batching)

---

## Success Criteria

✅ **Message Ordering**
- All messages arrive in correct sequence across all clients
- No inconsistent game state due to ordering

✅ **Flow Control**
- Slow clients don't block fast clients
- No queue overflow
- Graceful backpressure

✅ **Reliability**
- Messages detected as lost are resent
- All players receive all critical messages
- No silent failures

✅ **Performance**
- No degradation vs Phase 2 for 2-player games
- Minimal overhead for 3+ players
- Scales to 8+ players

✅ **Monitoring**
- Metrics accurately reflect network state
- Can identify slow clients
- Enable proactive diagnostics

---

## Timeline

| Week | Tasks | Deliverables |
|------|-------|--------------|
| W1 | Message sequencing | SequencedNetEvent, Sequencer, Buffer |
| W2 | Flow control, metrics | FlowController, MessageMetrics |
| W3 | Batching, testing | MessageBatcher, full test suite |
| W4 | Documentation, polish | Guides, examples, final build |

**Target:** Phase 3 complete by week of February 24, 2026

---

## Risk Mitigation

### Risks Identified

1. **Risk:** Message ordering too complex
   - **Mitigation:** Start with simple sequence numbers, add features incrementally
   - **Fallback:** Keep Phase 2 as baseline, Phase 3 as optional enhancement

2. **Risk:** Flow control causes stalls
   - **Mitigation:** Extensive testing with various network conditions
   - **Fallback:** Disable flow control if issues found

3. **Risk:** Metrics overhead
   - **Mitigation:** Profile before and after
   - **Fallback:** Make metrics collection optional

4. **Risk:** Backward compatibility break
   - **Mitigation:** Wrap all changes, no API breaks
   - **Fallback:** Version protocol messages

---

## Next Steps

1. ✅ Review this plan
2. ⏳ Create SequencedNetEvent
3. ⏳ Create MessageSequencer
4. ⏳ Create ClientMessageBuffer
5. ⏳ Integrate into FServerManager
6. ⏳ Integrate into FGameClient
7. ⏳ Test with 3+ players
8. ⏳ Add Flow Control
9. ⏳ Add Metrics
10. ⏳ Final documentation and build

---

**Plan Created:** February 18, 2026  
**Status:** Ready for Implementation  
**Phase:** 3 (Starting)

