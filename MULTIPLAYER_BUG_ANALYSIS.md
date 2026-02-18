# Forge Multiplayer Bug Analysis: 3+ Player Crashes

## Executive Summary

After analyzing the Forge multiplayer networking and game state synchronization code, I've identified several critical bugs and design issues that cause crashes and instability when 3 or more players join online games. These issues center around **thread safety, message ordering, and race conditions** in the network protocol implementation.

---

## Critical Issues Found

### 1. **Race Condition in `RemoteClient.send()` with `.sync()` Calls**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java:40-44`

**Problem:**
```java
@Override
public void send(final NetEvent event) {
    System.out.println("Sending event " + event + " to " + channel);
    try {
        channel.writeAndFlush(event).sync();  // ← BLOCKING CALL
    } catch (Exception e) {
        e.printStackTrace();
    }
}
```

The `channel.writeAndFlush(event).sync()` call blocks the Netty event loop thread until the write completes. When broadcasting to multiple players (3+), this causes:

- **Deadlocks**: Multiple threads waiting on each other
- **Message ordering violations**: Earlier messages may not arrive before later ones due to blocking
- **Thread starvation**: The Netty worker threads become blocked, preventing other connections from being processed
- **Cascade failures**: One slow client blocks all others

**Why it's worse with 3+ players:** With 2 players (host + 1 client), there's minimal contention. With 3+, multiple broadcast operations queue up, each blocking, creating a cascade of delays and potential deadlocks.

**Recommended Fix:**
```java
@Override
public void send(final NetEvent event) {
    System.out.println("Sending event " + event + " to " + channel);
    channel.writeAndFlush(event).addListener(future -> {
        if (!future.isSuccess()) {
            future.cause().printStackTrace();
        }
    });
}
```

---

### 2. **Unprotected Concurrent Access to `ReplyPool`**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/ReplyPool.java`

**Problem:**
```java
private final Map<Integer, CompletableFuture> pool = Maps.newHashMap();  // ← NOT thread-safe!

public void initialize(final int index) {
    synchronized (pool) {
        pool.put(index, new CompletableFuture());
    }
}

public Object get(final int index) throws TimeoutException {
    final CompletableFuture future;
    synchronized (pool) {
        future = pool.get(index);
    }
    try {
        return future.get(5, TimeUnit.MINUTES);  // ← TIMEOUT AFTER 5 MINUTES!
    } catch (final InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
    }
}
```

Issues with this implementation:

1. **5-minute timeout is too aggressive**: Any delay longer than 5 minutes causes a `TimeoutException`. With 3+ players, network lag and message processing delays can exceed this.

2. **Race condition in `complete()` and `get()`**: Between fetching the future and calling `.get()`, the future could be removed or replaced. This can cause `NullPointerException`.

3. **Memory leak**: If a message reply is never received, the futures remain in the map permanently.

4. **Lack of per-player isolation**: A single slow/lagging player causes timeouts for ALL players waiting on replies.

5. **`cancelAll()` is too aggressive**: When converting a player to AI, calling `future.set(null)` on all futures breaks any player waiting for legitimate replies.

**Recommended Fixes:**
- Increase timeout based on number of players: `5 * playerCount` minutes
- Add cleanup mechanism for orphaned futures
- Add per-player timeout tracking
- Use a thread-safe concurrent map structure
- Make `cancelAll()` more selective (only cancel futures for that specific player)

---

### 3. **Broadcast Ordering Not Guaranteed Across Multiple Players**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java:217-225`

**Problem:**
```java
private void broadcastTo(final NetEvent event, final Iterable<RemoteClient> to) {
    for (final RemoteClient client : to) {
        broadcastTo(event, client);  // ← Calls synchronously for each client
    }
}

private void broadcastTo(final NetEvent event, final RemoteClient to) {
    event.updateForClient(to);
    to.send(event);  // ← send() blocks with .sync()
}
```

**Issues:**
- No guaranteed order of delivery to all clients
- If client N experiences network delay, all broadcasts wait
- State updates for different players can arrive out of order
- Game view updates might be inconsistent across players

