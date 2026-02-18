# Phase 3: START HERE
**Date:** February 18, 2026  
**Status:** Components Complete - Ready for Integration  
**Your Task:** Integrate Phase 3 into the codebase (4-6 hours)
---
## TL;DR
Forge multiplayer was crashing with 3+ players because messages arrived out of order.
**What Phase 3 Does:**
1. Message ordering (guaranteed order delivery)
2. Flow control (prevents overwhelming slow clients)
3. Network monitoring (see what's happening)
**What You Need to Do:**
1. Read: PHASE3_QUICK_REFERENCE.md (10 min)
2. Read: PHASE3_INTEGRATION_GUIDE.md (30 min)
3. Integrate: Follow guide steps (4-6 hours)
4. Test: 2-4 player games work (1-2 hours)
**Time:** 5-7 hours total
---
## The 3-Player Bug (What We're Fixing)
### The Problem
When 3+ players play online:
```
Server sends: Message1, Message2, Message3
Network delays them
Client receives: Message3, Message1, Message2
Game processes wrong order:
  - Message 3: "Tap that token" ← But token doesn't exist yet!
  - Message 1: "Create token" ← Too late!
  - CRASH!
```
### The Solution
Add sequence numbers:
```
Message1 (seq=0)
Message2 (seq=1)
Message3 (seq=2)
Even if received as 3,1,2:
Client reorders to 1,2,3
Game processes correctly ✓
```
---
## Phase 3 Components (Already Done)
### Component 1: SequencedNetEvent
Wraps every message with a sequence number
### Component 2: MessageSequencer
Server assigns sequence numbers
### Component 3: ClientMessageBuffer
Client reorders messages by sequence number
### Component 4: MessageAckEvent
Client confirms it received messages
### Component 5: FlowController (NEW)
Prevents server from overwhelming slow client
### Component 6: MessageMetrics (NEW)
Monitors network health per player
---
## What You're Integrating
### Into FServerManager.java
```
Add:
  - messageSequencer field
  - flowController field
  - messageMetrics field
Modify:
  - broadcastTo() to sequence messages
  - broadcastTo() to check flow control
  - broadcastTo() to record metrics
Add method:
  - handleMessageAck() to process ACKs
```
### Into GameClientHandler.java
```
Add:
  - messageBuffer field
Modify:
  - channelRead() to buffer messages
  - channelRead() to deliver in order
  - channelRead() to send ACKs
```
**That's it!** Just two files to modify.
---
## Step-by-Step
### Step 1: Understand the Components (30 min)
Read: **PHASE3_QUICK_REFERENCE.md**
This shows you:
- What each component does
- How to use it
- Code examples
### Step 2: Get the Integration Plan (30 min)
Read: **PHASE3_INTEGRATION_GUIDE.md**
This shows you:
- Exactly what to change
- Code samples to copy
- How to test
### Step 3: Integrate (4-6 hours)
Follow: **PHASE3_INTEGRATION_GUIDE.md**
Do:
1. Step 2: Modify FServerManager
2. Step 4: Modify GameClientHandler
3. Step 7: Test with 2-4 player games
### Step 4: Validate (1-2 hours)
Test:
- 2-player local game (works)
- 3-player online game (works)
- 4-player online game (works)
- Messages arrive in order
- Metrics print correctly
---
## Files You Need
### Read First
1. **PHASE3_QUICK_REFERENCE.md** ← Start here
2. **PHASE3_INTEGRATION_GUIDE.md** ← Follow this
### Read for Reference
3. **PHASE3_WORK_REMAINING.md** ← Task priority
4. **PHASE3_COMPLETION_STATUS.md** ← What's done
### Read for Context (Optional)
5. **PHASE3_IMPLEMENTATION_SUMMARY.md** ← Deep dive
6. **PHASE3_INDEX.md** ← Complete reference
---
## Key Files to Modify
### File 1: FServerManager.java
**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java`
**What to add:**
```java
private MessageSequencer messageSequencer;
private FlowController flowController;
private MessageMetrics messageMetrics;
```
**What to modify:**
```java
// In broadcastTo():
SequencedNetEvent seqEvent = messageSequencer.sequenceMessage(event, playerId, true);
if (!flowController.canSendMessage(playerId)) return;
to.send(seqEvent);
flowController.markMessageSent(playerId);
messageMetrics.recordMessageSent(playerId, 256);
```
See PHASE3_INTEGRATION_GUIDE.md Step 2 for full code.
### File 2: GameClientHandler.java
**Location:** `forge-gui/src/main/java/forge/gamemodes/net/client/GameClientHandler.java`
**What to add:**
```java
private ClientMessageBuffer messageBuffer;
```
**What to modify:**
```java
// In channelRead():
messageBuffer.addMessage(seqEvent);
List<NetEvent> ordered = messageBuffer.getOrderedMessages(senderId);
for (NetEvent event : ordered) {
    handleGameEvent(event);
}
// Send ACK back
```
See PHASE3_INTEGRATION_GUIDE.md Step 4 for full code.
---
## Testing Checklist
### Must Test
- [ ] 2-player local game works
- [ ] 3-player online game works
- [ ] Messages arrive in order
- [ ] No crashes
### Should Test
- [ ] 4-player game works
- [ ] Metrics are accurate
- [ ] No memory leaks
### Nice to Test
- [ ] High latency (500ms) works
- [ ] Message loss handled gracefully
- [ ] 1-hour session works
---
## If You Get Stuck
### Compilation Error?
See: PHASE3_INTEGRATION_GUIDE.md "Troubleshooting" section
### Don't understand a component?
Read: PHASE3_QUICK_REFERENCE.md (has code examples)
### Don't know what to change?
Follow: PHASE3_INTEGRATION_GUIDE.md exactly (step-by-step)
### Test failing?
Check: PHASE3_INTEGRATION_GUIDE.md Step 7 (testing guide)
---
## Timeline
| Task | Time |
|------|------|
| Understand (read docs) | 1 hour |
| Integrate (code changes) | 4-6 hours |
| Test (local + online) | 1-2 hours |
| **TOTAL** | **5-7 hours** |
**Target:** Complete by Feb 19, 2026
---
## Current Status
✅ **Phase 1:** Connection state management - COMPLETE
✅ **Phase 2:** Thread safety & backpressure - COMPLETE
✅ **Phase 3A:** Components created - COMPLETE (YOU ARE HERE)
⏳ **Phase 3B:** Components integrated - PENDING (your job)
⏳ **Phase 3C:** Testing & validation - PENDING
⏳ **Phase 3D:** Polish & deployment - PENDING
---
## Success
You'll know it's working when:
✅ Project compiles without errors  
✅ 2-player game works, messages in order  
✅ 3-player game works, messages in order  
✅ 4-player game works  
✅ Metrics print to console  
✅ No crashes  
---
## Ready?
1. Open PHASE3_QUICK_REFERENCE.md
2. Spend 10 minutes reading
3. Open PHASE3_INTEGRATION_GUIDE.md
4. Spend 30 minutes planning
5. Start coding!
**Estimated completion:** 5-7 hours (by tomorrow)
---
**Started:** February 18, 2026  
**Your goal:** Phase 3B integration complete  
**Estimated:** February 19, 2026  
**Status:** All materials ready, waiting for you to start
