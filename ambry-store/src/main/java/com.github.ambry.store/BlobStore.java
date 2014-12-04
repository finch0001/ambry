package com.github.ambry.store;

import com.codahale.metrics.Timer;
import com.github.ambry.config.StoreConfig;
import com.github.ambry.utils.FileLock;
import com.github.ambry.utils.Scheduler;
import com.github.ambry.utils.SystemTime;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.codahale.metrics.MetricRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;


/**
 * The blob store that controls the log and index
 */
public class BlobStore implements Store {

  private Log log;
  private PersistentIndex index;
  private final String dataDir;
  private final Scheduler scheduler;
  private Logger logger = LoggerFactory.getLogger(getClass());
  /* A lock that prevents concurrent writes to the log */
  private Object lock = new Object();
  private boolean started;
  private StoreConfig config;
  private long capacityInBytes;
  private static String LockFile = ".lock";
  private FileLock fileLock;
  private StoreKeyFactory factory;
  private MessageStoreRecovery recovery;
  private StoreMetrics metrics;

  public BlobStore(StoreConfig config, Scheduler scheduler, MetricRegistry registry, String dataDir,
      long capacityInBytes, StoreKeyFactory factory, MessageStoreRecovery recovery) {
    this.metrics = new StoreMetrics(dataDir, registry);
    this.dataDir = dataDir;
    this.scheduler = scheduler;
    this.config = config;
    this.capacityInBytes = capacityInBytes;
    this.factory = factory;
    this.recovery = recovery;
  }

  @Override
  public void start()
      throws StoreException {
    synchronized (lock) {
      if (started) {
        throw new StoreException("Store already started", StoreErrorCodes.Store_Already_Started);
      }
      final Timer.Context context = metrics.storeStartTime.time();
      try {
        // Check if the data dir exist. If it does not exist, create it
        File dataFile = new File(dataDir);
        if (!dataFile.exists()) {
          logger.info("Store : {} data directory not found. creating it", dataDir);
          boolean created = dataFile.mkdir();
          if (!created) {
            throw new StoreException("Failed to create directory for data dir " + dataDir,
                StoreErrorCodes.Initialization_Error);
          }
        }
        if (!dataFile.isDirectory() || !dataFile.canRead()) {
          throw new StoreException(dataFile.getAbsolutePath() + " is either not a directory or is not readable",
              StoreErrorCodes.Initialization_Error);
        }

        // lock the directory
        fileLock = new FileLock(new File(dataDir, LockFile));
        if (!fileLock.tryLock()) {
          throw new StoreException("Failed to acquire lock on file " + dataDir +
              ". Another process or thread is using this directory.", StoreErrorCodes.Initialization_Error);
        }
        log = new Log(dataDir, capacityInBytes, metrics);
        index = new PersistentIndex(dataDir, scheduler, log, config, factory, recovery, metrics);
        // set the log end offset to the recovered offset from the index after initializing it
        log.setLogEndOffset(index.getCurrentEndOffset());
        metrics.initializeCapacityUsedMetric(log, capacityInBytes);
        started = true;
      } catch (Exception e) {
        throw new StoreException("Error while starting store for dir " + dataDir, e,
            StoreErrorCodes.Initialization_Error);
      } finally {
        context.stop();
      }
    }
  }

  @Override
  public StoreInfo get(List<? extends StoreKey> ids, EnumSet<StoreGetOptions> storeGetOptions)
      throws StoreException {
    checkStarted();
    // allows concurrent gets
    final Timer.Context context = metrics.getResponse.time();
    try {
      List<BlobReadOptions> readOptions = new ArrayList<BlobReadOptions>(ids.size());
      Map<StoreKey, MessageInfo> indexMessages = new HashMap<StoreKey, MessageInfo>(ids.size());
      for (StoreKey key : ids) {
        BlobReadOptions readInfo = index.getBlobReadInfo(key, storeGetOptions);
        readOptions.add(readInfo);
        indexMessages.put(key, new MessageInfo(key, readInfo.getSize(), readInfo.getTTL()));
      }

      MessageReadSet readSet = log.getView(readOptions);
      // We ensure that the metadata list is ordered with the order of the message read set view that the
      // log provides. This ensures ordering of all messages across the log and metadata from the index.
      List<MessageInfo> messageInfoList = new ArrayList<MessageInfo>(readSet.count());
      for (int i = 0; i < readSet.count(); i++) {
        messageInfoList.add(indexMessages.get(readSet.getKeyAt(i)));
      }
      return new StoreInfo(readSet, messageInfoList);
    } catch (StoreException e) {
      throw e;
    } catch (IOException e) {
      throw new StoreException("IO error while trying to fetch blobs from store " + dataDir, e,
          StoreErrorCodes.IOError);
    } catch (Exception e) {
      throw new StoreException("Unknown exception while trying to fetch blobs from store " + dataDir, e,
          StoreErrorCodes.Unknown_Error);
    } finally {
      context.stop();
    }
  }

