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
package org.apache.cassandra.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.log4j.Logger;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CassandraServer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.json.simple.JSONValue;

/**
 *  A class for caching the ring map at the client. For usage example, see
 *  test/unit/org.apache.cassandra.client.TestRingCache.java.
 */
public class RingCache
{
    final private static Logger logger_ = Logger.getLogger(RingCache.class);

    private Set<String> seeds_ = new HashSet<String>();
    final private int port_= DatabaseDescriptor.getThriftPort();
    final private static IPartitioner partitioner_ = DatabaseDescriptor.getPartitioner();
    private TokenMetadata tokenMetadata;

    public RingCache()
    {
        for (InetAddress seed : DatabaseDescriptor.getSeeds())
        {
            seeds_.add(seed.getHostAddress());
        }
        refreshEndPointMap();
    }

    public void refreshEndPointMap()
    {
        for (String seed : seeds_)
        {
            try
            {
                TSocket socket = new TSocket(seed, port_);
                TBinaryProtocol binaryProtocol = new TBinaryProtocol(socket, false, false);
                Cassandra.Client client = new Cassandra.Client(binaryProtocol);
                socket.open();

                Map<String,String> tokenToHostMap = (Map<String,String>) JSONValue.parse(client.get_string_property(CassandraServer.TOKEN_MAP));
                
                BiMap<Token, InetAddress> tokenEndpointMap = HashBiMap.create();
                for (Map.Entry<String,String> entry : tokenToHostMap.entrySet())
                {
                    Token token = StorageService.getPartitioner().getTokenFactory().fromString(entry.getKey());
                    String host = entry.getValue();
                    try
                    {
                        tokenEndpointMap.put(token, InetAddress.getByName(host));
                    }
                    catch (UnknownHostException e)
                    {
                        throw new AssertionError(e); // host strings are IPs
                    }
                }

                tokenMetadata = new TokenMetadata(tokenEndpointMap);

                break;
            }
            catch (TException e)
            {
                /* let the Exception go and try another seed. log this though */
                logger_.debug("Error contacting seed " + seed + " " + e.getMessage());
            }
        }
    }

    public List<InetAddress> getEndPoint(String table, String key)
    {
        if (tokenMetadata == null)
            throw new RuntimeException("Must refresh endpoints before looking up a key.");
        AbstractReplicationStrategy strat = StorageService.getReplicationStrategy(tokenMetadata, table);
        return strat.getNaturalEndpoints(partitioner_.getToken(key), table);
    }
}
