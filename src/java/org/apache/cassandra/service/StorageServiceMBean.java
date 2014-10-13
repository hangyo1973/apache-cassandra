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

package org.apache.cassandra.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;


public interface StorageServiceMBean
{    
    /**
     * Retrieve the list of live nodes in the cluster, where "liveness" is
     * determined by the failure detector of the node being queried.
     *
     * @return set of IP addresses, as Strings
     */
    public Set<String> getLiveNodes();

    /**
     * Retrieve the list of unreachable nodes in the cluster, as determined
     * by this node's failure detector.
     *
     * @return set of IP addresses, as Strings
     */
    public Set<String> getUnreachableNodes();

    /**
     * Fetch a string representation of the token.
     *
     * @return a string token
     */
    public String getToken();

    /**
     * Retrieve a map of range to end points that describe the ring topology
     * of a Cassandra cluster.
     *
     * @return mapping of ranges to end points
     */
    public Map<Range, List<String>> getRangeToEndPointMap(String keyspace);

    /**
     * Numeric load value.
     */
    public double getLoad();

    /** Human-readable load value */
    public String getLoadString();

    /** Human-readable load value.  Keys are IP addresses. */
    public Map<String, String> getLoadMap();

    /**
     * Return the generation value for this node.
     *
     * @return generation number
     */
    public int getCurrentGenerationNumber();
    
    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public List<InetAddress> getNaturalEndpoints(String key, String table);

    /**
     * Forces major compaction (all sstable files compacted)
     */
    public void forceTableCompaction() throws IOException;

    /**
     * Forces major compaction on a single cf of a single keyspace
     */
    public void forceTableCompaction(String ks, String... columnFamilies) throws IOException;

    /**
     * Trigger a cleanup of keys on all tables.
     */
    public void forceTableCleanup() throws IOException;

    /**
     * Trigger a cleanup of keys on a single keyspace
     */
    public void forceTableCleanup(String tableName, String... columnFamilies) throws IOException;

    /**
     * Takes the snapshot for a given table.
     * 
     * @param tableName the name of the table.
     * @param tag       the tag given to the snapshot (null is permissible)
     */
    public void takeSnapshot(String tableName, String tag) throws IOException;

    /**
     * Takes a snapshot for every table.
     * 
     * @param tag the tag given to the snapshot (null is permissible)
     */
    public void takeAllSnapshot(String tag) throws IOException;

    /**
     * Takes a snapshot for every table. System table will be fully snapshotted,
     * column families in other keyspaces will be filtered using <code>cfNameRegExp</code>
     * @param cfNameRegExp regexp for column families selection for snapshot
     * @param tag the tag given to the snapshot (null is permissible)
     */
    public void takeAllSnapshot(String cfNameRegExp, String tag) throws IOException;

    /**
     * Remove all the existing snapshots.
     */
    public void clearSnapshot() throws IOException;

    /**
     * Flush all memtables for the given column families, or all columnfamilies for the given table
     * if none are explicitly listed.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableFlush(String tableName, String... columnFamilies) throws IOException;

    /**
     * Triggers proactive repair for given column families, or all columnfamilies for the given table
     * if none are explicitly listed.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableRepair(String tableName, String... columnFamilies) throws IOException;

    /**
     * transfer this node's data to other machines and remove it from service.
     */
    public void decommission() throws InterruptedException;

    /**
     * @param newToken token to move this node to.
     * This node will unload its data onto its neighbors, and bootstrap to the new token.
     */
    public void move(String newToken) throws IOException, InterruptedException;

    /**
     * This node will unload its data onto its neighbors, and bootstrap to share the range
     * of the most-loaded node in the ring.
     */
    public void loadBalance() throws IOException, InterruptedException;

    /**
     * removeToken removes token (and all data associated with
     * enpoint that had it) from the ring
     */
    public void removeToken(String token);

    /** set the logging level at runtime */
    public void setLog4jLevel(String classQualifier, String level);

    /** get the operational mode (leaving, joining, normal, decommissioned, client) **/
    public String getOperationMode();
    
    /** makes node unavailable for writes, flushes memtables and replays commitlog. */
    public void drain() throws IOException, InterruptedException, ExecutionException;
    
    /** force hint delivery to an endpoint **/
    public void deliverHints(String host) throws UnknownHostException;
    
    /**
     * 
     * @return currently configured value
     */
    int getMemtableThroughput();
    /**
     * MM: allow experiments with memtable throughout change at runtime
     * 
     * @param throughputMBytes
     */
    void setMemtableThroughput(int throughputMBytes);
    
    /**
     * 
     * @return currently configured value
     */
    double getMemtableOperations();
    /**
     * MM: allow experiments with memtable operations change at runtime
     * 
     * @param operations
     */
    void setMemtableOperations(double operations);
    /** save row and key caches */
    public void saveCaches() throws ExecutionException, InterruptedException;

    /**
     * given a list of tokens (representing the nodes in the cluster), returns
     *   a mapping from "token -> %age of cluster owned by that token"
     */
    public Map<Token, Float> getOwnership();

    /**
     * @return
     */
    float getConsistencyCheckProbability();

    /**
     * @param p
     */
    void setConsistencyCheckProbability(float p);

    /**
     * @return
     */
    long getRecentReadRepairs();

    /**
     * @return map of known endpoint locations. key: endpoint address, value datacenter:rack
     */
    Map<String, String> getLocationsMap();

    /**
     * @return map of known endpoint names. key: endpoint address, value: name
     */
    Map<String, String> getEndpointNames();

    /**
     * @return
     */
    Map<Token, String> getPrettyTokenRing();

    /**
     * @return
     */
    String gossipInfo();

    /**
     * 
     */
    void gossipStop();

    /**
     * 
     */
    void gossipStart();

    /**
     * 
     */
    void gossipPurgePersistent();

    /**
     * @return
     */
    int getMaxCommitLogSegmentsActive();

    /**
     * @param maxCount
     */
    void setMaxCommitLogSegmentsActive(int maxCount);
     /**
     * Resume  node paused before joining the ring during bootstrap
     */
    void completeBootstrap();
}
