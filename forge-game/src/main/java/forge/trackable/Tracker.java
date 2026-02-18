package forge.trackable;

import java.util.List;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;

import forge.trackable.TrackableTypes.TrackableType;

public class Tracker {
    private int freezeCounter = 0;
    private final Object freezeLock = new Object();  // Lock for freeze operations
    private final List<DelayedPropChange> delayedPropChanges = Lists.newArrayList();

    private final Table<TrackableType<?>, Integer, Object> objLookups = HashBasedTable.create();

    public final boolean isFrozen() {
        synchronized (freezeLock) {
            return freezeCounter > 0;
        }
    }

    public void freeze() {
        synchronized (freezeLock) {
            freezeCounter++;
        }
    }

    // Note: objLookups exist on the tracker and not on the TrackableType because
    // TrackableType is global and Tracker is per game.
    @SuppressWarnings("unchecked")
    public <T> T getObj(TrackableType<T> type, Integer id) {
        synchronized (objLookups) {
            return (T)objLookups.get(type, id);
        }
    }

    public boolean hasObj(TrackableType<?> type, Integer id) {
        synchronized (objLookups) {
            return objLookups.contains(type, id);
        }
    }

    public <T> void putObj(TrackableType<T> type, Integer id, T val) {
        synchronized (objLookups) {
            objLookups.put(type, id, val);
        }
    }

    public void unfreeze() {
        synchronized (freezeLock) {
            if (!isFrozen() || --freezeCounter > 0) {
                return;
            }
        }

        // Process delayed changes outside of lock to avoid deadlocks
        synchronized (delayedPropChanges) {
            if (delayedPropChanges.isEmpty()) {
                return;
            }
            //after being unfrozen, ensure all changes delayed during freeze are now applied
            for (final DelayedPropChange change : delayedPropChanges) {
                change.object.set(change.prop, change.value);
            }
            delayedPropChanges.clear();
        }
    }

    public void flush() {
        // unfreeze and refreeze the tracker in order to flush current pending properties
        synchronized (freezeLock) {
            if (freezeCounter == 0) {
                return;
            }
        }
        unfreeze();
        freeze();
    }

    public void addDelayedPropChange(final TrackableObject object, final TrackableProperty prop, final Object value) {
        synchronized (delayedPropChanges) {
            delayedPropChanges.add(new DelayedPropChange(object, prop, value));
        }
    }

    public void clearDelayed() {
        synchronized (delayedPropChanges) {
            delayedPropChanges.clear();
        }
    }

    private class DelayedPropChange {
        private final TrackableObject object;
        private final TrackableProperty prop;
        private final Object value;
        private DelayedPropChange(final TrackableObject object0, final TrackableProperty prop0, final Object value0) {
            object = object0;
            prop = prop0;
            value = value0;
        }
        @Override public String toString() {
            return "Set " + prop + " of " + object + " to " + value;
        }
    }
}
