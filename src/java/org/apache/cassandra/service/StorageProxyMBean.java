/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.service;

import org.apache.cassandra.config.ConfigurationException;

public interface StorageProxyMBean
{
    public long getReadOperations();
    public long getTotalReadLatencyMicros();
    public double getRecentReadLatencyMicros();

    public long getRangeOperations();
    public long getTotalRangeLatencyMicros();
    public double getRecentRangeLatencyMicros();

    public long getWriteOperations();
    public long getTotalWriteLatencyMicros();
    public double getRecentWriteLatencyMicros();

    public String getHintedHandoffEnabled();
    /**
     * @param config
     * @throws ConfigurationException
     */
    void setHintedHandoffEnabled(String config) throws ConfigurationException;
    /**
     * @return
     */
    int getHintedHandoffWriteLatencyThreshold();
    /**
     * @param millis
     */
    void setHintedHandoffWriteLatencyThreshold(int millis);
    /**
     * @return
     */
    double getRecentHintLatencyMicros();
    /**
     * @return
     */
    long getTotalHintLatencyMicros();
    /**
     * @return
     */
    long getHintOperations();
    /**
     * @return
     */
    long[] getTotalHintHistogram();
    /**
     * @return
     */
    long[] getRecentHintHistogram();
    /**
     * @return
     */
    long getTotalLaggedHints();
    /**
     * @return
     */
    boolean getParallelReads();
    /**
     * @param parallelWeakRead
     */
    void setParallelReads(boolean parallelWeakRead);
    /**
     * @return
     */
    long getRecentWeakReadsLocal();
    /**
     * @return
     */
    long getRecentWeakReadsRemote();
    /**
     * @return
     */
    long getRecentWeakConsistencyAll();
    /**
     * @return
     */
    long getRecentWeakConsistencyUnder();
    /**
     * @return
     */
    long getRecentReadRepairs();
    /**
     * @return
     */
    long getRecentStrongConsistencyAll();
    /**
     * @return
     */
    long getRecentStrongConsistencyUnder();
    /**
     * @return
     */
    long getRecentStrongConsistencyReuseSuperset();
    
    
}
