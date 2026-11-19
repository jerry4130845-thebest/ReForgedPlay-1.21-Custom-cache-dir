package com.replaymod.core.files;

import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.IOException;

/** Service ownership ends only on a real adapter release, not on a deferred close request. */
public class ManagedReplayFile extends DelegatingReplayFile {
    private Runnable onClose;
    private final boolean releaseAware;
    private final TransactionalReplayFile transactional;

    public ManagedReplayFile(ReplayFile delegate, Runnable onClose) {
        super(delegate);
        this.onClose = onClose;
        transactional = delegate instanceof TransactionalReplayFile adapter ? adapter : null;
        releaseAware = transactional != null;
        if (transactional != null) transactional.onReleased(this::released);
    }

    private synchronized void released() {
        if (onClose == null) return;
        Runnable callback = onClose;
        onClose = null;
        callback.run();
    }

    /** Actual receipt for callers which must move a closed replay, never merely a close request. */
    public boolean isReleased() {
        if (transactional != null) return transactional.isReleased();
        synchronized (this) { return onClose == null; }
    }

    public java.nio.file.Path recoveryRecord() throws IOException {
        if (transactional == null) throw new IOException("No transactional recovery record");
        return transactional.recoveryRecord();
    }

    public void preserveRecovery(Throwable failure) {
        if (transactional != null) transactional.recoveryFailed(failure);
    }

    public int recoverPacketData() throws IOException {
        if (transactional == null) throw new IOException("Recovery requires a transactional workspace");
        return transactional.recoverPacketData();
    }

    @Override public void close() throws IOException {
        super.close();
        if (!releaseAware) released();
    }
}
