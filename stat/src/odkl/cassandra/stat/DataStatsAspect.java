/*
 * @(#) DataStatsAspect.java
 * Created Nov 4, 2011 by oleg
 * (C) ONE, SIA
 */
package odkl.cassandra.stat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.io.CompactionIterator;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

import one.log.util.LoggerUtil;

/**
 * This has background process which periodically collects data from internals of cassandra
 * 
 * @author Oleg Anastasyev<oa@hq.one.lv>
 *
 */
@Aspect
public class DataStatsAspect extends SystemArchitectureAspect implements Runnable
{
    private final Log log = LogFactory.getLog(getClass());

    private static final int MB = 1024 * 1024;

    /**
     * Statistic values are logged for each CF
     * 
     */
    enum Msg { 
        // mbytes of data in CF
        LoadMBytes, 
        // number of completed compactions, total compacted (read) Mbytes
        Compactions, CompactedMBytes, CompactedRows,
        
        // RowCacheHitRate, RowCacheSize, KeyCacheHitRate, KeyCacheSize, - these are not implemented at the moment

        // mean and max size of the row
        RowSize
    };
    
    private ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> execFuture = null;
    
    /**
     * Currently running compaction information.
     * If == null no compaction is currently running
     */
    private ColumnFamilyStore compactingStore = null;
    private CompactionIterator compactingIterator = null;
    private long compactionStartedMillis;
    
    @After("cassandraStart()")
    public void start()
    {
        execFuture  = exec.scheduleWithFixedDelay(this, 1, 1, TimeUnit.MINUTES);
        log.info("Statistics collection daemon started");
    }
    
    @Before("cassandraStop()")
    public void stop()
    {
        if (execFuture!=null)
        {
            execFuture.cancel(true);
            execFuture = null;
            log.info("Statistics collection daemon stopped");
        }
    }
    
    @After("compactionStartedPointcut(cfs,ci)")
    public void compactionStart(ColumnFamilyStore cfs,CompactionIterator ci)
    {
        compactingStore = cfs;
        compactingIterator = ci;
        compactionStartedMillis = System.currentTimeMillis();
    }

    @After("compactionCompletedPointcut()")
    public void compactionCompleted()
    {
        if (compactingStore==null)
            return;
        
        if (compactingIterator.getBytesRead()==0)
            return; // this was just check for possible compaction
        
        String clusterName = DatabaseDescriptor.getClusterName();
        String serverName = FBUtilities.getLocalAddress().getHostAddress();
        String cfNameForLogging = cfNameForLogging(compactingStore.getTable().name, compactingStore);
        long compactionTimeNanos = (System.currentTimeMillis() - compactionStartedMillis) * 1000000;
        
        LoggerUtil.operationsSuccess(OP_LOGGER_NAME, compactionTimeNanos, 1, "COMPACTION",
                clusterName, serverName, cfNameForLogging);
        LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.CompactedMBytes.name(),
                clusterName, serverName, cfNameForLogging, compactingIterator.getBytesRead() / MB);
        LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.CompactedRows.name(),
                clusterName, serverName, cfNameForLogging, compactingIterator.getRow());

        compactingStore = null;
        compactingIterator = null;
        compactionStartedMillis = 0;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run()
    {
        try {
            String clusterName = DatabaseDescriptor.getClusterName();
            String serverName = FBUtilities.getLocalAddress().getHostAddress();

            // Collect statistics for CassandraStat
            //streams pending, completed
            //(MAX seen in 5 minutes)
            //        LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.TPStream.name(), clusterName, serverName,null,streamStageMBean.getPendingTasks(),streamStageMBean.getCompletedTasks());


            // per CF statistics
            HashMap<String,long[]> loads = new HashMap<String, long[]>();
            for (String table : DatabaseDescriptor.getNonSystemTables())
                try {
                    for (ColumnFamilyStore cf : Table.open(table).getColumnFamilyStores() )
                    {
                        String columnFamilyName = cfNameForLogging(table, cf);

                        long[] array = loads.get(columnFamilyName);
                        if (array==null)
                            loads.put(columnFamilyName,array=new long[5]);

                        array[0]+=cf.getLiveDiskSpaceUsed();
                        array[1]+=cf.getMemtableDataSize();
                        array[2]+=cf.getMeanRowCompactedSize();
                        array[3]++;

                        array[4] = Math.max(array[4],cf.getMaxRowCompactedSize());

                        // row caches
                        //hits.label row cache hit rate (avg) and size (max seen)
                        //                JMXInstrumentedCacheMBean cache = caches.get(table+'.'+columnFamilyName+".row");
                        //                if (cache.getSize()>0)
                        //                {
                        //                    LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.RowCacheHitRate.name(), clusterName, serverName,columnFamilyName,cache.getRecentHitRate()*100);
                        //                    LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.RowCacheSize.name(), clusterName, serverName,columnFamilyName,cache.getSize());
                        //                }

                        // key caches
                        //hits.label key cache hit rate and size
                        //                cache = caches.get(table+'.'+columnFamilyName+".key");
                        //                if (cache.getSize()>0)
                        //                {
                        //                    LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.KeyCacheHitRate.name(), clusterName, serverName,columnFamilyName,cache.getRecentHitRate()*100);
                        //                    LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.KeyCacheSize.name(), clusterName, serverName,columnFamilyName,cache.getSize());
                        //                }
                    }
                } catch (IOException e) {
                    log.error("",e);
                }          

            for (Entry<String, long[]> en : loads.entrySet()) 
            {
                long[] array = en.getValue();
                String columnFamilyName = en.getKey();
                LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.LoadMBytes.name(),
                        clusterName, serverName, columnFamilyName, array[0] / MB, array[1] / MB);
                LoggerUtil.operationData(MSG_LOGGER_NAME, Msg.RowSize.name(),
                        clusterName, serverName, columnFamilyName, array[2] / array[3], array[4]);
            }
        } catch (Throwable e)
        {
            log.error("Cannot collect statistics data",e);
        }
    }

    private String cfNameForLogging(String table, ColumnFamilyStore cf)
    {
        String columnFamilyName = cf.getColumnFamilyName();
        
        CFMetaData data = DatabaseDescriptor.getCFMetaData(table, columnFamilyName);
        if (data.domainSplit)
            columnFamilyName = data.domainCFName;
        return columnFamilyName;
    }
}
