package com.nexusrmm.sync;

/** Delivers one batch to the destination. Implementations must never report success without a real acknowledgement. */
public interface SyncClient {

    /**
     * @return the destination's acknowledgement
     * @throws SyncTransportException for any failure (connection, timeout, HTTP error, invalid response)
     */
    SyncAck send(SyncBatch batch) throws SyncTransportException;
}