**Why it matters with 3+ players:** The probability of message reordering increases with more clients. For example:
- Player 1 sends action A
- Player 2 sends action B  
- With 4+ players, these could arrive in different orders at different clients due to broadcast delays

---

### 4. **Non-Thread-Safe TrackableCollection Updates**

**Location:** `forge-game/src/main/java/forge/trackable/TrackableTypes.java:112-130`

**Problem:**
```java
@Override
protected void copyChangedProps(TrackableObject from, TrackableObject to, TrackableProperty prop) {
    // ...
    try {
        for (int i = 0; i < newCollection.size(); i++) {
            // Iterating and modifying collection without synchronization
            newCollection.remove(i);
            newCollection.add(i, existingObj);
        }
    } catch (IndexOutOfBoundsException e) {
        System.err.println("got an IndexOutOfBoundsException, trying to continue ...");
    }
}
```

**Issues:**
- `FCollection` (extends `ArrayList`-based list) is not thread-safe
- Multiple players receiving updates to the same collection simultaneously
- No synchronization on collection modifications
- `IndexOutOfBoundsException` is caught and silently ignored, causing data corruption
- Game thread updates collection while network thread sends it

**Why it's worse with 3+ players:** More game objects being tracked, more simultaneous updates, higher chance of concurrent modification.

---

### 5. **Tracker Freeze/Unfreeze Not Thread-Safe**

**Location:** `forge-game/src/main/java/forge/trackable/Tracker.java:11-65`

**Problem:**
```java
public class Tracker {
    private int freezeCounter = 0;  // ← No synchronization!
    private final List<DelayedPropChange> delayedPropChanges = Lists.newArrayList();  // ← Not thread-safe!
    private final Table<TrackableType<?>, Integer, Object> objLookups = HashBasedTable.create();  // ← Not thread-safe!

    public void freeze() {
        freezeCounter++;  // ← Race condition
    }

    public void unfreeze() {
        freezeCounter--;  // ← Could go negative!
    }
}
```

**Issues:**
- Non-atomic increment/decrement
- Multiple threads calling `freeze()`/`unfreeze()` simultaneously
- `freezeCounter` can become inconsistent (multiple unfreezes without matching freezes)
- `delayedPropChanges` list not protected, concurrent modification crashes
- `objLookups` (Guava `HashBasedTable`) not thread-safe

---

### 6. **Channel State Not Protected During Reconnect**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java:26-29`

**Problem:**
```java
public void swapChannel(final Channel newChannel) {
    this.channel = newChannel;
    this.replies = new ReplyPool();
}
```

Issues:
- No synchronization on channel swap
- Messages sent during swap could use old/new channel inconsistently
- Race between `send()` checking channel and `swapChannel()` updating it
- New `ReplyPool` created, but pending requests on old pool are lost

---

### 7. **Blocking Netty Send in GameProtocolSender**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/GameProtocolSender.java:20-27`

**Problem:**
```java
public <T> T sendAndWait(final ProtocolMethod method, final Object... args) {
    method.checkArgs(args);
    try {
        final Object returned = remote.sendAndWait(new GuiGameEvent(method, args));
        method.checkReturnValue(returned);
        return (T) returned;
    } catch (final TimeoutException e) {
        e.printStackTrace();
    }
    return null;
}
```

Issues:
- `sendAndWait()` uses 5-minute timeout
- Game thread blocks waiting for replies from remote players
- With 3+ players, if one player is slow, the game thread blocks
- No handling of cascading timeouts

---

### 8. **Missing Connection State Management**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java`

**Problem:**
The `RemoteClient` doesn't properly track connection state:
- No distinction between "connected", "disconnected", "reconnecting", "failed"
- `send()` throws exception if channel is null but doesn't check state first
- No backpressure handling when channel becomes unwritable
- Multiple simultaneous send attempts don't coordinate

**Why it matters:** With 3+ players:
- Network hiccups cause cascading failures
- One player's connection state affects others
- No graceful degradation

---

