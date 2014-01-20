/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.db;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;

import org.apache.log4j.Logger;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.filter.IdentityQueryFilter;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.io.SSTableDeletingReference;
import org.apache.cassandra.io.SSTableReader;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.CopyOnWriteMap;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.WrappedRunnable;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

public class Table 
{
    public static final String SYSTEM_TABLE = "system";

    private static final Logger logger = Logger.getLogger(Table.class);
    private static final String SNAPSHOT_SUBDIR_NAME = "snapshots";
    /**
     * accesses to CFS.memtable should acquire this for thread safety.
     * Table.maybeSwitchMemtable should aquire the writeLock; see that method for the full explanation.
     *
     * (Enabling fairness in the RRWL is observed to decrease throughput, so we leave it off.)
     */
    static final ReentrantReadWriteLock flusherLock = new ReentrantReadWriteLock();

    private static Timer flushTimer = new Timer("FLUSH-TIMER");
    private final boolean waitForCommitLog;
    
    // This is a result of pushing down the point in time when storage directories get created.  It used to happen in
    // CassandraDaemon, but it is possible to call Table.open without a running daemon, so it made sense to ensure
    // proper directories here.
    static
    {
        try
        {
            DatabaseDescriptor.createAllDirectories();
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    /*
     * This class represents the metadata of this Table. The metadata
     * is basically the column family name and the ID associated with
     * this column family. We use this ID in the Commit Log header to
     * determine when a log file that has been rolled can be deleted.
    */
    public static class TableMetadata
    {
        private static HashMap<String,TableMetadata> tableMetadataMap = new HashMap<String,TableMetadata>();
        private static Map<Integer, String> idCfMap_ = new HashMap<Integer, String>();

        static
        {
            try
            {
                DatabaseDescriptor.storeMetadata();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public static synchronized Table.TableMetadata instance(String tableName) throws IOException
        {
            if ( tableMetadataMap.get(tableName) == null )
            {
                tableMetadataMap.put(tableName, new Table.TableMetadata());
            }
            return tableMetadataMap.get(tableName);
        }

        /* The mapping between column family and the column type. */
        private Map<String, String> cfTypeMap_ = new HashMap<String, String>();
        private Map<String, Integer> cfIdMap_ = new HashMap<String, Integer>();

        public void add(String cf, int id)
        {
            add(cf, id, "Standard");
        }
        
        public void add(String cf, int id, String type)
        {
            if (logger.isDebugEnabled())
              logger.debug("adding " + cf + " as " + id);
            assert !idCfMap_.containsKey(id);
            cfIdMap_.put(cf, id);
            idCfMap_.put(id, cf);
            cfTypeMap_.put(cf, type);
        }
        
        public boolean isEmpty()
        {
            return cfIdMap_.isEmpty();
        }

        int getColumnFamilyId(String columnFamily)
        {
            return cfIdMap_.get(columnFamily);
        }

        public static String getColumnFamilyName(int id)
        {
            return idCfMap_.get(id);
        }
        
        String getColumnFamilyType(String cfName)
        {
            return cfTypeMap_.get(cfName);
        }

        Set<String> getColumnFamilies()
        {
            return cfIdMap_.keySet();
        }
        
        int size()
        {
            return cfIdMap_.size();
        }
        
        boolean isValidColumnFamily(String cfName)
        {
            return cfIdMap_.containsKey(cfName);
        }

        public String toString()
        {
            return "TableMetadata(" + FBUtilities.mapToString(cfIdMap_) + ")";
        }

        public static int getColumnFamilyCount()
        {
            return idCfMap_.size();
        }

        public static String getColumnFamilyIDString()
        {
            return FBUtilities.mapToString(tableMetadataMap);
        }
    }

    /** Table objects, one per keyspace.  only one instance should ever exist for any given keyspace. */
    private static final Map<String, Table> instances = new NonBlockingHashMap<String, Table>();

    /* Table name. */
    public final String name;
    /* Handle to the Table Metadata */
    private final Table.TableMetadata tableMetadata;
    /* ColumnFamilyStore per column family */
    private final Map<String, ColumnFamilyStore> columnFamilyStores = new HashMap<String, ColumnFamilyStore>();
    /**
     * Key: ColumnFamilyStore
     * Value: its listener
     */
    private Map<String, IStoreApplyListener> storeFilters = null;

    public static Table open(String table) throws IOException
    {
        Table tableInstance = instances.get(table);
        if (tableInstance == null)
        {
            // instantiate the Table.  we could use putIfAbsent but it's important to making sure it is only done once
            // per keyspace, so we synchronize and re-check before doing it.
            synchronized (Table.class)
            {
                tableInstance = instances.get(table);
                if (tableInstance == null)
                {
                    tableInstance = new Table(table);
                    instances.put(table, tableInstance);

                    //table has to be constructed and in the cache before cacheRow can be called
                    for (ColumnFamilyStore cfs : tableInstance.getColumnFamilyStores())
                        cfs.initRowCache();
                }
            }
        }
        return tableInstance;
    }
    
    public static ColumnFamilyStore getColumnFamilyStore(int cfid) {
        String cfName = TableMetadata.getColumnFamilyName(cfid);
        for (Table t : Table.all()) {
            ColumnFamilyStore store = t.getColumnFamilyStore(cfName);
            if ( store != null )
                return store;
        }
        
        throw new IllegalArgumentException("Cannot find column family with id="+cfid);
    }

    public Set<String> getColumnFamilies()
    {
        return tableMetadata.getColumnFamilies();
    }

    public Collection<ColumnFamilyStore> getColumnFamilyStores()
    {
        return Collections.unmodifiableCollection(columnFamilyStores.values());
    }

    public ColumnFamilyStore getColumnFamilyStore(String cfName)
    {
        return columnFamilyStores.get(cfName);
    }

    /**
     * Do a cleanup of keys that do not belong locally.
     */
    public void forceCleanup()
    {
        if (name.equals(SYSTEM_TABLE))
            throw new UnsupportedOperationException("Cleanup of the system table is neither necessary nor wise");

        // Sort the column families in order of SSTable size, so cleanup of smaller CFs
        // can free up space for larger ones
        List<ColumnFamilyStore> sortedColumnFamilies = new ArrayList<ColumnFamilyStore>(columnFamilyStores.values());
        Collections.sort(sortedColumnFamilies, new Comparator<ColumnFamilyStore>()
        {
            // Compare first on size and, if equal, sort by name (arbitrary & deterministic).
            public int compare(ColumnFamilyStore cf1, ColumnFamilyStore cf2)
            {
                long diff = (cf1.getTotalDiskSpaceUsed() - cf2.getTotalDiskSpaceUsed());
                if (diff > 0)
                    return 1;
                if (diff < 0)
                    return -1;
                return cf1.columnFamily_.compareTo(cf2.columnFamily_);
            }
        });

        // Cleanup in sorted order to free up space for the larger ones
        for (ColumnFamilyStore cfs : sortedColumnFamilies)
            cfs.forceCleanup();
    }
    
    
    /**
     * Take a snapshot of the entire set of column families with a given timestamp.
     * 
     * @param clientSuppliedName the tag associated with the name of the snapshot.  This
     *                           value can be null.
     */
    public void snapshot(String clientSuppliedName) throws IOException
    {
        String snapshotName = Long.toString(System.currentTimeMillis());
        if (clientSuppliedName != null && !clientSuppliedName.equals(""))
        {
            snapshotName = snapshotName + "-" + clientSuppliedName;
        }

        for (ColumnFamilyStore cfStore : columnFamilyStores.values())
        {
            cfStore.snapshot(snapshotName);
        }
        
        submitArchiveSnapshot();
    }

    private Future<?> submitArchiveSnapshot()
    {
        if (DatabaseDescriptor.isDataArchiveEnabled())
        {
            return StageManager.getStage(StageManager.SNAPSHOT_ARCHIVE_STAGE).submit(
                    new WrappedRunnable()
                    {

                        @Override
                        protected void runMayThrow() throws Exception
                        {
                            archiveSnapshot(false);
                        }
                    } 
                    );
            
        }
        
        return null;
    }

    private void forceBlockingArchiveSnapshot() throws IOException
    {
        if (DatabaseDescriptor.isDataArchiveEnabled())
        {
            archiveSnapshot(true);
        }
    }

    /*
     * Move snapshot files to separate archive disk
     */
    public void archiveSnapshot(boolean force) throws IOException
    {
        int chunkSize = 128*1024; // 128kbytes
        int chunksSec = force ? 10000*(1024/128) : DatabaseDescriptor.getDataArchiveThrottle()*(1024/128);
        
        for (String dataDirPath : DatabaseDescriptor.getAllDataFileLocations())
        {
            String snapshotPath = dataDirPath + File.separator + name + File.separator + SNAPSHOT_SUBDIR_NAME;
            File snapshotDir = new File(snapshotPath);
            if (snapshotDir.exists())
            {
                File archiveLocation = DatabaseDescriptor.getDataArchiveFileLocationForSnapshot(name);

                logger.info("Moving snapshot directory " + snapshotPath + " to " + archiveLocation + (force ? " (forced, blocking)" :""));

                if (DatabaseDescriptor.isDataArchiveHardLinksEnabled()) 
                {
                    long copiedAndLinked[] = FileUtils.copyDirLinkDublicates(
                            snapshotDir, archiveLocation, chunkSize, chunksSec);
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("Move of ");
                    stringBuilder.append(snapshotPath);
                    stringBuilder.append(" to ");
                    stringBuilder.append(archiveLocation);
                    stringBuilder.append(" completed. Copied ");
                    stringBuilder.append(FileUtils.stringifyFileSize(copiedAndLinked[0]));
                    stringBuilder.append(", linked ");
                    stringBuilder.append(FileUtils.stringifyFileSize(copiedAndLinked[1]));
                    stringBuilder.append(" .Removing snapshot directory ");
                    stringBuilder.append(snapshotPath);
                    logger.info(stringBuilder.toString());
                }
                else
                {
                    long copied = FileUtils.copyDir(snapshotDir, archiveLocation, chunkSize, chunksSec);

                    logger.info("Move of " + (copied / 1024 / 1024) + "MB to " + archiveLocation
                            + " completed. Removing snapshot directory " + snapshotPath);
                }
                FileUtils.deleteDir(snapshotDir);
            }
        }
    }

    /**
     * Clear all the snapshots for a given table.
     */
    public void clearSnapshot() throws IOException
    {
        for (String dataDirPath : DatabaseDescriptor.getAllDataFileLocations())
        {
            String snapshotPath = dataDirPath + File.separator + name + File.separator + SNAPSHOT_SUBDIR_NAME;
            File snapshotDir = new File(snapshotPath);
            if (snapshotDir.exists())
            {
                if (logger.isDebugEnabled())
                    logger.debug("Removing snapshot directory " + snapshotPath);
                FileUtils.deleteDir(snapshotDir);
            }
        }
    }

    /*
     * This method is invoked only during a bootstrap process. We basically
     * do a complete compaction since we can figure out based on the ranges
     * whether the files need to be split.
    */
    public List<String> forceAntiCompaction(Collection<Range> ranges, InetAddress target)
    {
        List<String> allResults = new ArrayList<String>();
        Set<String> columnFamilies = tableMetadata.getColumnFamilies();
        for ( String columnFamily : columnFamilies )
        {
            ColumnFamilyStore cfStore = columnFamilyStores.get( columnFamily );
            try
            {
                allResults.addAll(CompactionManager.instance.submitAnticompaction(cfStore, ranges, target).get());
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        return allResults;
    }
    
    /*
     * This method is an ADMIN operation to force compaction
     * of all SSTables on disk. 
    */
    public void forceCompaction()
    {
        Set<String> columnFamilies = tableMetadata.getColumnFamilies();
        for ( String columnFamily : columnFamilies )
        {
            ColumnFamilyStore cfStore = columnFamilyStores.get( columnFamily );
            if ( cfStore != null )
                CompactionManager.instance.submitMajor(cfStore);
        }
    }

    List<SSTableReader> getAllSSTablesOnDisk()
    {
        List<SSTableReader> list = new ArrayList<SSTableReader>();
        Set<String> columnFamilies = tableMetadata.getColumnFamilies();
        for ( String columnFamily : columnFamilies )
        {
            ColumnFamilyStore cfStore = columnFamilyStores.get( columnFamily );
            if ( cfStore != null )
                list.addAll(cfStore.getSSTables());
        }
        return list;
    }

    private Table(final String table) throws IOException
    {
        name = table;
        waitForCommitLog = DatabaseDescriptor.getCommitLogSync() == DatabaseDescriptor.CommitLogSync.batch;
        tableMetadata = Table.TableMetadata.instance(table);
        
        // MM : Speed up startup by parallelling all CF initializations
        int threads = Math.max( Runtime.getRuntime().availableProcessors(),  DatabaseDescriptor.getAllDataFileLocations().length);
        logger.info("Starting "+table+" keyspace using "+ threads+" threads");
        ExecutorService initExecutor = Executors.newFixedThreadPool(threads, new NamedThreadFactory("KS-INIT-"+table));

        List<Callable<ColumnFamilyStore>> tasks = new ArrayList<Callable<ColumnFamilyStore>>();
        for (final String columnFamily : tableMetadata.getColumnFamilies())
        {
//            columnFamilyStores.put(columnFamily, ColumnFamilyStore.createColumnFamilyStore(table, columnFamily));
            tasks.add(new Callable<ColumnFamilyStore>()
            {
                
                @Override
                public ColumnFamilyStore call() throws Exception
                {
                    return ColumnFamilyStore.createColumnFamilyStore(table, columnFamily);
                }
            });
        }
        
        try {
            List<Future<ColumnFamilyStore>> cfs = initExecutor.invokeAll(tasks);
            
            for (Future<ColumnFamilyStore> future : cfs) 
            {
                columnFamilyStores.put(future.get().getColumnFamilyName(), future.get());
            }
        } catch (Exception e1) {
            throw new RuntimeException(table+" initialization failure", e1);
        }
        
        initExecutor.shutdown();

        // if are noopied snapshots - do snapshot archive now
        forceBlockingArchiveSnapshot();

        // check 10x as often as the lifetime, so we can exceed lifetime by 10% at most
        int checkMs = DatabaseDescriptor.getMemtableLifetimeMS() / 10;
        flushTimer.schedule(new TimerTask()
        {
            public void run()
            {
                for (ColumnFamilyStore cfs : columnFamilyStores.values())
                {
                    try
                    {
                        cfs.forceFlushIfExpired();
                    }
                    catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }, checkMs, checkMs);
    }

    public int getColumnFamilyId(String columnFamily)
    {
        return tableMetadata.getColumnFamilyId(columnFamily);
    }

    /**
     * Selects the specified column family for the specified key.
    */
    @Deprecated // single CFs could be larger than memory
    public ColumnFamily get(String key, String cfName) throws IOException
    {
        ColumnFamilyStore cfStore = columnFamilyStores.get(cfName);
        assert cfStore != null : "Column family " + cfName + " has not been defined";
        return cfStore.getColumnFamily(new IdentityQueryFilter(key, new QueryPath(cfName)));
    }

    public Row getRow(QueryFilter filter) throws IOException
    {
        ColumnFamilyStore cfStore = columnFamilyStores.get(filter.getColumnFamilyName());
        ColumnFamily columnFamily = cfStore.getColumnFamily(filter);
        return new Row(filter.key, columnFamily);
    }

    /**
     * This method adds the row to the Commit Log associated with this table.
     * Once this happens the data associated with the individual column families
     * is also written to the column family store's memtable.
    */
    public void apply(RowMutation mutation, Object serializedMutation, boolean writeCommitLog) throws IOException
    {
        HashMap<ColumnFamilyStore,Memtable> memtablesToFlush = new HashMap<ColumnFamilyStore, Memtable>(2);
        RowMutation filteredMutation = null;
        if (storeFilters!=null)
        {
            // invoke listener prior critical section
            for (ColumnFamily columnFamily : mutation.getColumnFamilies())
            {
                IStoreApplyListener listener = storeFilters.get( columnFamily.name() );
                if (listener!=null)
                {
                    if (!listener.preapply(mutation.key(), columnFamily)){
                        //create copy for mutation to avoid ConcurrentModificationException in send threads
                        if (filteredMutation == null){
                            filteredMutation  = new RowMutation(mutation.getTable(), mutation.key(), new HashMap<String, ColumnFamily>(mutation.modifications_));
                        }
                        filteredMutation.modifications_.remove(columnFamily.name());
                    }
                }
            }
        }
        
        if (filteredMutation != null){
            //something was filtered
            mutation = filteredMutation;
            
            if (mutation.isEmpty()){
                return;
            }
            
            // rebuild the serialized mutation
            serializedMutation = mutation.getSerializedBuffer();
            
            
        }
        
       

        // write the mutation to the commitlog and memtables
        flusherLock.readLock().lock();
        try
        {
            if (writeCommitLog)
            {
                CommitLog.instance().add(mutation, serializedMutation);
            }

            for (ColumnFamily columnFamily : mutation.getColumnFamilies())
            {
                Memtable memtableToFlush;
                ColumnFamilyStore cfs = columnFamilyStores.get(columnFamily.name());
                if ((memtableToFlush=cfs.apply(mutation.key(), columnFamily)) != null)
                    memtablesToFlush.put(cfs, memtableToFlush);

                ColumnFamily cachedRow = cfs.getRawCachedRow(mutation.key());
                if (cachedRow != null)
                    cachedRow.addAll(columnFamily);
            }
        }
        finally
        {
            flusherLock.readLock().unlock();

        }


        
        // flush memtables that got filled up.  usually mTF will be empty and this will be a no-op
        for (Map.Entry<ColumnFamilyStore, Memtable> entry : memtablesToFlush.entrySet())
            entry.getKey().maybeSwitchMemtable(entry.getValue(), writeCommitLog);
    }

    public List<Future<?>> flush() throws IOException
    {
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (String cfName : columnFamilyStores.keySet())
        {
            Future<?> future = columnFamilyStores.get(cfName).forceFlush();
            if (future != null)
                futures.add(future);
        }
        return futures;
    }

    /**
     * Does flush memtables one by one and waits for process completion. This will take more time than
     * {@link #flush()} but is more gentle for running loaded system.
     * 
     * @return
     * @throws IOException
     */
    public void flushAndWait() 
    {
        for (String cfName : columnFamilyStores.keySet())
        {
            Future<?> future = columnFamilyStores.get(cfName).forceFlush();
            if ( future != null )
                try {
                    future.get();
                } catch (Exception e) {
                    throw new RuntimeException("Cannot flush "+cfName,e);
                }
        }
    }

    // for binary load path.  skips commitlog.
    void load(RowMutation rowMutation) throws IOException
    {
        String key = rowMutation.key();
                
        for (ColumnFamily columnFamily : rowMutation.getColumnFamilies())
        {
            Collection<IColumn> columns = columnFamily.getSortedColumns();
            for (IColumn column : columns)
            {
                ColumnFamilyStore cfStore = columnFamilyStores.get(new String(column.name(), "UTF-8"));
                cfStore.applyBinary(key, column.value());
            }
        }
    }


    public static String getSnapshotPath(String dataDirPath, String tableName, String snapshotName)
    {
        return dataDirPath + File.separator + tableName + File.separator + SNAPSHOT_SUBDIR_NAME + File.separator + snapshotName;
    }

    public static Iterable<Table> all()
    {
        Function<String, Table> transformer = new Function<String, Table>()
        {
            public Table apply(String tableName)
            {
                try
                {
                    return Table.open(tableName);
                }
                catch (IOException e)
                {
                    throw new FSReadError(e);
                }
            }
        };
        return Iterables.transform(DatabaseDescriptor.getTables(), transformer);
    }
    
    void setStoreListener(ColumnFamilyStore store, IStoreApplyListener listener)
    {
        assert storeFilters == null || storeFilters.get(store.columnFamily_)==null || listener==null;
        
        synchronized (this) {
            if (storeFilters==null)
                storeFilters = new CopyOnWriteMap<String, IStoreApplyListener>();
            
            storeFilters.put(store.columnFamily_,listener);
        }
    }
}
