package org.apache.kylin.common.lock;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;

public interface DistributedLock extends Closeable {

    boolean lockWithName(String name, String serverName);

    boolean isHasLocked(String segmentId);

    void unlockWithName(String name);

    void watchLock(ExecutorService pool, DoWatchLock doWatch);

    public interface DoWatchLock {
        void doWatch(String path, String data);
    }
}