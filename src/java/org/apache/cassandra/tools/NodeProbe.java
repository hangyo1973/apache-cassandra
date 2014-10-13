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

package org.apache.cassandra.tools;

import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.cassandra.cache.JMXInstrumentedCacheMBean;
import org.apache.cassandra.concurrent.IExecutorMBean;
import org.apache.cassandra.db.ColumnFamilyStoreMBean;
import org.apache.cassandra.db.CompactionManager;
import org.apache.cassandra.db.CompactionManagerMBean;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.service.StorageServiceMBean;
import org.apache.cassandra.streaming.StreamingService;
import org.apache.cassandra.streaming.StreamingServiceMBean;
import org.apache.commons.logging.LogFactory;

/**
 * JMX client operations for Cassandra.
 */
public class NodeProbe
{
    private static final String fmtUrl = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi";
    private static final String ssObjName = "org.apache.cassandra.service:type=StorageService";
    private String host;
    private int port;
    private String username;
    private String password;

    private MBeanServerConnection mbeanServerConn;
    private StorageServiceMBean ssProxy;
    private MemoryMXBean memProxy;
    private RuntimeMXBean runtimeProxy;
    private CompactionManagerMBean mcmProxy;
    private StreamingServiceMBean streamProxy;
    
    /**
     * Creates a NodeProbe using the specified JMX host and port.
     * 
     * @param host hostname or IP address of the JMX agent
     * @param port TCP port of the remote JMX agent
     * @param username user name of the remote JMX agent
     * @param password password of the remote JMX agent
     * @throws IOException on connection failures
     */
    public NodeProbe(String host, int port, String username, String password) throws IOException, InterruptedException
    {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        connect();
    }
    
    /**
     * Creates a NodeProbe using the specified JMX host and default port.
     * 
     * @param host hostname or IP address of the JMX agent
     * @throws IOException on connection failures
     */
    public NodeProbe(String host) throws IOException, InterruptedException
    {
        this.host = host;
        this.port = defaultPort();
        
        readUsernameAndPassword();
        
        connect();
    }
    
    private static int defaultPort()
    {
        return Integer.parseInt(System.getProperty("jmx.agent.port","8080"));
    }
    
    private void readUsernameAndPassword()
    {
        String passwdFile = System.getProperty("com.sun.management.jmxremote.password.file");
        if ( passwdFile != null )
        {
            // there are credentials configured. reading 1st line
            try {
                LineNumberReader reader = new LineNumberReader(new FileReader(passwdFile));
                try {
                    String line = null;
                    do {
                        line = reader.readLine();
                    } while (line==null || line.trim().length()==0);

                    String[] cred = line.split("\\s+");

                    if (cred.length>1)
                    {
                        this.username = cred[0];
                        this.password = cred[1];
                    }
                } finally {
                    reader.close();
                }
            } catch (IOException e)
            {
                LogFactory.getLog(NodeProbe.class).error("Cannot read "+passwdFile+" for username and password to connect to "+this.host+". Trying to continue without credentials",e);
            }
        }
    }
    
