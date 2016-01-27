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

import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.commons.lang.StringUtils;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.hints.HintLog;
import org.apache.cassandra.dht.BootStrapper;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.DeletionService;
import org.apache.cassandra.io.IndexSummary;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.IEndPointSnitch;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.net.ResponseVerbHandler;
import org.apache.cassandra.service.AntiEntropyService.TreeRequestVerbHandler;
import org.apache.cassandra.streaming.*;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.tools.NodeProbe;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.WrappedRunnable;

/*
 * This abstraction contains the token/identifier of this node
 * on the identifier space. This token gets gossiped around.
 * This class will also maintain histograms of the load information
 * of other nodes in the cluster.
 */
public class StorageService implements IEndPointStateChangeSubscriber, StorageServiceMBean
{
    private static Logger logger_ = Logger.getLogger(StorageService.class);     

    public static final int RING_DELAY = 30 * 1000; // delay after which we assume ring has stablized

    public final static String MOVE_STATE = "MOVE";

    // this must be a char that cannot be present in any token
    public final static char Delimiter = ',';
    private final static String DelimiterStr = new String(new char[] {Delimiter});

    public final static String STATE_BOOTSTRAPPING = "BOOT";
    public final static String STATE_NORMAL = "NORMAL";
    public final static String STATE_LEAVING = "LEAVING";
    public final static String STATE_LEFT = "LEFT";
    public final static String STATE_HIBERNATE = "hibernate";

    public final static String REMOVE_TOKEN = "remove";

    /* All verb handler identifiers */
    public enum Verb
    {
        MUTATION,
        BINARY,
        READ_REPAIR,
        READ,
        READ_RESPONSE,
        STREAM_INITIATE,
        STREAM_INITIATE_DONE,
        STREAM_FINISHED,
        STREAM_REQUEST,
        RANGE_SLICE,
        BOOTSTRAP_TOKEN,
        TREE_REQUEST,
        TREE_RESPONSE,
        JOIN,
        GOSSIP_DIGEST_SYN,
        GOSSIP_DIGEST_ACK,
        GOSSIP_DIGEST_ACK2,
        ;
        // remember to add new verbs at the end, since we serialize by ordinal
    }
    public static final Verb[] VERBS = Verb.values();

    private static IPartitioner partitioner_ = DatabaseDescriptor.getPartitioner();

    public static final StorageService instance = new StorageService();
    
    public static IPartitioner getPartitioner() {
        return partitioner_;
    }

    public Collection<Range> getLocalRanges(String table)
    {
        return getRangesForEndPoint(table, FBUtilities.getLocalAddress());
    }

    public Range getLocalPrimaryRange()
    {
        return getPrimaryRangeForEndPoint(FBUtilities.getLocalAddress());
    }

    /* This abstraction maintains the token/endpoint metadata information */
    private TokenMetadata tokenMetadata_ = new TokenMetadata();
    private SystemTable.StorageMetadata storageMetadata_;

    /* This thread pool does consistency checks when the client doesn't care about consistency */
    private ExecutorService consistencyManager_ = new JMXEnabledThreadPoolExecutor(DatabaseDescriptor.getConsistencyThreads(),
                                                                                   DatabaseDescriptor.getConsistencyThreads(),
                                                                                   Integer.MAX_VALUE,
                                                                                   TimeUnit.SECONDS,
                                                                                   new LinkedBlockingQueue<Runnable>(),
                                                                                   new NamedThreadFactory("CONSISTENCY-MANAGER"));

    /* We use this interface to determine where replicas need to be placed */
    private Map<String, AbstractReplicationStrategy> replicationStrategies;

    /* Are we starting this node in bootstrap mode? */
    private boolean isBootstrapMode;
    private Multimap<InetAddress, String> bootstrapSet;
    /* when intialized as a client, we shouldn't write to the system table. */
    private boolean isClientMode;
    private boolean initialized;
    private String operationMode;

    private AtomicLong recentReadRepairs = new AtomicLong();

    public void addBootstrapSource(InetAddress s, String table)
    {
        if (logger_.isDebugEnabled())
            logger_.debug(String.format("Added %s/%s as a bootstrap source", s, table));
        bootstrapSet.put(s, table);
    }

    public void removeBootstrapSource(InetAddress s, String table)
    {
        if (table == null)
            bootstrapSet.removeAll(s);
        else
            bootstrapSet.remove(s, table);
        if (logger_.isDebugEnabled())
            logger_.debug(String.format("Removed %s/%s as a bootstrap source; remaining is [%s]", s, table == null ? "<ALL>" : table, StringUtils.join(bootstrapSet.keySet(), ", ")));

        if (bootstrapSet.isEmpty())
        {
            finishBootstrapping();
        }
    }

    private void finishBootstrapping()
    {
        isBootstrapMode = false;
    }

    /** This method updates the local token on disk  */
    public void setToken(Token token)
    {
        if (logger_.isDebugEnabled())
            logger_.debug("Setting token to " + token);
        SystemTable.updateToken(token);
        tokenMetadata_.updateNormalToken(token, FBUtilities.getLocalAddress());
    }

    public StorageService()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName("org.apache.cassandra.service:type=StorageService"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        bootstrapSet = Multimaps.synchronizedSetMultimap(HashMultimap.<InetAddress, String>create());

        /* register the verb handlers */
        MessagingService.instance.registerVerbHandlers(Verb.BINARY, new BinaryVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.MUTATION, new RowMutationVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.READ_REPAIR, new ReadRepairVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.READ, new ReadVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.RANGE_SLICE, new RangeSliceVerbHandler());
        // see BootStrapper for a summary of how the bootstrap verbs interact
        MessagingService.instance.registerVerbHandlers(Verb.BOOTSTRAP_TOKEN, new BootStrapper.BootstrapTokenVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.STREAM_REQUEST, new StreamRequestVerbHandler() );
        MessagingService.instance.registerVerbHandlers(Verb.STREAM_INITIATE, new StreamInitiateVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.STREAM_INITIATE_DONE, new StreamInitiateDoneVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.STREAM_FINISHED, new StreamFinishedVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.READ_RESPONSE, new ResponseVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.TREE_REQUEST, new TreeRequestVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.TREE_RESPONSE, new AntiEntropyService.TreeResponseVerbHandler());

        MessagingService.instance.registerVerbHandlers(Verb.JOIN, new GossiperJoinVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.GOSSIP_DIGEST_SYN, new GossipDigestSynVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.GOSSIP_DIGEST_ACK, new GossipDigestAckVerbHandler());
        MessagingService.instance.registerVerbHandlers(Verb.GOSSIP_DIGEST_ACK2, new GossipDigestAck2VerbHandler());

        replicationStrategies = new HashMap<String, AbstractReplicationStrategy>();
        for (String table : DatabaseDescriptor.getNonSystemTables())
        {
            AbstractReplicationStrategy strat = getReplicationStrategy(tokenMetadata_, table);
            replicationStrategies.put(table, strat);
        }
        replicationStrategies = Collections.unmodifiableMap(replicationStrategies);

        // spin up the streaming serivice so it is available for jmx tools.
        if (StreamingService.instance == null)
            throw new RuntimeException("Streaming service is unavailable.");
    }

    public AbstractReplicationStrategy getReplicationStrategy(String table)
    {
        AbstractReplicationStrategy ars = replicationStrategies.get(table);
        if (ars == null)
            throw new RuntimeException(String.format("No replica strategy configured for %s", table));
        else
            return ars;
    }