### 9. **EventLoopGroup Blocking in Client**

**Location:** `forge-gui/src/main/java/forge/gamemodes/net/client/FGameClient.java:46-76`

**Problem:**
```java
public void connect() {
    final EventLoopGroup group = new NioEventLoopGroup();
    try {
        // ...
        channel = b.connect(this.hostname, this.port).sync().channel();  // ← BLOCKS!
        final ChannelFuture ch = channel.closeFuture();
        new Thread(() -> {
            try {
                ch.sync();  // ← Blocks in separate thread
            } catch (final InterruptedException e) {
                // ...
            } finally {
                group.shutdownGracefully();
            }
        }).start();
    }
}
```

Issues:
- Initial `.sync()` can block indefinitely if server is down
- EventLoopGroup shutdown is deferred, not guaranteed
- No timeout on connect
- No proper error handling

---

### 10. **State Inconsistency Between Game and Network Threads**

**Location:** Multiple locations

**Problem:**
The game runs on one thread while the network handler runs on another:
- Game thread updates `TrackableObject` state
- Network thread reads state to send to clients
- No synchronization between reads and writes
- State updates may be incomplete when sent

Example scenario with 3+ players:
1. Game thread: Update card A's tapped state
2. Game thread: Update card A's mana ability
3. Network thread: Serialize and send card A's state to Player 1
4. Network thread: Send card A's state to Player 2 (different partial state!)
5. Result: Players see different game states

---

## Summary Table of Issues

| Issue | Severity | Scope | Affected Players |
|-------|----------|-------|-----------------|
| `.sync()` blocking in broadcast | **CRITICAL** | Netty server | 3+ |
| ReplyPool timeout too short | **HIGH** | Network protocol | 3+ |
| TrackableCollection race condition | **HIGH** | Game state | 3+ |
| Tracker freeze not thread-safe | **HIGH** | Game state | 3+ |
| Channel swap race condition | **HIGH** | Reconnect logic | 3+ |
| Missing connection state tracking | **MEDIUM** | Network resilience | 3+ |
| Blocking client connect | **MEDIUM** | Client startup | All |
| Broadcast ordering not guaranteed | **MEDIUM** | Game logic | 3+ |
| EventLoopGroup lifecycle | **MEDIUM** | Resource management | All |
| Thread-unsafe state updates | **HIGH** | Game consistency | 3+ |

---

## Recommended Action Plan

### Phase 1 (Critical - Do First)
1. **Remove `.sync()` calls** in `RemoteClient.send()` - use async listeners instead
2. **Add proper synchronization** to `Tracker` class using `synchronized` or `ConcurrentHashMap`
3. **Protect `TrackableCollection` access** with locks during concurrent updates
4. **Increase ReplyPool timeout** based on player count or add adaptive timeout

### Phase 2 (High Priority)
5. Implement proper connection state machine
6. Add per-player timeout tracking and monitoring
7. Make broadcast operations fully asynchronous
8. Add proper channel write buffer management and backpressure handling

### Phase 3 (Important)
9. Add comprehensive logging of network events for debugging
10. Implement message ordering guarantees
11. Add integration tests for 3+ player scenarios
12. Create thread safety analysis and document thread contracts

---

## Testing Recommendations

To verify fixes, create test scenarios:
1. **2 player game** - Baseline (should work)
2. **3 player game** - Most common failure case
3. **4+ player game** - Stress test
4. **High latency simulation** - Inject 500ms-2s delays
5. **Player disconnect/reconnect** - During active game
6. **Rapid state updates** - Many cards, many actions per turn

---

## Conclusion

The multiplayer crashes with 3+ players are caused by a combination of:
- **Incorrect use of Netty's blocking API** (`.sync()` in hot paths)
- **Non-thread-safe shared state** (Tracker, TrackableCollection)
- **Insufficient timeout handling** (5-minute hard limit)
- **Missing connection state management**

Fixing the top 4 issues should resolve most crash scenarios. Full thread-safety review and refactoring should be done as part of a broader multiplayer stability initiative.