    /**
     * Create a connection to the JMX agent and setup the M[X]Bean proxies.
     * 
     * @throws IOException on connection failures
     */
    private void connect() throws IOException
    {
        JMXServiceURL jmxUrl = new JMXServiceURL(String.format(fmtUrl, host, port));
        Map<String, Object> env = new HashMap<String, Object>();
        env.put(JMXConnector.CREDENTIALS, new String[] { username, password });
        JMXConnector jmxc = JMXConnectorFactory.connect(jmxUrl, env);
        mbeanServerConn = jmxc.getMBeanServerConnection();
        
        try
        {
            ObjectName name = new ObjectName(ssObjName);
            ssProxy = JMX.newMBeanProxy(mbeanServerConn, name, StorageServiceMBean.class);
            name = new ObjectName(CompactionManager.MBEAN_OBJECT_NAME);
            mcmProxy = JMX.newMBeanProxy(mbeanServerConn, name, CompactionManagerMBean.class);
            name = new ObjectName(StreamingService.MBEAN_OBJECT_NAME);
            streamProxy = JMX.newMBeanProxy(mbeanServerConn, name, StreamingServiceMBean.class);
        } catch (MalformedObjectNameException e)
        {
            throw new RuntimeException(
                    "Invalid ObjectName? Please report this as a bug.", e);
        }
        
        memProxy = ManagementFactory.newPlatformMXBeanProxy(mbeanServerConn, 
                ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        runtimeProxy = ManagementFactory.newPlatformMXBeanProxy(
                mbeanServerConn, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
    }

    public void forceTableCleanup() throws IOException
    {
        ssProxy.forceTableCleanup();
    }
    public void forceTableCleanup(String ks, String... columnFamilies) throws IOException
    {
        ssProxy.forceTableCleanup(ks, columnFamilies);
    }

    public void forceTableCompaction() throws IOException
    {
        ssProxy.forceTableCompaction();
    }
    public void forceTableCompaction(String ks, String... columnFamilies) throws IOException
    {
        ssProxy.forceTableCompaction(ks, columnFamilies);
    }

    public void forceTableFlush(String tableName, String... columnFamilies) throws IOException
    {
        ssProxy.forceTableFlush(tableName, columnFamilies);
    }

    public void forceTableRepair(String tableName, String... columnFamilies) throws IOException
    {
        ssProxy.forceTableRepair(tableName, columnFamilies);
    }
    
    public void drain() throws IOException, InterruptedException, ExecutionException
    {
        ssProxy.drain();	
    }
    
    public String gossipInfo()
    {
        return ssProxy.gossipInfo();
    }

    public void gossipStop()
    {
        ssProxy.gossipStop();
    }

    public void gossipStart()
    {
        ssProxy.gossipStart();
    }

    public void gossipPurgePersistent()
    {
        ssProxy.gossipPurgePersistent();
    }

    public void completeBootstrap() {
        ssProxy.completeBootstrap();
    }
    
    public Map<Range, List<String>> getRangeToEndPointMap(String tableName)
    {
        return ssProxy.getRangeToEndPointMap(tableName);
    }
    
    public Set<String> getLiveNodes()
    {
        return ssProxy.getLiveNodes();
    }
    

    /**
     * Write a textual representation of the Cassandra ring.
     * 
     * @param outs the stream to write to
     */
    public void printRing(PrintStream outs)
    {
        Map<Token, String> rangeMap = getPrettyRing();
        List<Token> ranges = new ArrayList<Token>(rangeMap.keySet());
        Collections.sort(ranges);
        Set<String> liveNodes = ssProxy.getLiveNodes();
        Set<String> deadNodes = ssProxy.getUnreachableNodes();
        Map<String, String> loadMap = ssProxy.getLoadMap();
        Map<String, String> locationsMap = ssProxy.getLocationsMap();
        Map<String, String> namesMap = ssProxy.getEndpointNames();

        // Print range-to-endpoint mapping
        int counter = 0;
        outs.print(String.format("%-24s", "Address"));
        outs.print(String.format("%-11s", "Status"));
        outs.print(String.format("%-14s", "Location"));
        outs.print(String.format("%-14s", "Load"));
        outs.print(String.format("%-43s", "Range"));
        outs.println("Ring");
        // emphasize that we're showing the right part of each range
        if (ranges.size() > 1)
        {
            outs.println(String.format("%-24s%-11s%-14s%-43s", "", "", "", ranges.get(ranges.size()-1)));
        }
        // normal range & node info
        for (Token range : ranges) {
            String primaryEndpoint = rangeMap.get(range);
            
            if (namesMap.containsKey(primaryEndpoint))
            {
                outs.print(String.format("%-24s", primaryEndpoint + "("+namesMap.get(primaryEndpoint)+")"));
            } else
            {
                outs.print(String.format("%-24s", primaryEndpoint));
            }
            


            String status = liveNodes.contains(primaryEndpoint)
                          ? "Up"
                          : deadNodes.contains(primaryEndpoint)
                            ? "Down"
                            : "?";
            outs.print(String.format("%-11s", status));

            outs.print(String.format("%-14s", locationsMap.get( primaryEndpoint )));

            String load = loadMap.containsKey(primaryEndpoint) ? loadMap.get(primaryEndpoint) : "?";
            outs.print(String.format("%-14s", load));

            outs.print(String.format("%-43s", range));

            String asciiRingArt;
            if (counter == 0)
            {
                asciiRingArt = "|<--|";
            }
            else if (counter == (rangeMap.size() - 1))
            {
                asciiRingArt = "|-->|";
            }
            else
            {
                if ((rangeMap.size() > 4) && ((counter % 2) == 0))
                    asciiRingArt = "v   |";
                else if ((rangeMap.size() > 4) && ((counter % 2) != 0))
                    asciiRingArt = "|   ^";
                else
                    asciiRingArt = "|   |";
            }
            outs.println(asciiRingArt);
            
            counter++;
        }
    }

    public Map<Token, String> getPrettyRing()
    {
        return ssProxy.getPrettyTokenRing();
    }
    
    public Set<String> getUnreachableNodes()
    {
        return ssProxy.getUnreachableNodes();
    }
    
    public Map<String, String> getLoadMap()
    {
        return ssProxy.getLoadMap();
    }

    public Map<Token, Float> getOwnership()
    {
        return ssProxy.getOwnership();
    }

    public Iterator<Map.Entry<String, ColumnFamilyStoreMBean>> getColumnFamilyStoreMBeanProxies()
    {
        try
        {
            return new ColumnFamilyStoreMBeanIterator(mbeanServerConn);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException("Invalid ObjectName? Please report this as a bug.", e);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Could not retrieve list of stat mbeans.", e);
        }
    }

    public JMXInstrumentedCacheMBean getKeyCacheMBean(String tableName, String cfName)
    {
        String keyCachePath = "org.apache.cassandra.db:type=Caches,keyspace=" + tableName + ",cache=" + cfName + "KeyCache";
        try
        {
            return JMX.newMBeanProxy(mbeanServerConn, new ObjectName(keyCachePath), JMXInstrumentedCacheMBean.class);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    public JMXInstrumentedCacheMBean getRowCacheMBean(String tableName, String cfName)
    {
        String rowCachePath = "org.apache.cassandra.db:type=Caches,keyspace=" + tableName + ",cache=" + cfName + "RowCache";
        try
        {
            return JMX.newMBeanProxy(mbeanServerConn, new ObjectName(rowCachePath), JMXInstrumentedCacheMBean.class);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get MBean representing ColumnFamilyStore
     * @param keySpace name of the keyspace
     * @param cfName name of the column family
     * @return ColumnFamilyStoreMBean
     */
    public ColumnFamilyStoreMBean getColumnFamilyStoreMBean(String keySpace, String cfName)
    {
        String path = String.format("org.apache.cassandra.db:type=ColumnFamilyStores,keyspace=%s,columnfamily=%s", keySpace, cfName);

        try
        {
            return JMX.newMBeanProxy(mbeanServerConn, new ObjectName(path), ColumnFamilyStoreMBean.class);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getToken()
    {
        return ssProxy.getToken();
    }
    
    public String getLoadString()
    {
        return ssProxy.getLoadString();
    }
    
    public int getCurrentGenerationNumber()
    {
        return ssProxy.getCurrentGenerationNumber();
    }
    
    public long getUptime()
    {
        return runtimeProxy.getUptime();
    }
    
    public MemoryUsage getHeapMemoryUsage()
    {
        return memProxy.getHeapMemoryUsage();
    }
    
    /**
     * Take a snapshot of all the tables.
     * 
     * @param snapshotName the name of the snapshot.
     */
    public void takeSnapshot(String snapshotName) throws IOException
    {
        ssProxy.takeAllSnapshot(snapshotName);
    }

    /**
     * Takes a snapshot for every table. System table will be fully snapshotted,
     * column families in other keyspaces will be filtered using <code>cfNameRegExp</code>
     *
     * @param cfNameRegExp regexp for column families selection for snapshot
     * @param snapshotName the name of the snapshot.
     */
    public void takeSnapshot(String cfNameRegExp, String snapshotName) throws IOException
    {
        ssProxy.takeAllSnapshot(cfNameRegExp, snapshotName);
    }

    /**
     * Remove all the existing snapshots.
     */
    public void clearSnapshot() throws IOException
    {
        ssProxy.clearSnapshot();
    }

    public void decommission() throws InterruptedException
    {
        ssProxy.decommission();
    }

    public void loadBalance() throws IOException, InterruptedException
    {
        ssProxy.loadBalance();
    }

    public void move(String newToken) throws IOException, InterruptedException
    {
        ssProxy.move(newToken);
    }

    public void removeToken(String token)
    {
        ssProxy.removeToken(token);
    }
    
  
    public Iterator<Map.Entry<String, IExecutorMBean>> getThreadPoolMBeanProxies()
    {
        try
        {
            return new ThreadPoolProxyMBeanIterator(mbeanServerConn);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException("Invalid ObjectName? Please report this as a bug.", e);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Could not retrieve list of stat mbeans.", e);
        }
    }

    /**
     * Get the compaction threshold
     *
     * @param outs the stream to write to
     */
    public void getCompactionThreshold(PrintStream outs)
    {
        outs.println("Current compaction threshold: Min=" +  mcmProxy.getMinimumCompactionThreshold() +
            ", Max=" +  mcmProxy.getMaximumCompactionThreshold());
    }

    public int getMaxCompactionThreshold()
    {
        return mcmProxy.getMaximumCompactionThreshold();
    }

    /**
     * Set the compaction threshold
     *
     * @param minimumCompactionThreshold minimum compaction threshold
     * @param maximumCompactionThreshold maximum compaction threshold
     */
    public void setCompactionThreshold(int minimumCompactionThreshold, int maximumCompactionThreshold)
    {
        mcmProxy.setMinimumCompactionThreshold(minimumCompactionThreshold);
        if (maximumCompactionThreshold >= 0)
        {
             mcmProxy.setMaximumCompactionThreshold(maximumCompactionThreshold);
        }
    }
    
    /**
     * Dumps compaction status
     * 
     * @return compacton status readable string 
     */
    public String getCompactionStatus()
    {
        String cf=mcmProxy.getColumnFamilyInProgress();
        
        if (cf!=null)
        {
            return (mcmProxy.isMajorCompaction() ? "Major " : "" )+"Compacting "+cf+", progress "+mcmProxy.getBytesCompacted()+"/"+mcmProxy.getBytesTotalInProgress();
        } else
        {
            return "Not active";
        }
        
    }

    public void setCacheCapacities(String tableName, String cfName, int keyCacheCapacity, int rowCacheCapacity)
    {
        try
        {
            String keyCachePath = "org.apache.cassandra.db:type=Caches,keyspace=" + tableName + ",cache=" + cfName + "KeyCache";
            JMXInstrumentedCacheMBean keyCacheMBean = JMX.newMBeanProxy(mbeanServerConn, new ObjectName(keyCachePath), JMXInstrumentedCacheMBean.class);
            keyCacheMBean.setCapacity(keyCacheCapacity);

            String rowCachePath = "org.apache.cassandra.db:type=Caches,keyspace=" + tableName + ",cache=" + cfName + "RowCache";
            JMXInstrumentedCacheMBean rowCacheMBean = null;
            rowCacheMBean = JMX.newMBeanProxy(mbeanServerConn, new ObjectName(rowCachePath), JMXInstrumentedCacheMBean.class);
            rowCacheMBean.setCapacity(rowCacheCapacity);
        }
        catch (MalformedObjectNameException e)
        {
            throw new RuntimeException(e);
        }
    }

    public List<InetAddress> getEndPoints(String keyspace, String key)
    {
        return ssProxy.getNaturalEndpoints(keyspace, key);
    }

    public Set<InetAddress> getStreamDestinations()
    {
        return streamProxy.getStreamDestinations();
    }

    public List<String> getFilesDestinedFor(InetAddress host) throws IOException
    {
        return streamProxy.getOutgoingFiles(host.getHostAddress());
    }

    public Set<InetAddress> getStreamSources()
    {
        return streamProxy.getStreamSources();
    }

    public List<String> getIncomingFiles(InetAddress host) throws IOException
    {
        return streamProxy.getIncomingFiles(host.getHostAddress());
    }

    public void cancelStreamOut(String host2) throws UnknownHostException
    {
        streamProxy.cancelStreamOut(host2);
    }
    
    public void setStreamingInMBits(int speed) {
        streamProxy.setStreamInMBits(speed);
    }

    public String getOperationMode()
    {
        return ssProxy.getOperationMode();
    }

    /**
     * @return
     */
    public Map<String, String> getLocationsMap()
    {
        try {
            return ssProxy.getLocationsMap();
        } catch (Exception e)
        {
            // this is old endpoint
            return Collections.emptyMap();
        }
    }

    /**
     * @return
     */
    public Map<String, String> getEndpointNames()
    {
        try {
            return ssProxy.getEndpointNames();
        } catch (Exception e)
        {
            // this is old endpoint
            return Collections.emptyMap();
        }
    }

    /**
     * @return
     */
    public Map<Token, String> getPrettyTokenRing()
    {
        try {
            return getPrettyRing();
        } catch (Exception e)
        {
            // falling back to old style
            
            Map<Range, List<String>> rangeToEndPointMap = ssProxy.getRangeToEndPointMap(null);
            Map<Token,String> m = new HashMap<Token, String>();
            for (Entry<Range, List<String>> entry : rangeToEndPointMap.entrySet()) {
                m.put(entry.getKey().right,entry.getValue().get(0));
            }
            
            return m;
        }
    }

   
}

class ColumnFamilyStoreMBeanIterator implements Iterator<Map.Entry<String, ColumnFamilyStoreMBean>>
{
    private Iterator<ObjectName> resIter;
    private MBeanServerConnection mbeanServerConn;
    
    public ColumnFamilyStoreMBeanIterator(MBeanServerConnection mbeanServerConn)
    throws MalformedObjectNameException, NullPointerException, IOException
    {
        ObjectName query = new ObjectName("org.apache.cassandra.db:type=ColumnFamilyStores,*");
        resIter = mbeanServerConn.queryNames(query, null).iterator();
        this.mbeanServerConn = mbeanServerConn;
    }

    public boolean hasNext()
    {
        return resIter.hasNext();
    }

    public Entry<String, ColumnFamilyStoreMBean> next()
    {
        ObjectName objectName = resIter.next();
        String tableName = objectName.getKeyProperty("keyspace");
        ColumnFamilyStoreMBean cfsProxy = JMX.newMBeanProxy(mbeanServerConn, objectName, ColumnFamilyStoreMBean.class);
        return new AbstractMap.SimpleImmutableEntry<String, ColumnFamilyStoreMBean>(tableName, cfsProxy);
    }

    public void remove()
    {
        throw new UnsupportedOperationException();
    }
}

class ThreadPoolProxyMBeanIterator implements Iterator<Map.Entry<String, IExecutorMBean>>
{
    private Iterator<ObjectName> resIter;
    private MBeanServerConnection mbeanServerConn;
    
    public ThreadPoolProxyMBeanIterator(MBeanServerConnection mbeanServerConn) 
    throws MalformedObjectNameException, NullPointerException, IOException
    {
        ObjectName query = new ObjectName("org.apache.cassandra.concurrent:type=*");
        resIter = mbeanServerConn.queryNames(query, null).iterator();
        this.mbeanServerConn = mbeanServerConn;
    }
    
    public boolean hasNext()
    {
        return resIter.hasNext();
    }

    public Map.Entry<String, IExecutorMBean> next()
    {
        ObjectName objectName = resIter.next();
        String poolName = objectName.getKeyProperty("type");
        IExecutorMBean threadPoolProxy = JMX.newMBeanProxy(mbeanServerConn, objectName, IExecutorMBean.class);
        return new AbstractMap.SimpleImmutableEntry<String, IExecutorMBean>(poolName, threadPoolProxy);
    }

    public void remove()
    {
        throw new UnsupportedOperationException();
    }   
}
