/*
 * @(#) HintLogHandoffManager.java
 * Created Aug 31, 2011 by oleg
 * (C) ONE, SIA
 */
package org.apache.cassandra.db.hints;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.HintedHandOffManager;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.DigestMismatchException;
import org.apache.cassandra.service.WriteResponseHandler;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.log4j.Logger;

/**
 * Implementation of {@link HintedHandOffManager} using HintLogs for hist storage.
 * 
 * @author Oleg Anastasyev<oa@hq.one.lv>
 *
 */
public class HintLogHandoffManager extends HintedHandOffManager
{
    private static final Logger logger_ = Logger.getLogger(HintedHandOffManager.class);

    /* (non-Javadoc)
     * @see org.apache.cassandra.db.HintedHandOffManager#deliverHintsToEndpoint(java.net.InetAddress)
     */
    @Override
    protected void deliverHintsToEndpoint(InetAddress endPoint)
            throws IOException, DigestMismatchException,
            InvalidRequestException, TimeoutException
    {
        queuedDeliveries.remove(endPoint);
        if (logger_.isDebugEnabled())
            logger_.debug("Check hintlog for deliverables for endPoint " + endPoint.getHostAddress());
        
        if (!FailureDetector.instance.isAlive(endPoint))
        {
            logger_.info("Hints delivery to "+endPoint.getHostAddress()+" is cancelled - endpoint is dead. Will restart as soon as it gets UP again");
            return;
        }

        long started=System.currentTimeMillis();
        long counter = 0;

        Iterator<byte[]> hintsToDeliver = HintLog.instance().getHintsToDeliver(endPoint);
        String throttleRaw = System.getProperty("hinted_handoff_throttle");
        int throttle = throttleRaw == null ? 0 : Integer.valueOf(throttleRaw);
        
        if (hintsToDeliver.hasNext())
            logger_.info("Started hinted handoff for endPoint " + endPoint.getHostAddress());

        
        HINT_DELIVERY:
        while (hintsToDeliver.hasNext())
        {
            byte[] rm = hintsToDeliver.next();

            while (!deliverHint(endPoint, rm))
            {
                // may be this is temporary problem. Trying to pause for some time.
                try {
                    Thread.sleep(DatabaseDescriptor.getRpcTimeout());
                } catch (InterruptedException e) {
                    break HINT_DELIVERY;
                }
                
                // checking, is endpoint still in ring
                if (!FailureDetector.instance.isAlive(endPoint))
                {
                    logger_.info("Hints delivery to "+endPoint.getHostAddress()+" is cancelled - endpoint is dead. Will restart as soon as it gets UP again");
                    break HINT_DELIVERY;
                }
            }
            
            hintsToDeliver.remove();
            counter ++;
            
            if (throttle>0)
            {
                try
                {
                    Thread.sleep(throttle);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
            }
        }

        if (counter>0)
            logger_.info("Finished hinted handoff for endPoint " + endPoint.getHostAddress() + " total "+counter+" mutations delivered in " + (System.currentTimeMillis()-started)/1000+" seconds");
        else
            logger_.info("Finished hinted handoff check for endPoint " + endPoint.getHostAddress() + " in " + (System.currentTimeMillis()-started)/1000+" seconds");
        
    }

    private boolean deliverHint(InetAddress endPoint, byte[] rm)
            throws IOException
    {
        Message message = RowMutation.makeRowMutationMessage(rm);
        WriteResponseHandler responseHandler = new WriteResponseHandler(1, 1, RowMutation.tableNameSerializer_().deserialize(new DataInputStream(new ByteArrayInputStream(rm))));
        MessagingService.instance.sendRR(message, endPoint, responseHandler);
        try
        {
            responseHandler.get();
            
            return true;
        }
        catch (TimeoutException e)
        {
            return false;
        }
    }

    /**
     * Stores new hint for later delivery
     * 
     * @param hint
     * @param rm
     * @throws IOException 
     */
    public void storeHint(InetAddress hint, RowMutation rm, byte[] serializedMutation) throws IOException 
    {
        HintLog.instance().add(hint, serializedMutation);
    };
    
}