    public static AbstractReplicationStrategy getReplicationStrategy(TokenMetadata tokenMetadata, String table)
    {
        AbstractReplicationStrategy replicationStrategy = null;
        Class<? extends AbstractReplicationStrategy> cls = DatabaseDescriptor.getReplicaPlacementStrategyClass(table);
        if (cls == null)
            throw new RuntimeException(String.format("No replica strategy configured for %s", table));
        Class [] parameterTypes = new Class[] { TokenMetadata.class, IEndPointSnitch.class};
        try
        {
            Constructor<? extends AbstractReplicationStrategy> constructor = cls.getConstructor(parameterTypes);
            replicationStrategy = constructor.newInstance(tokenMetadata, DatabaseDescriptor.getEndPointSnitch(table));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        return replicationStrategy;
    }

    public void stopClient()
    {
        Gossiper.instance.unregister(this);
        Gossiper.instance.stop();
        MessagingService.shutdown();
        StageManager.shutdownNow();
    }
    
    public  void setConsistencyCheckProbability( float p )
    {
        DatabaseDescriptor.setConsistencyCheckProbability(p);
    }

    public  float getConsistencyCheckProbability( )
    {
        return DatabaseDescriptor.getConsistencyCheckProbability() ;
    }
    
    public synchronized void initClient() throws IOException
    {
        if (initialized)
        {
            if (!isClientMode)
                throw new UnsupportedOperationException("StorageService does not support switching modes.");
            return;
        }
        initialized = true;
        isClientMode = true;
        logger_.info("Starting up client gossip");
        setMode("Client", false);
        Gossiper.instance.init(FBUtilities.getLocalAddress(), (int)(System.currentTimeMillis() / 1000)); // needed for node-ring gathering.
        Gossiper.instance.register(this);
        Gossiper.instance.start();
        MessagingService.instance.listen(FBUtilities.getLocalAddress());
    }

    public synchronized void initServer() throws IOException, org.apache.cassandra.config.ConfigurationException
    {
        if (initialized)
        {
            if (isClientMode)
                throw new UnsupportedOperationException("StorageService does not support switching modes.");
            return;
        }
        initialized = true;
        isClientMode = false;
        storageMetadata_ = SystemTable.initMetadata();

        // be certain that the recorded clustername matches what the user specified
        if (!(Arrays.equals(storageMetadata_.getClusterName(),DatabaseDescriptor.getClusterName().getBytes())))
        {
            logger_.error("ClusterName mismatch: " + new String(storageMetadata_.getClusterName()) + " != " +
                    DatabaseDescriptor.getClusterName());
            System.exit(3);
        }
        
        partitioner_.validateToken(storageMetadata_.getToken());
        
        if (SystemTable.isBootstrapped() && !DatabaseDescriptor.isAutoBootstrap())
        {
            throw new ConfigurationException("This node seems boostrapped, but your storage-conf.xml has <Autoboostrap>false</>. Please change it to true on ALL nodes to avoid accidental empty node join to the ring");
        }

        DatabaseDescriptor.createAllDirectories();

        try
        {
            GCInspector.instance.start();
        }
        catch (Throwable t)
        {
            logger_.warn("Unable to start GCInspector (currently only supported on the Sun JVM)");
        }

        logger_.info("Starting up server gossip");

        // have to start the gossip service before we can see any info on other nodes.  this is necessary
        // for bootstrap to get the load info it needs.
        // (we won't be part of the storage ring though until we add a nodeId to our state, below.)
        Gossiper.instance.init(FBUtilities.getLocalAddress(), storageMetadata_.getGeneration()); // needed for node-ring gathering.
        // calling endpoints so they get chance of registering with gossip before
        // Storage service, so they can block invalid nodes from appearing in ring
        for (String table : DatabaseDescriptor.getNonSystemTables())
        {
            DatabaseDescriptor.getEndPointSnitch(table).gossiperStarting();
        }
        Gossiper.instance.register(this);
        // touching class to make it register itself to gossip, so it will get information, preloaded from persistent store
        StorageLoadBalancer loadBalancer = StorageLoadBalancer.instance;
        Gossiper.instance.start();
        
        if (null != DatabaseDescriptor.getReplaceToken())
        {
            logger_.info("Will replace node with token = "+DatabaseDescriptor.getReplaceToken());
            Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_HIBERNATE+Delimiter+"true");
        }

        MessagingService.instance.listen(FBUtilities.getLocalAddress());

        loadBalancer.startBroadcasting();

        if (DatabaseDescriptor.isAutoBootstrap()
                && DatabaseDescriptor.getSeeds().contains(FBUtilities.getLocalAddress())
                && !SystemTable.isBootstrapped())
            throw new UnsupportedOperationException("This node will not auto bootstrap/replace because it is configured to be a seed node.");

        InetAddress current = null;
        if (DatabaseDescriptor.isAutoBootstrap()
            && !(DatabaseDescriptor.getSeeds().contains(FBUtilities.getLocalAddress()) || SystemTable.isBootstrapped()))
        {
            setMode("Joining: getting load information", true);
            StorageLoadBalancer.instance.waitForLoadInfo();
            if (logger_.isDebugEnabled())
                logger_.debug("... got load info");
            
            Token token;
            
            if (DatabaseDescriptor.getReplaceToken() == null)
            {
                if (tokenMetadata_.isMember(FBUtilities.getLocalAddress()))
                {
                    String s = "This node is already a member of the token ring; bootstrap aborted. (If replacing a dead node, remove the old one from the ring first.)";
                    throw new UnsupportedOperationException(s);
                }
                setMode("Joining: getting bootstrap token", true);
                token = BootStrapper.getBootstrapToken(tokenMetadata_, StorageLoadBalancer.instance.getLoadInfo());
            }
            else
            {
                token = StorageService.getPartitioner().getTokenFactory().fromString(DatabaseDescriptor.getReplaceToken());
                
                if (tokenMetadata_.getEndPoint(token) == null)
                {
                    // we did not got token from gossip, but this may be because we replacing with the same ip address
                    // failed node has. In this case gossip will not deliver its messages, so we validate with
                    // nodeprobe, asking token metadata using jmx interface from one of currently known live nodes
                    if (!hasTokenOnOtherNodes(token,FBUtilities.getLocalAddress()))
                        throw new UnsupportedOperationException("Token "+token+" is not registered in token ring. Cannot replace a previously unknown token. Are you trying to bootstrap new node ?");
                }
                
                setMode("Joining: Preparing to replace a node with token: " + token+". Checking, is node being replaced actually live", true);
                try
                {
                    // Sleeping additionally to make sure that the server actually is not alive
                    // and giving it more time to gossip if alive.
                    Thread.sleep(StorageLoadBalancer.BROADCAST_INTERVAL);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
                // check for operator errors...
                current = tokenMetadata_.getEndPoint(token);
                if (null != current && Gossiper.instance.getEndPointStateForEndPoint(current).getUpdateTimestamp() > (System.currentTimeMillis() - StorageLoadBalancer.BROADCAST_INTERVAL))
                    throw new UnsupportedOperationException("Cannnot replace a token for a Live node... ");
                setMode("Joining: Replacing a node with token: " + token, true);
            }

            startBootstrap(token);
            // don't finish startup (enabling thrift) until after bootstrap is done
            while (isBootstrapMode)
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
            }

            if (DatabaseDescriptor.isManualBootstrapComplete()){
                logger_.info("Bootstrap/move data retrieval completed, waiting manual resume before complete and join ring. Resume with nodetool resumebootstrap.");
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                
            }
            SystemTable.setBootstrapped(true);
            setToken(getLocalToken());

            // remove the existing info about the replaced node.
            if (current != null)
                Gossiper.instance.replacedEndpoint(current);

            Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_NORMAL + Delimiter + partitioner_.getTokenFactory().toString(getLocalToken()));
            logger_.info("Bootstrap/move completed! Now serving reads.");
            setMode("Normal", false);

        }
        else
        {
            SystemTable.setBootstrapped(true);
            Token token = storageMetadata_.getToken();
            tokenMetadata_.updateNormalToken(token, FBUtilities.getLocalAddress());
            Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_NORMAL + Delimiter + partitioner_.getTokenFactory().toString(token));
            setMode("Normal", false);
        }

        assert tokenMetadata_.sortedTokens().size() > 0;
    }

    /**
    /**
     * ! DO NOT USE THIS METHOD FOR OTHER THAN VALIDATION
     * @return current token metadata state from antoher node, asked through JMX
     */
    private boolean hasTokenOnOtherNodes(Token token, InetAddress localAddress)
    {
        // first try seeds
        for (InetAddress seed : DatabaseDescriptor.getSeeds()) 
        {
            if (FailureDetector.instance.isAlive(seed))
            {
                try {
                    Map<Token, String> ring = new NodeProbe(seed.getHostAddress()).getPrettyRing();
                    
                    String host = ring.get(token);

                    if (host!=null && host.equals(localAddress.getHostAddress()))
                    {
                        logger_.info("Found myself in ring of "+seed.getHostAddress());
                        return true;
                    }
                    
                } catch (IOException e) {
                } catch (InterruptedException e) {
                }
            }
        }
        
        return false;
    }

    private void setMode(String m, boolean log)
    {
        operationMode = m;
        if (log)
            logger_.info(m);
    }

    private void startBootstrap(Token token) throws IOException
    {
        isBootstrapMode = true;
        SystemTable.updateToken(token); // DON'T use setToken, that makes us part of the ring locally which is incorrect until we are done bootstrapping
        if (null == DatabaseDescriptor.getReplaceToken())
        {
            Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_BOOTSTRAPPING + Delimiter + partitioner_.getTokenFactory().toString(token));
            setMode("Joining: sleeping " + RING_DELAY + " ms for pending range setup", true);
            try
            {
                Thread.sleep(RING_DELAY);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
            setMode("Bootstrapping", true);
        } 
        else
        {
            // Dont set any state for the node which is bootstrapping the existing token...
            tokenMetadata_.updateNormalToken(token, FBUtilities.getLocalAddress());
            setMode("Bootstrapping (replace token)", true);
        }

        new BootStrapper(FBUtilities.getLocalAddress(), token, tokenMetadata_).startBootstrap(); // handles token update
    }

    public boolean isBootstrapMode()
    {
        return isBootstrapMode;
    }

    public TokenMetadata getTokenMetadata()
    {
        return tokenMetadata_;
    }

    /**
     * This method performs the requisite operations to make
     * sure that the N replicas are in sync. We do this in the
     * background when we do not care much about consistency.
     */
    public void doConsistencyCheck(Row row, ReadCommand command, InetAddress dataSource)
    {
        if (DatabaseDescriptor.getConsistencyCheck())
        {
            List<InetAddress> endpoints = StorageService.instance.getLiveNaturalEndpoints(command.table, command.key);
            if (endpoints.size() > 1)
                consistencyManager_.submit(new ConsistencyChecker(command, row, endpoints, dataSource));
        }
    }
    
    public void doConsistencyCheck(Runnable checkTask)
    {
        if (DatabaseDescriptor.getConsistencyCheck())
        {
            consistencyManager_.submit(checkTask);
        }
    }
    

    /**
     * for a keyspace, return the ranges and corresponding hosts for a given keyspace in human friendly form.
     * @param keyspace
     * @return
     */
    public Map<Range, List<String>> getRangeToEndPointMap(String keyspace)
    {
        // some people just want to get a visual representation of things. Allow null and set it to the first
        // non-system table.
        if (keyspace == null)
            keyspace = DatabaseDescriptor.getNonSystemTables().get(0);

        /* All the ranges for the tokens */
        Map<Range, List<String>> map = new HashMap<Range, List<String>>();
        for (Range range : getAllRanges() )
        {
            map.put(range, stringify(getReplicationStrategy(keyspace).calculateNaturalEndpoints(range.right, tokenMetadata_ , keyspace)));
        }
        return map;
    }

    public Map<Range, Collection<InetAddress>> getRangeToAddressMap(String keyspace)
    {
        return getReplicationStrategy(keyspace).getRangeAddresses(tokenMetadata_, keyspace).asMap();
    }

    /**
     * Human readable token ring representation for dipolay by node probe
     * @return key - token, value - ip addr
     */
    public Map<Token,String> getPrettyTokenRing()
    {
        List<Token> sortedTokens = tokenMetadata_.sortedTokens();
        
        HashMap<Token, String> ring = new HashMap<Token, String>(sortedTokens.size());
        
        for (Token token : sortedTokens) {
            ring.put(token, tokenMetadata_.getEndPoint(token).getHostAddress());
        }
        
        return ring;
    }

    /*
     * onChange only ever sees one ApplicationState piece change at a time, so we perform a kind of state machine here.
     * We are concerned with two events: knowing the token associated with an enpoint, and knowing its operation mode.
     * Nodes can start in either bootstrap or normal mode, and from bootstrap mode can change mode to normal.
     * A node in bootstrap mode needs to have pendingranges set in TokenMetadata; a node in normal mode
     * should instead be part of the token ring.
     * 
     * Normal state progression of a node should be like this:
     * STATE_BOOTSTRAPPING,token
     *   if bootstrapping. stays this way until all files are received.
     * STATE_NORMAL,token 
     *   ready to serve reads and writes.
     * STATE_NORMAL,token,REMOVE_TOKEN,token
     *   specialized normal state in which this node acts as a proxy to tell the cluster about a dead node whose 
     *   token is being removed. this value becomes the permanent state of this node (unless it coordinates another
     *   removetoken in the future).
     * STATE_LEAVING,token 
     *   get ready to leave the cluster as part of a decommission or move
     * STATE_LEFT,token 
     *   set after decommission or move is completed.
     * 
     * Note: Any time a node state changes from STATE_NORMAL, it will not be visible to new nodes. So it follows that
     * you should never bootstrap a new node during a removetoken, decommission or move.
     */
    public void onChange(InetAddress endpoint, String apStateName, ApplicationState apState)
    {
        if (!MOVE_STATE.equals(apStateName))
            return;

        String apStateValue = apState.getValue();
        String[] pieces = apStateValue.split(DelimiterStr, -1);
        assert (pieces.length > 0);        

        String moveName = pieces[0];

        if (moveName.equals(STATE_BOOTSTRAPPING))
            handleStateBootstrap(endpoint, pieces);
        else if (moveName.equals(STATE_NORMAL))
            handleStateNormal(endpoint, pieces);
        else if (moveName.equals(STATE_LEAVING))
            handleStateLeaving(endpoint, pieces);
        else if (moveName.equals(STATE_LEFT))
            handleStateLeft(endpoint, pieces);
    }

    /**
     * Handle node bootstrap
     *
     * @param endPoint bootstrapping node
     * @param pieces STATE_BOOTSTRAPPING,bootstrap token as string
     */
    private void handleStateBootstrap(InetAddress endPoint, String[] pieces)
    {
        assert pieces.length == 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);        

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endPoint + " state bootstrapping, token " + token);

        // if this node is present in token metadata, either we have missed intermediate states
        // or the node had crashed. Print warning if needed, clear obsolete stuff and
        // continue.
        if (tokenMetadata_.isMember(endPoint))
        {
            // If isLeaving is false, we have missed both LEAVING and LEFT. However, if
            // isLeaving is true, we have only missed LEFT. Waiting time between completing
            // leave operation and rebootstrapping is relatively short, so the latter is quite
            // common (not enough time for gossip to spread). Therefore we report only the
            // former in the log.
            if (!tokenMetadata_.isLeaving(endPoint))
                logger_.info("Node " + endPoint + " state jump to bootstrap");
            tokenMetadata_.removeEndpoint(endPoint);
        }

        tokenMetadata_.addBootstrapToken(token, endPoint);
        calculatePendingRanges();
    }

    /**
     * Handle node move to normal state. That is, node is entering token ring and participating
     * in reads.
     *
     * @param endpoint node
     * @param pieces STATE_NORMAL,token[,other_state,token]
     */
    private void handleStateNormal(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state normal, token " + token);

        if (tokenMetadata_.isMember(endpoint))
            logger_.info("Node " + endpoint + " state jump to normal");

        // we don't want to update if this node is responsible for the token and it has a later startup time than endpoint.
        InetAddress currentNode = tokenMetadata_.getEndPoint(token);
        if (currentNode == null)
        {
            logger_.debug("New node " + endpoint + " at token " + token);
            tokenMetadata_.updateNormalToken(token, endpoint);
            if (!isClientMode)
                SystemTable.updateToken(endpoint, token);
        }
        else if (endpoint.equals(currentNode))
        {
            // nothing to do
        }
        else if (Gossiper.instance.compareEndpointStartup(endpoint, currentNode) > 0)
        {
            logger_.info(String.format("Nodes %s and %s have the same token %s.  %s is the new owner",
                                       endpoint, currentNode, token, endpoint));
            tokenMetadata_.updateNormalToken(token, endpoint);
            
            Gossiper.instance.replacedEndpoint(currentNode);
            
            if (!isClientMode)
                SystemTable.updateToken(endpoint, token);
        }
        else
        {
            logger_.info(String.format("Nodes %s and %s have the same token %s.  Ignoring %s",
                                       endpoint, currentNode, token, endpoint));

            Gossiper.instance.replacedEndpoint(endpoint);
        }

        if (pieces.length > 2)
        {
            if (REMOVE_TOKEN.equals(pieces[2]))
            {
                // remove token was called on a dead node.
                Token tokenThatLeft = getPartitioner().getTokenFactory().fromString(pieces[3]);
                InetAddress endpointThatLeft = tokenMetadata_.getEndPoint(tokenThatLeft);
                // let's make sure that we're not removing ourselves. This can happen when a node
                // enters ring as a replacement for a removed node. removeToken for the old node is
                // still in gossip, so we will see it.
                if (FBUtilities.getLocalAddress().equals(endpointThatLeft))
                {
                    logger_.info("Received removeToken gossip about myself. Is this node a replacement for a removed one?");
                    return;
                }
                logger_.debug("Token " + tokenThatLeft + " removed manually (endpoint was " + ((endpointThatLeft == null) ? "unknown" : endpointThatLeft) + ")");
                if (endpointThatLeft != null)
                {
                    removeEndPointLocally(endpointThatLeft);
                }
                tokenMetadata_.removeBootstrapToken(tokenThatLeft);
            }
        }

        calculatePendingRanges();
    }

    /**
     * Handle node preparing to leave the ring
     *
     * @param endPoint node
     * @param pieces STATE_LEAVING,token
     */
    private void handleStateLeaving(InetAddress endPoint, String[] pieces)
    {
        assert pieces.length == 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endPoint + " state leaving, token " + token);

        // If the node is previously unknown or tokens do not match, update tokenmetadata to
        // have this node as 'normal' (it must have been using this token before the
        // leave). This way we'll get pending ranges right.
        if (!tokenMetadata_.isMember(endPoint))
        {
            logger_.info("Node " + endPoint + " state jump to leaving");
            tokenMetadata_.updateNormalToken(token, endPoint);
        }
        else if (!tokenMetadata_.getToken(endPoint).equals(token))
        {
            logger_.warn("Node " + endPoint + " 'leaving' token mismatch. Long network partition?");
            tokenMetadata_.updateNormalToken(token, endPoint);
        }

        // at this point the endpoint is certainly a member with this token, so let's proceed
        // normally
        tokenMetadata_.addLeavingEndPoint(endPoint);
        calculatePendingRanges();
    }

    /**
     * Decides is application state reports dead state of the node
     * 
     * @param epState
     * @return true - application is not reported any state yet or reported dead one.
     */
    public static boolean isDeadState(EndPointState epState)
    {
        ApplicationState apState = epState.getApplicationState(MOVE_STATE);
        
        if (apState==null)
            return true; // every alive server must have MOVESTATE
        
        String apStateValue = apState.getValue();
        String[] pieces = apStateValue.split(DelimiterStr);
        assert (pieces.length > 0);        

        String moveName = pieces[0];

        return moveName.equals(STATE_HIBERNATE) || moveName.equals(STATE_LEFT);
    }
    
    /**
     * Handle node leaving the ring. This can be either because of decommission or loadbalance
     *
     * @param endPoint If reason for leaving is decommission or loadbalance
     * endpoint is the leaving node.
     * @param pieces STATE_LEFT,token
     */
    private void handleStateLeft(InetAddress endPoint, String[] pieces)
    {
        assert pieces.length == 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);        

        // endPoint itself is leaving
        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endPoint + " state left, token " + token);
        

        // If the node is member, remove all references to it. If not, call
        // removeBootstrapToken just in case it is there (very unlikely chain of events)
        if (tokenMetadata_.isMember(endPoint))
        {
            if (!tokenMetadata_.getToken(endPoint).equals(token))
                logger_.warn("Node " + endPoint + " 'left' token mismatch. Long network partition?");
            tokenMetadata_.removeEndpoint(endPoint);
        }

        // remove token from bootstrap tokens just in case it is still there
        tokenMetadata_.removeBootstrapToken(token);
        calculatePendingRanges();
    }

    /**
     * endPoint was completely removed from ring (as a result of removetoken command). Remove it
     * from token metadata and gossip and restore replica count.
     */
    private void removeEndPointLocally(InetAddress endPoint)
    {
        restoreReplicaCount(endPoint);
        Gossiper.instance.removeEndPoint(endPoint);
        // gossiper onRemove will take care of TokenMetadata
    }

    /**
     * Calculate pending ranges according to bootsrapping and leaving nodes. Reasoning is:
     *
     * (1) When in doubt, it is better to write too much to a node than too little. That is, if
     * there are multiple nodes moving, calculate the biggest ranges a node could have. Cleaning
     * up unneeded data afterwards is better than missing writes during movement.
     * (2) When a node leaves, ranges for other nodes can only grow (a node might get additional
     * ranges, but it will not lose any of its current ranges as a result of a leave). Therefore
     * we will first remove _all_ leaving tokens for the sake of calculation and then check what
     * ranges would go where if all nodes are to leave. This way we get the biggest possible
     * ranges with regard current leave operations, covering all subsets of possible final range
     * values.
     * (3) When a node bootstraps, ranges of other nodes can only get smaller. Without doing
     * complex calculations to see if multiple bootstraps overlap, we simply base calculations
     * on the same token ring used before (reflecting situation after all leave operations have
     * completed). Bootstrapping nodes will be added and removed one by one to that metadata and
     * checked what their ranges would be. This will give us the biggest possible ranges the
     * node could have. It might be that other bootstraps make our actual final ranges smaller,
     * but it does not matter as we can clean up the data afterwards.
     *
     * NOTE: This is heavy and ineffective operation. This will be done only once when a node
     * changes state in the cluster, so it should be manageable.
     */
    private void calculatePendingRanges()
    {
        for (String table : DatabaseDescriptor.getNonSystemTables())
            calculatePendingRanges(getReplicationStrategy(table), table);
    }

    // public & static for testing purposes
    public static void calculatePendingRanges(AbstractReplicationStrategy strategy, String table)
    {
        TokenMetadata tm = StorageService.instance.getTokenMetadata();
        Multimap<Range, InetAddress> pendingRanges = HashMultimap.create();
        Map<Token, InetAddress> bootstrapTokens = tm.getBootstrapTokens();
        Set<InetAddress> leavingEndPoints = tm.getLeavingEndPoints();

        if (bootstrapTokens.isEmpty() && leavingEndPoints.isEmpty())
        {
            if (logger_.isDebugEnabled())
                logger_.debug("No bootstrapping or leaving nodes -> empty pending ranges for " + table);
            tm.setPendingRanges(table, pendingRanges);
            return;
        }

        Multimap<InetAddress, Range> addressRanges = strategy.getAddressRanges(table);

        // Copy of metadata reflecting the situation after all leave operations are finished.
        TokenMetadata allLeftMetadata = tm.cloneAfterAllLeft();

        // get all ranges that will be affected by leaving nodes
        Set<Range> affectedRanges = new HashSet<Range>();
        for (InetAddress endPoint : leavingEndPoints)
            affectedRanges.addAll(addressRanges.get(endPoint));

        // for each of those ranges, find what new nodes will be responsible for the range when
        // all leaving nodes are gone.
        for (Range range : affectedRanges)
        {
            List<InetAddress> currentEndPoints = strategy.calculateNaturalEndpoints(range.right, tm, table);
            List<InetAddress> newEndPoints = strategy.calculateNaturalEndpoints(range.right, allLeftMetadata, table);
            newEndPoints.removeAll(currentEndPoints);
            pendingRanges.putAll(range, newEndPoints);
        }

        // At this stage pendingRanges has been updated according to leave operations. We can
        // now finish the calculation by checking bootstrapping nodes.

        // For each of the bootstrapping nodes, simply add and remove them one by one to
        // allLeftMetadata and check in between what their ranges would be.
        for (Map.Entry<Token, InetAddress> entry : bootstrapTokens.entrySet())
        {
            InetAddress endPoint = entry.getValue();

            allLeftMetadata.updateNormalToken(entry.getKey(), endPoint);
            for (Range range : strategy.getAddressRanges(allLeftMetadata, table).get(endPoint))
                pendingRanges.put(range, endPoint);
            allLeftMetadata.removeEndpoint(endPoint);
        }

        tm.setPendingRanges(table, pendingRanges);

        if (logger_.isDebugEnabled())
            logger_.debug("Pending ranges:\n" + (pendingRanges.isEmpty() ? "<empty>" : tm.printPendingRanges()));
    }

    /**
     * Called when an endPoint is removed from the ring without proper
     * STATE_LEAVING -> STATE_LEFT sequence. This function checks
     * whether this node becomes responsible for new ranges as a
     * consequence and streams data if needed.
     *
     * This is rather ineffective, but it does not matter so much
     * since this is called very seldom
     *
     * @param endPoint node that has left
     */
    private void restoreReplicaCount(InetAddress endPoint)
    {
        InetAddress myAddress = FBUtilities.getLocalAddress();

        for (String table : DatabaseDescriptor.getNonSystemTables())
        {
            // get all ranges that change ownership (that is, a node needs
            // to take responsibility for new range)
            Multimap<Range, InetAddress> changedRanges = getChangedRangesForLeaving(table, endPoint);

            // check if any of these ranges are coming our way
            Set<Range> myNewRanges = new HashSet<Range>();
            for (Map.Entry<Range, InetAddress> entry : changedRanges.entries())
            {
                if (entry.getValue().equals(myAddress))
                    myNewRanges.add(entry.getKey());
            }

            if (!myNewRanges.isEmpty())
            {
                if (logger_.isDebugEnabled())
                    logger_.debug(endPoint + " was removed, my added ranges: " + StringUtils.join(myNewRanges, ", "));

                Multimap<Range, InetAddress> rangeAddresses = getReplicationStrategy(table).getRangeAddresses(tokenMetadata_, table);
                Multimap<InetAddress, Range> sourceRanges = HashMultimap.create();
                IFailureDetector failureDetector = FailureDetector.instance;

                // find alive sources for our new ranges
                for (Range myNewRange : myNewRanges)
                {
                    List<InetAddress> sources = DatabaseDescriptor.getEndPointSnitch(table).getSortedListByProximity(myAddress, rangeAddresses.get(myNewRange));

                    assert (!sources.contains(myAddress));

                    for (InetAddress source : sources)
                    {
                        if (source.equals(endPoint))
                            continue;

                        if (failureDetector.isAlive(source))
                        {
                            sourceRanges.put(source, myNewRange);
                            break;
                        }
                    }
                }

                // Finally we have a list of addresses and ranges to
                // stream. Proceed to stream
                for (Map.Entry<InetAddress, Collection<Range>> entry : sourceRanges.asMap().entrySet())
                {
                    if (logger_.isDebugEnabled())
                        logger_.debug("Requesting from " + entry.getKey() + " ranges " + StringUtils.join(entry.getValue(), ", "));
                    StreamIn.requestRanges(entry.getKey(), table, entry.getValue());
                }
            }
        }
    }

    // needs to be modified to accept either a table or ARS.
    private Multimap<Range, InetAddress> getChangedRangesForLeaving(String table, InetAddress endpoint)
    {
        // First get all ranges the leaving endpoint is responsible for
        Collection<Range> ranges = getRangesForEndPoint(table, endpoint);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " ranges [" + StringUtils.join(ranges, ", ") + "]");

        Map<Range, ArrayList<InetAddress>> currentReplicaEndpoints = new HashMap<Range, ArrayList<InetAddress>>();

        // Find (for each range) all nodes that store replicas for these ranges as well
        for (Range range : ranges)
            currentReplicaEndpoints.put(range, getReplicationStrategy(table).calculateNaturalEndpoints(range.right, tokenMetadata_, table));

        TokenMetadata temp = tokenMetadata_.cloneAfterAllLeft();

        // endpoint might or might not be 'leaving'. If it was not leaving (that is, removetoken
        // command was used), it is still present in temp and must be removed.
        if (temp.isMember(endpoint))
            temp.removeEndpoint(endpoint);

        Multimap<Range, InetAddress> changedRanges = HashMultimap.create();

        // Go through the ranges and for each range check who will be
        // storing replicas for these ranges when the leaving endpoint
        // is gone. Whoever is present in newReplicaEndpoins list, but
        // not in the currentReplicaEndpoins list, will be needing the
        // range.
        for (Range range : ranges)
        {
            ArrayList<InetAddress> newReplicaEndpoints = getReplicationStrategy(table).calculateNaturalEndpoints(range.right, temp, table);
            newReplicaEndpoints.removeAll(currentReplicaEndpoints.get(range));
            if (logger_.isDebugEnabled())
                if (newReplicaEndpoints.isEmpty())
                    logger_.debug("Range " + range + " already in all replicas");
                else
                    logger_.debug("Range " + range + " will be responsibility of " + StringUtils.join(newReplicaEndpoints, ", "));
            changedRanges.putAll(range, newReplicaEndpoints);
        }

        return changedRanges;
    }

    public void onJoin(InetAddress endpoint, EndPointState epState)
    {
        for (Map.Entry<String,ApplicationState> entry : epState.getSortedApplicationStates())
        {
            onChange(endpoint, entry.getKey(), entry.getValue());
        }
    }

    public void onAlive(InetAddress endpoint, EndPointState state)
    {
        if (!isClientMode)
            deliverHints(endpoint);
    }

    public void onRemove(InetAddress endpoint)
    {
        tokenMetadata_.removeEndpoint(endpoint);
        calculatePendingRanges();
    }

    public void onDead(InetAddress endpoint, EndPointState state)
    {
        MessagingService.instance.convict(endpoint);
    }

    /** raw load value */
    public double getLoad()
    {
        double bytes = 0;
        for (String tableName : DatabaseDescriptor.getTables())
        {
            Table table;
            try
            {
                table = Table.open(tableName);
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
            for (String cfName : table.getColumnFamilies())
            {
                ColumnFamilyStore cfs = table.getColumnFamilyStore(cfName);
                bytes += cfs.getLiveDiskSpaceUsed();
            }
        }
        return bytes;
    }

    public String getLoadString()
    {
        return FileUtils.stringifyFileSize(getLoad());
    }

    /**
     * @return the recentReadRepairs
     */
    public long getRecentReadRepairs()
    {
        return recentReadRepairs.getAndSet(0);
    }
    
    public void countReadRepair()
    {
        recentReadRepairs.incrementAndGet();
    }


    public Map<String, String> getLoadMap()
    {
        Map<String, String> map = new HashMap<String, String>();
        for (Map.Entry<InetAddress,Double> entry : StorageLoadBalancer.instance.getLoadInfo().entrySet())
        {
            map.put(entry.getKey().getHostAddress(), FileUtils.stringifyFileSize(entry.getValue()));
        }
        // gossiper doesn't see its own updates, so we need to special-case the local node
        map.put(FBUtilities.getLocalAddress().getHostAddress(), getLoadString());
        return map;
    }
    
    public Map<String,String> getLocationsMap()
    {
        HashMap<String, String> lmap = new HashMap<String, String>();
        List<String> tables = DatabaseDescriptor.getNonSystemTables();
        for (Token t : tokenMetadata_.sortedTokens() )
        {
            InetAddress endPoint = tokenMetadata_.getEndPoint(t);
            
            String dc = null, rack = null;
            
            for (String table : tables)
            {
                if (dc==null)
                    dc = DatabaseDescriptor.getEndPointSnitch(table).getDatacenter(endPoint);
                if (rack == null)
                    rack = DatabaseDescriptor.getEndPointSnitch(table).getRack(endPoint);
            }
            
            if (dc !=null && rack !=null)
                lmap.put(endPoint.getHostAddress(),dc+":"+rack);
        }
        
        return lmap;
    }

    public Map<String,String> getEndpointNames()
    {
        HashMap<String, String> lmap = new HashMap<String, String>();
        List<String> tables = DatabaseDescriptor.getNonSystemTables();
        for (Token t : tokenMetadata_.sortedTokens() )
        {
            InetAddress endPoint = tokenMetadata_.getEndPoint(t);
            
            String dc = null;
            
            for (String table : tables)
            {
                if (dc==null)
                    dc = DatabaseDescriptor.getEndPointSnitch(table).getEndpointName(endPoint);
            }
            
            if (dc !=null)
                lmap.put(endPoint.getHostAddress(),dc);
        }
        
        return lmap;
    }

    /**
     * Deliver hints to the specified node when it has crashed
     * and come back up/ marked as alive after a network partition
    */
    public final void deliverHints(InetAddress endpoint)
    {
        HintedHandOffManager.instance().deliverHints(endpoint);
    }

    public final void deliverHints(String host) throws UnknownHostException
    {
        HintedHandOffManager.instance().deliverHints(host);
    }
    
    @Override
    public int getHintlogPlayBatchSize()
    {
        return DatabaseDescriptor.getHintLogPlayBatchSize();
    }
    
    @Override
    public void setHintlogPlayBatchSize(int newsize)
    {
        DatabaseDescriptor.setHintLogPlayBatchSize(newsize);
    }

    @Override
    public long getHintlogPlayBatchBytes()
    {
        return DatabaseDescriptor.getHintLogPlayBatchBytes();
    }
    
    @Override
    public void setHintlogPlayBatchBytes(long newsize)
    {
        DatabaseDescriptor.setHintLogPlayBatchBytes(newsize);
    }

    public Token getLocalToken()
    {
        return storageMetadata_.getToken();
    }

    /* This methods belong to the MBean interface */

    public String getToken()
    {
        return getLocalToken().toString();
    }

    public Set<String> getLiveNodes()
    {
        return stringify(Gossiper.instance.getLiveMembers());
    }

    public Set<String> getUnreachableNodes()
    {
        return stringify(Gossiper.instance.getUnreachableMembers());
    }

    private Set<String> stringify(Collection<InetAddress> endPoints)
    {
        Set<String> stringEndPoints = new HashSet<String>();
        for (InetAddress ep : endPoints)
        {
            stringEndPoints.add(ep.getHostAddress());
        }
        return stringEndPoints;
    }

    private List<String> stringify(List<InetAddress> endPoints)
    {
        List<String> stringEndPoints = new ArrayList<String>();
        for (InetAddress ep : endPoints)
        {
            stringEndPoints.add(ep.getHostAddress());
        }
        return stringEndPoints;
    }

    public int getCurrentGenerationNumber()
    {
        return Gossiper.instance.getCurrentGenerationNumber(FBUtilities.getLocalAddress());
    }

    public void forceTableCleanup() throws IOException
    {
        List<String> tables = DatabaseDescriptor.getNonSystemTables();
        for (String tName : tables)
        {
            Table table = Table.open(tName);
            table.forceCleanup();
        }
    }
    public void forceTableCleanup(String tableName, String... columnFamilies) throws IOException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            cfStore.forceCleanup();
        }
    }
    
    public void forceTableCompaction() throws IOException
    {
        for (Table table : Table.all())
            table.forceCompaction();
    }
    public void forceTableCompaction(String ks, String... columnFamilies) throws IOException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(ks, columnFamilies))
        {
            cfStore.forceMajorCompaction();
        }
    }

    /**
     * Takes the snapshot for a given table.
     *
     * @param tableName the name of the table.
     * @param tag   the tag given to the snapshot (null is permissible)
     */
    public void takeSnapshot(String tableName, String tag) throws IOException
    {
        Table tableInstance = getValidTable(tableName);
        tableInstance.snapshot(tag);
    }

    /**
     * Takes a snapshot for every table. System table will be fully snapshotted,
     * column families in other keyspaces will be filtered using <code>cfNameRegExp</code>
     *
     * @param cfNameRegExp regexp for column families selection for snapshot
     * @param tag   the tag given to the snapshot (null is permissible)
     */
    public void takeAllSnapshot(String cfNameRegExp, String tag) throws IOException
    {
        for (Table table : Table.all())
        {
            if (Table.SYSTEM_TABLE.equals(table.name))
            {
                table.snapshot(tag);
            }
            else
            {
                table.snapshot(cfNameRegExp, tag);
            }
        }
    }

    private Table getValidTable(String tableName) throws IOException
    {
        if (!DatabaseDescriptor.getTables().contains(tableName))
        {
            throw new IOException("Table " + tableName + " does not exist");
        }
        return Table.open(tableName);
    }

    /**
     * Takes a snapshot for every table.
     *
     * @param tag the tag given to the snapshot (null is permissible)
     */
    public void takeAllSnapshot(String tag) throws IOException
    {
        for (Table table : Table.all())
            table.snapshot(tag);
    }

    /**
     * Remove all the existing snapshots.
     */
    public void clearSnapshot() throws IOException
    {
        for (Table table : Table.all())
            table.clearSnapshot();

        if (logger_.isDebugEnabled())
            logger_.debug("Cleared out all snapshot directories");
    }

    public Iterable<ColumnFamilyStore> getValidColumnFamilies(String tableName, String... columnFamilies) throws IOException
    {
        Table table = getValidTable(tableName);
        Set<ColumnFamilyStore> valid = new HashSet<ColumnFamilyStore>();

        for (String cfName : columnFamilies.length == 0 ? table.getColumnFamilies() : Arrays.asList(columnFamilies))
        {
            ColumnFamilyStore cfStore = table.getColumnFamilyStore(cfName);
            if (cfStore == null)
            {
                // column family not recognized by exact name, trying wildcard match
                Pattern cfPattern = Pattern.compile(cfName);
                boolean matched = false;
                for (ColumnFamilyStore cfTest : table.getColumnFamilyStores())
                {
                    if ( cfPattern.matcher(cfTest.getColumnFamilyName()).matches() )
                    {
                        if (!matched)
                        {
                            logger_.warn(String.format("Column Family %s interpreted as pattern", cfName));
                            matched = true;
                        }
                        
                        valid.add ( cfTest );
                    }
                }
                
                if (!matched)
                {
                    // this means there was a cf passed in that is not recognized in the keyspace. report it and continue.
                    logger_.warn(String.format("Invalid column family specified: %s. Proceeding with others.", cfName));
                }
                
                continue;
            }
            valid.add(cfStore);
        }
        return valid;
    }

    /**
     * Flush all memtables for a table and column families.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableFlush(final String tableName, final String... columnFamilies) throws IOException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            logger_.debug("Forcing binary flush on keyspace " + tableName + ", CF " + cfStore.getColumnFamilyName());
            cfStore.forceFlushBinary();
            logger_.debug("Forcing flush on keyspace " + tableName + ", CF " + cfStore.getColumnFamilyName());
            cfStore.forceFlush();
        }
    }

    /**
     * Trigger proactive repair for a table and column families.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableRepair(final String tableName, final String... columnFamilies) throws IOException
    {
        // request that all relevant endpoints generate trees
        final MessagingService ms = MessagingService.instance;
        final Set<InetAddress> endpoints = AntiEntropyService.getNeighbors(tableName);
        endpoints.add(FBUtilities.getLocalAddress());
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            if (isInLocalRange(cfStore))
            {
                // MM: only ask for repair if column family can have any data for this CF (it is impossible for domain splitted CF, if its domain is out of range)
                StringBuilder prettyEndpoints = new StringBuilder();
                Message request = TreeRequestVerbHandler.makeVerb(tableName, cfStore.getColumnFamilyName());
                for (InetAddress endpoint : endpoints)
                {
                    if (isInRemoteRange(cfStore, endpoint))
                    {
                        ms.sendOneWay(request, endpoint);
                        prettyEndpoints.append(endpoint.getHostAddress()).append(' ');
                    }
                }

                logger_.info("Repair: requesting tree for "+cfStore.getColumnFamilyName()+" from "+prettyEndpoints);
            }
            else
            {
                logger_.info("Repair-skip: skipping repair for "+cfStore.getColumnFamilyName()+" - it is out of local nodes range" );
                
            }
        }
    }
    
    public boolean isInLocalRange(ColumnFamilyStore cfs)
    {
        String table = cfs.getTable().name;
        CFMetaData cfMetaData = DatabaseDescriptor.getCFMetaData(table, cfs.getColumnFamilyName());
        
        if (!cfMetaData.domainSplit)
            return true;
        
        // this is domain split column familily. Determining can possibly it have records of local node's range
        for (Range range : getLocalRanges(table)) 
        {
            if (range.contains( cfMetaData.domainMinToken ))
                return true;
        }
        
        return false;
    }

    public boolean isInRemoteRange(ColumnFamilyStore cfs, InetAddress endpoint)
    {
        String table = cfs.getTable().name;
        CFMetaData cfMetaData = DatabaseDescriptor.getCFMetaData(table, cfs.getColumnFamilyName());
        
        if (!cfMetaData.domainSplit)
            return true;
        
        // this is domain split column familily. Determining can possibly it have records of local node's range
        for (Range range : getRangesForEndPoint(table, endpoint)) 
        {
            if (range.contains( cfMetaData.domainMinToken ))
                return true;
        }
        
        return false;
    }

    /* End of MBean interface methods */

    /**
     * This method returns the predecessor of the endpoint ep on the identifier
     * space.
     */
    InetAddress getPredecessor(InetAddress ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndPoint(tokenMetadata_.getPredecessor(token));
    }

    /*
     * This method returns the successor of the endpoint ep on the identifier
     * space.
     */
    public InetAddress getSuccessor(InetAddress ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndPoint(tokenMetadata_.getSuccessor(token));
    }

    /**
     * Get the primary range for the specified endpoint.
     * @param ep endpoint we are interested in.
     * @return range for the specified endpoint.
     */
    public Range getPrimaryRangeForEndPoint(InetAddress ep)
    {
        return tokenMetadata_.getPrimaryRangeFor(tokenMetadata_.getToken(ep));
    }

    /**
     * Get all ranges an endpoint is responsible for.
     * @param ep endpoint we are interested in.
     * @return ranges for the specified endpoint.
     */
    Collection<Range> getRangesForEndPoint(String table, InetAddress ep)
    {
        return getReplicationStrategy(table).getAddressRanges(table).get(ep);
    }
    
    /**
     * Get all ranges that span the ring. All ranges are in sorted order of
     * ranges.
     * @return ranges in sorted order
    */
    public List<Range> getAllRanges()
    {
        return getAllRanges(tokenMetadata_.sortedTokens());
    }

    /**
     * Get all ranges that span the ring given a set
     * of tokens. All ranges are in sorted order of
     * ranges.
     * @return ranges in sorted order
    */
    public List<Range> getAllRanges(List<Token> sortedTokens)
    {
        if (logger_.isDebugEnabled())
            logger_.debug("computing ranges for " + StringUtils.join(sortedTokens, ", "));

        if (sortedTokens.isEmpty()) 
            return Collections.emptyList();
        List<Range> ranges = new ArrayList<Range>();
        int size = sortedTokens.size();
        for (int i = 1; i < size; ++i)
        {
            Range range = new Range(sortedTokens.get(i - 1), sortedTokens.get(i));
            ranges.add(range);
        }
        Range range = new Range(sortedTokens.get(size - 1), sortedTokens.get(0));
        ranges.add(range);

        return ranges;
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public List<InetAddress> getNaturalEndpoints(String table, String key)
    {
        return getNaturalEndpoints(table, partitioner_.getToken(key));
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param token - token for which we need to find the endpoint return value -
     * the endpoint responsible for this token
     */
    public List<InetAddress> getNaturalEndpoints(String table, Token token)
    {
        return getReplicationStrategy(table).getNaturalEndpoints(token, table);
    }

    /**
     * This method attempts to return N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public List<InetAddress> getLiveNaturalEndpoints(String table, String key)
    {
        return getLiveNaturalEndpoints(table, partitioner_.getToken(key));
    }

    public List<InetAddress> getLiveNaturalEndpoints(String table, Token token)
    {
        List<InetAddress> liveEps = new ArrayList<InetAddress>();
        List<InetAddress> endpoints = getReplicationStrategy(table).getNaturalEndpoints(token, table);

        for (InetAddress endpoint : endpoints)
        {
            if (FailureDetector.instance.isAlive(endpoint))
                liveEps.add(endpoint);
        }

        return liveEps;
    }

    /**
     * This function finds the closest live endpoint that contains a given key.
     */
    public InetAddress findSuitableEndPoint(String table, String key) throws IOException, UnavailableException
    {
        List<InetAddress> endpoints = getNaturalEndpoints(table, key);
        DatabaseDescriptor.getEndPointSnitch(table).sortByProximity(FBUtilities.getLocalAddress(), endpoints);
        for (InetAddress endpoint : endpoints)
        {
            if (FailureDetector.instance.isAlive(endpoint))
                return endpoint;
        }
        throw new UnavailableException(); // no nodes that could contain key are alive
    }

    /**
     * This function finds the all live endpoints that contains a given key sorted by proximity
     */
    public List<InetAddress> findSuitableEndPoints(String table, String key) throws IOException, UnavailableException
    {
        List<InetAddress> endpoints = getLiveNaturalEndpoints(table, key);
        if (endpoints.size()==0)
            throw new UnavailableException(); // no nodes that could contain key are alive
            
        return DatabaseDescriptor.getEndPointSnitch(table).sortByProximity(FBUtilities.getLocalAddress(), endpoints);
    }

    public Map<String, String> getStringEndpointMap()
    {
        HashMap<String, String> map = new HashMap<String, String>();
        for (Token t : tokenMetadata_.sortedTokens())
        {
            map.put(t.toString(), tokenMetadata_.getEndPoint(t).getHostAddress());
        }
        return map;
    }

    public void setLog4jLevel(String classQualifier, String rawLevel)
    {
        Level level = Level.toLevel(rawLevel);
        Logger.getLogger(classQualifier).setLevel(level);
        logger_.info("set log level to " + level + " for classes under '" + classQualifier + "' (if the level doesn't look like '" + rawLevel + "' then log4j couldn't parse '" + rawLevel + "')");
    }

    /**
     * @return list of Tokens (_not_ keys!) breaking up the data this node is responsible for into pieces of roughly keysPerSplit
     */ 
    public List<Token> getSplits(Range range, int keysPerSplit)
    {
        List<Token> tokens = new ArrayList<Token>();
        // we use the actual Range token for the first and last brackets of the splits to ensure correctness
        tokens.add(range.left);

        List<DecoratedKey> keys = new ArrayList<DecoratedKey>();
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            for (IndexSummary.KeyPosition info: cfs.allIndexPositions())
            {
                if (range.contains(info.key.token))
                    keys.add(info.key);
            }
        }
        FBUtilities.sortSampledKeys(keys, range);
        int splits = keys.size() * DatabaseDescriptor.getIndexInterval() / keysPerSplit;

        if (keys.size() >= splits)
        {
            for (int i = 1; i < splits; i++)
            {
                int index = i * (keys.size() / splits);
                tokens.add(keys.get(index).token);
            }
        }

        tokens.add(range.right);
        return tokens;
    }

    /** return a token to which if a node bootstraps it will get about 1/2 of this node's range */
    public Token getBootstrapToken()
    {
        Range range = getLocalPrimaryRange();
        List<DecoratedKey> keys = new ArrayList<DecoratedKey>();
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            for (IndexSummary.KeyPosition info: cfs.allIndexPositions())
            {
                if (range.contains(info.key.token))
                    keys.add(info.key);
            }
        }
        FBUtilities.sortSampledKeys(keys, range);

        if (keys.size() < 3)
            return partitioner_.midpoint(range.left, range.right);
        else
            return keys.get(keys.size() / 2).token;
    }

    /**
     * Broadcast leaving status and update local tokenMetadata_ accordingly
     */
    private void startLeaving()
    {
        Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_LEAVING + Delimiter + getLocalToken().toString());
        tokenMetadata_.addLeavingEndPoint(FBUtilities.getLocalAddress());
        calculatePendingRanges();
    }

    public void decommission() throws InterruptedException
    {
        if (!tokenMetadata_.isMember(FBUtilities.getLocalAddress()))
            throw new UnsupportedOperationException("local node is not a member of the token ring yet");
        if (tokenMetadata_.cloneAfterAllLeft().sortedTokens().size() < 2)
            throw new UnsupportedOperationException("no other normal nodes in the ring; decommission would be pointless");
        for (String table : DatabaseDescriptor.getNonSystemTables())
        {
            if (tokenMetadata_.getPendingRanges(table, FBUtilities.getLocalAddress()).size() > 0)
                throw new UnsupportedOperationException("data is currently moving to this node; unable to leave the ring");
        }

        if (logger_.isDebugEnabled())
            logger_.debug("DECOMMISSIONING");
        startLeaving();
        setMode("Leaving: sleeping " + RING_DELAY + " ms for pending range setup", true);
        Thread.sleep(RING_DELAY);

        Runnable finishLeaving = new Runnable()
        {
            public void run()
            {
                Gossiper.instance.stop();
                MessagingService.shutdown();
                StageManager.shutdownNow();
                setMode("Decommissioned", true);
                
                System.exit(0);
            }
        };
        unbootstrap(finishLeaving);
    }

    private void leaveRing()
    {
        SystemTable.setBootstrapped(false);
        tokenMetadata_.removeEndpoint(FBUtilities.getLocalAddress());
        calculatePendingRanges();

        Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_LEFT + Delimiter + partitioner_.getTokenFactory().toString(getLocalToken()));
        try
        {
            Thread.sleep(2 * Gossiper.intervalInMillis_);
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
    }

    private void unbootstrap(final Runnable onFinish)
    {
        final CountDownLatch latch = new CountDownLatch(DatabaseDescriptor.getNonSystemTables().size());
        for (final String table : DatabaseDescriptor.getNonSystemTables())
        {
            Multimap<Range, InetAddress> rangesMM = getChangedRangesForLeaving(table, FBUtilities.getLocalAddress());
            if (logger_.isDebugEnabled())
                logger_.debug("Ranges needing transfer are [" + StringUtils.join(rangesMM.keySet(), ",") + "]");
            if (rangesMM.isEmpty())
            {
                latch.countDown();
                continue;
            }

            setMode("Leaving: streaming data to other nodes", true);
            final Set<Map.Entry<Range, InetAddress>> pending = Collections.synchronizedSet(new HashSet<Map.Entry<Range, InetAddress>>(rangesMM.entries()));
            for (final Map.Entry<Range, InetAddress> entry : rangesMM.entries())
            {
                final Range range = entry.getKey();
                final InetAddress newEndpoint = entry.getValue();
                final Runnable callback = new Runnable()
                {
                    public void run()
                    {
                        pending.remove(entry);
                        if (pending.isEmpty())
                            latch.countDown();
                    }
                };
                StageManager.getStage(StageManager.STREAM_STAGE).execute(new Runnable()
                {
                    public void run()
                    {
                        // TODO each call to transferRanges re-flushes, this is potentially a lot of waste
                        StreamOut.transferRanges(newEndpoint, table, Arrays.asList(range), callback);
                    }
                });
            }
        }

        // wait for the transfer runnables to signal the latch.
        logger_.debug("waiting for stream aks.");
        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        logger_.debug("stream acks all received.");
        leaveRing();
        onFinish.run();
    }

    public void move(String newToken) throws IOException, InterruptedException
    {
        move(partitioner_.getTokenFactory().fromString(newToken));
    }

    public void loadBalance() throws IOException, InterruptedException
    {
        move((Token)null);
    }

    /**
     * move the node to new token or find a new token to boot to according to load
     *
     * @param token new token to boot to, or if null, find balanced token to boot to
     */
    private void move(final Token token) throws IOException, InterruptedException
    {
        for (String table : DatabaseDescriptor.getTables())
        {
            if (tokenMetadata_.getPendingRanges(table, FBUtilities.getLocalAddress()).size() > 0)
                throw new UnsupportedOperationException("data is currently moving to this node; unable to leave the ring");
        }
        if (token != null && tokenMetadata_.sortedTokens().contains(token))
            throw new IOException("target token " + token + " is already owned by another node");

        if (logger_.isDebugEnabled())
            logger_.debug("Leaving: old token was " + getLocalToken());
        startLeaving();
         setMode("Leaving: sleeping " + RING_DELAY + " ms for pending range setup", true);
        Thread.sleep(RING_DELAY);

        Runnable finishMoving = new WrappedRunnable()
        {
            public void runMayThrow() throws IOException
            {
                Token bootstrapToken = token;
		if (bootstrapToken == null)
		{
		    StorageLoadBalancer.instance.waitForLoadInfo();
		    bootstrapToken = BootStrapper.getBalancedToken(tokenMetadata_, StorageLoadBalancer.instance.getLoadInfo());
		}
                logger_.info("re-bootstrapping to new token " + bootstrapToken);
                startBootstrap(bootstrapToken);
            }
        };
        unbootstrap(finishMoving);
    }

    public void removeToken(String tokenString)
    {
        Token token = partitioner_.getTokenFactory().fromString(tokenString);

        // Here we could refuse the operation from continuing if we
        // cannot find the endpoint for this token from metadata, but
        // that would prevent this command from being issued by a node
        // that has never seen the failed node.
        InetAddress endPoint = tokenMetadata_.getEndPoint(token);
        if (endPoint != null)
        {
            if (endPoint.equals(FBUtilities.getLocalAddress()))
                throw new UnsupportedOperationException("Cannot remove node's own token");

            // Let's make sure however that we're not removing a live
            // token (member)
            if (Gossiper.instance.getLiveMembers().contains(endPoint))
                throw new UnsupportedOperationException("Node " + endPoint + " is alive and owns this token. Use decommission command to remove it from the ring");

            removeEndPointLocally(endPoint);
            calculatePendingRanges();
        }

        // bundle two states together. include this nodes state to keep the status quo, but indicate the leaving token so that it can be dealt with.
        Gossiper.instance.addLocalApplicationState(MOVE_STATE, STATE_NORMAL + Delimiter + partitioner_.getTokenFactory().toString(getLocalToken()) + Delimiter + REMOVE_TOKEN + Delimiter + partitioner_.getTokenFactory().toString(token));
    }

    public WriteResponseHandler getWriteResponseHandler(int blockFor, int writeEndpointCount, ConsistencyLevel consistency_level, String table)
    {
        return getReplicationStrategy(table).getWriteResponseHandler(blockFor, writeEndpointCount, consistency_level, table);
    }

    public boolean isClientMode()
    {
        return isClientMode;
    }

    public synchronized void requestGC()
    {
        if (hasUnreclaimedSpace())
        {
            logger_.info("requesting GC to free disk space");
            System.gc();
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }
    }

    private boolean hasUnreclaimedSpace()
    {
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            if (cfs.hasUnreclaimedSpace())
                return true;
        }
        return false;
    }

    public String getOperationMode()
    {
        return operationMode;
    }
    

    /**
     * Resume  node paused before joining the ring during bootstrap
     */
    public synchronized void completeBootstrap() 
    {
       logger_.info("Resume bootstrarp, joining reing.");
       this.notify(); 
        
    }
    /** shuts node off to writes, empties memtables and the commit log. */
    public synchronized void drain() throws IOException, InterruptedException, ExecutionException
    {
        ExecutorService mutationStage = StageManager.getStage(StageManager.MUTATION_STAGE);
        if (mutationStage.isTerminated())
        {
            logger_.warn("Cannot drain node (did it already happen?)");
            return;
        }
        setMode("Starting drain process", true);
        Gossiper.instance.stop();
        setMode("Draining: shutting down MessageService", false);
        MessagingService.shutdown();
        setMode("Draining: emptying MessageService pools", false);
        MessagingService.waitFor();

        setMode("Draining: clearing mutation stage", false);
        mutationStage.shutdown();
        mutationStage.awaitTermination(3600, TimeUnit.SECONDS);
       
        // lets flush.
        setMode("Draining: flushing column families", false);
        for (String tableName : DatabaseDescriptor.getTables())
            for (Future f : Table.open(tableName).flush())
                f.get();
       

        ColumnFamilyStore.postFlushExecutor.shutdown();
        ColumnFamilyStore.postFlushExecutor.awaitTermination(60, TimeUnit.SECONDS);
        CommitLog.instance().forceNewSegment();
        
        HintLog.instance().close();
        // want to make sure that any segments deleted as a result of flushing are gone.
        DeletionService.waitFor();
       
        setMode("Node is drained", true);
    }

    public void saveCaches() throws ExecutionException, InterruptedException
    {
        List<Future<?>> futures = new ArrayList<Future<?>>();
        logger_.debug("submitting cache saves");
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            futures.add(cfs.submitKeyCacheWrite());
            futures.add(cfs.submitRowCacheWrite());
        }
        FBUtilities.waitOnFutures(futures);
        logger_.debug("cache saves completed");
    }

    // Never ever do this at home. Used by tests.
    Map<String, AbstractReplicationStrategy> setReplicationStrategyUnsafe(Map<String, AbstractReplicationStrategy> replacement)
    {
        Map<String, AbstractReplicationStrategy> old = replicationStrategies;
        replicationStrategies = replacement;
        return old;
    }

    // Never ever do this at home. Used by tests.
    IPartitioner setPartitionerUnsafe(IPartitioner newPartitioner)
    {
        IPartitioner oldPartitioner = partitioner_;
        partitioner_ = newPartitioner;
        return oldPartitioner;
    }

    TokenMetadata setTokenMetadataUnsafe(TokenMetadata tmd)
    {
        TokenMetadata old = tokenMetadata_;
        tokenMetadata_ = tmd;
        return old;
    }
    
    /* (non-Javadoc)
     * @see org.apache.cassandra.service.StorageServiceMBean#getMemtableThroughput()
     */
    @Override
    public int getMemtableThroughput()
    {
        return DatabaseDescriptor.getMemtableThroughput();
    }
    
    /* (non-Javadoc)
     * @see org.apache.cassandra.service.StorageServiceMBean#setMemtableThroughput(int)
     */
    @Override
    public void setMemtableThroughput(int throughputMBytes)
    {
        DatabaseDescriptor.setMemtableThroughput(throughputMBytes);
    }
    
    /* (non-Javadoc)
     * @see org.apache.cassandra.service.StorageServiceMBean#getMemtableOperations()
     */
    @Override
    public double getMemtableOperations()
    {
        return DatabaseDescriptor.getMemtableOperations();
    }
    
    /* (non-Javadoc)
     * @see org.apache.cassandra.service.StorageServiceMBean#setMemtableOperations(double)
     */
    @Override
    public void setMemtableOperations(double operations)
    {
        DatabaseDescriptor.setMemtableOperations(operations);
    }
    
    public Map<Token, Float> getOwnership()
    {
        List<Range> ranges = new ArrayList<Range>(getRangeToEndPointMap(null).keySet());
        List<Token> sortedTokens = new ArrayList<Token>();
        for(Range r : ranges) { sortedTokens.add(r.left); }
        Collections.sort(sortedTokens);

        return partitioner_.describeOwnership(sortedTokens);
    }
    
    public String gossipInfo() {
        return "ACTUAL:\n"+Gossiper.instance.dumpState()+"\nPERSISTENT:\n"+Gossiper.instance.dumpPersistentState();
    }
    
    public void gossipStop() {
        logger_.warn("Stopping gossip by operator request");
        Gossiper.instance.stop();
    }
    
    public void gossipStart() {
        logger_.warn("Starting gossip by operator request");
        Gossiper.instance.start();
    }
    
    public void gossipPurgePersistent() {
        logger_.warn("Removing persistent gossip state by operator request");
        SystemTable.removeEndpointStates();
    }
    
    public int getMaxCommitLogSegmentsActive() {
        return DatabaseDescriptor.getMaxCommitLogSegmentsActive();
    }

    public void setMaxCommitLogSegmentsActive(int maxCount) {
        DatabaseDescriptor.setMaxCommitLogSegmentsActive(maxCount);
    }
}