  @Override
  public void put(MessageWriteSet messageSetToWrite)
      throws StoreException {
    checkStarted();
    final Timer.Context context = metrics.putResponse.time();
    try {
      if (messageSetToWrite.getMessageSetInfo().size() == 0) {
        throw new IllegalArgumentException("Message write set cannot be empty");
      }
      // if any of the keys alreadys exist in the store, we fail
      for (MessageInfo info : messageSetToWrite.getMessageSetInfo()) {
        if (index.exists(info.getStoreKey())) {
          throw new StoreException("Key already exist in store. cannot be overwritten", StoreErrorCodes.Already_Exist);
        }
      }
      synchronized (lock) {
        long writeStartOffset = log.getLogEndOffset();
        messageSetToWrite.writeTo(log);
        logger.trace("Store : {} message set written to log", dataDir);
        List<MessageInfo> messageInfo = messageSetToWrite.getMessageSetInfo();
        ArrayList<IndexEntry> indexEntries = new ArrayList<IndexEntry>(messageInfo.size());
        for (MessageInfo info : messageInfo) {
          IndexValue value = new IndexValue(info.getSize(), writeStartOffset, (byte) 0, info.getExpirationTimeInMs());
          IndexEntry entry = new IndexEntry(info.getStoreKey(), value);
          indexEntries.add(entry);
          writeStartOffset += info.getSize();
        }
        FileSpan fileSpan = new FileSpan(indexEntries.get(0).getValue().getOffset(), log.getLogEndOffset());
        index.addToIndex(indexEntries, fileSpan);
        logger.trace("Store : {} message set written to index ", dataDir);
      }
    } catch (StoreException e) {
      throw e;
    } catch (IOException e) {
      throw new StoreException("IO error while trying to put blobs to store " + dataDir, e, StoreErrorCodes.IOError);
    } catch (Exception e) {
      throw new StoreException("Unknown error while trying to put blobs to store " + dataDir, e,
          StoreErrorCodes.Unknown_Error);
    } finally {
      context.stop();
    }
  }

  @Override
  public void delete(MessageWriteSet messageSetToDelete)
      throws StoreException {
    checkStarted();
    final Timer.Context context = metrics.deleteResponse.time();
    try {
      List<MessageInfo> infoList = messageSetToDelete.getMessageSetInfo();
      for (MessageInfo info : infoList) {
        IndexValue value = index.findKey(info.getStoreKey());
        if (value == null) {
          throw new StoreException("Cannot delete id " + info.getStoreKey() + " since it is not present in the index.",
              StoreErrorCodes.ID_Not_Found);
        } else if (value.isFlagSet(IndexValue.Flags.Delete_Index)) {
          throw new StoreException(
              "Cannot delete id " + info.getStoreKey() + " since it is already deleted in the index.",
              StoreErrorCodes.ID_Deleted);
        }
      }
      synchronized (lock) {
        messageSetToDelete.writeTo(log);
        logger.trace("Store : {} delete mark written to log", dataDir);
        for (MessageInfo info : infoList) {
          FileSpan fileSpan = new FileSpan(log.getLogEndOffset() - info.getSize(), log.getLogEndOffset());
          index.markAsDeleted(info.getStoreKey(), fileSpan);
        }
        logger.trace("Store : {} delete has been marked in the index ", dataDir);
      }
    } catch (StoreException e) {
      throw e;
    } catch (IOException e) {
      throw new StoreException("IO error while trying to delete blobs from store " + dataDir, e,
          StoreErrorCodes.IOError);
    } catch (Exception e) {
      throw new StoreException("Unknown error while trying to delete blobs from store " + dataDir, e,
          StoreErrorCodes.Unknown_Error);
    } finally {
      context.stop();
    }
  }

  @Override
  public FindInfo findEntriesSince(FindToken token, long maxTotalSizeOfEntries)
      throws StoreException {
    checkStarted();
    final Timer.Context context = metrics.findEntriesSinceResponse.time();
    try {
      return index.findEntriesSince(token, maxTotalSizeOfEntries);
    } finally {
      context.stop();
    }
  }

  @Override
  public Set<StoreKey> findMissingKeys(List<StoreKey> keys)
      throws StoreException {
    checkStarted();
    final Timer.Context context = metrics.findMissingKeysResponse.time();
    try {
      return index.findMissingKeys(keys);
    } finally {
      context.stop();
    }
  }

  @Override
  public boolean isKeyDeleted(StoreKey key)
      throws StoreException {
    checkStarted();
    final Timer.Context context = metrics.isKeyDeletedResponse.time();
    try {
      IndexValue value = index.findKey(key);
      if (value == null) {
        throw new StoreException("Key " + key + " not found in store. Cannot check if it is deleted",
            StoreErrorCodes.ID_Not_Found);
      }
      return value.isFlagSet(IndexValue.Flags.Delete_Index);
    } finally {
      context.stop();
    }
  }

  @Override
  public long getSizeInBytes() {
    return log.getLogEndOffset();
  }

  @Override
  public void shutdown()
      throws StoreException {
    synchronized (lock) {
      checkStarted();
      try {
        logger.info("Store : " + dataDir + " shutting down");
        index.close();
        log.close();
        started = false;
      } catch (Exception e) {
        logger.error("Store : " + dataDir + " shutdown of store failed for directory ", e);
      } finally {
        try {
          fileLock.destroy();
        } catch (IOException e) {
          logger.error("Store : " + dataDir + " IO Exception while trying to close the file lock", e);
        }
      }
    }
  }

  private void checkStarted()
      throws StoreException {
    if (!started) {
      throw new StoreException("Store not started", StoreErrorCodes.Store_Not_Started);
    }
  }
}
