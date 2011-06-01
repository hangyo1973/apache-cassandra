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

package org.apache.cassandra.utils;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.cassandra.io.ICompactSerializer;
import org.apache.cassandra.utils.obs.OpenBitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BloomFilter extends Filter
{

    private static final Logger logger = LoggerFactory.getLogger(BloomFilter.class);
    private static final int EXCESS = 20;
    static ICompactSerializer<BloomFilter> serializer_ = new BloomFilterSerializer();

    public OpenBitSet bitset;
    
    private ByteBuffer strBuffer;

    BloomFilter(int hashes, OpenBitSet bs)
    {
        hashCount = hashes;
        bitset = bs;
    }

    public static ICompactSerializer<BloomFilter> serializer()
    {
        return serializer_;
    }

    private static OpenBitSet bucketsFor(long numElements, int bucketsPer)
    {
        return new OpenBitSet(numElements * bucketsPer + EXCESS);
    }

    /**
    * @return A BloomFilter with the lowest practical false positive probability
    * for the given number of elements.
    */
    public static BloomFilter getFilter(long numElements, int targetBucketsPerElem)
    {
        int maxBucketsPerElement = Math.max(1, BloomCalculations.maxBucketsPerElement(numElements));
        int bucketsPerElement = Math.min(targetBucketsPerElem, maxBucketsPerElement);
        if (bucketsPerElement < targetBucketsPerElem)
        {
            logger.warn(String.format("Cannot provide an optimal BloomFilter for %d elements (%d/%d buckets per element).",
                                      numElements, bucketsPerElement, targetBucketsPerElem));
        }
        BloomCalculations.BloomSpecification spec = BloomCalculations.computeBloomSpec(bucketsPerElement);
        return new BloomFilter(spec.K, bucketsFor(numElements, spec.bucketsPerElement));
    }

    /**
    * @return The smallest BloomFilter that can provide the given false positive
    * probability rate for the given number of elements.
    *
    * Asserts that the given probability can be satisfied using this filter.
    */
    public static BloomFilter getFilter(long numElements, double maxFalsePosProbability)
    {
        assert maxFalsePosProbability <= 1.0 : "Invalid probability";
        int bucketsPerElement = BloomCalculations.maxBucketsPerElement(numElements);
        BloomCalculations.BloomSpecification spec = BloomCalculations.computeBloomSpec(bucketsPerElement, maxFalsePosProbability);
        return new BloomFilter(spec.K, bucketsFor(numElements, spec.bucketsPerElement));
    }

    public int buckets()
    {
      return (int) bitset.size();
    }

    private long[] getHashBuckets(ByteBuffer key)
    {
        return BloomFilter.getHashBuckets(key, hashCount, buckets());
    }

    // Murmur is faster than an SHA-based approach and provides as-good collision
    // resistance.  The combinatorial generation approach described in
    // http://www.eecs.harvard.edu/~kirsch/pubs/bbbf/esa06.pdf
    // does prove to work in actual tests, and is obviously faster
    // than performing further iterations of murmur.
    static long[] getHashBuckets(ByteBuffer b, int hashCount, long max)
    {
        long[] result = new long[hashCount];
        long hash1 = MurmurHash.hash64(b, b.position(), b.remaining(), 0L);
        long hash2 = MurmurHash.hash64(b, b.position(), b.remaining(), hash1);
        for (int i = 0; i < hashCount; ++i)
        {
            result[i] = Math.abs((hash1 + (long)i * hash2) % max);
        }
        return result;
    }

    static long[] getHashBuckets(String key, int hashCount, long max)
    {
        return getHashBuckets(toByteBuffer(key), hashCount, max);
    }
    
    public void add(ByteBuffer key)
    {
        for (long bucketIndex : getHashBuckets(key))
        {
            bitset.set(bucketIndex);
        }
    }

    public boolean isPresent(ByteBuffer key)
    {
      for (long bucketIndex : getHashBuckets(key))
      {
          if (!bitset.get(bucketIndex))
          {
              return false;
          }
      }
      return true;
    }
    
    public void add(byte[] key)
    {
        add(ByteBuffer.wrap(key));
    }
    
    public boolean isPresent(byte[] key)
    {
        return isPresent(ByteBuffer.wrap(key));
    }

    public void add(String key)
    {
        add(toBB(key));
    }
    
    /**
     * 
     */
    public boolean isPresent(String key)
    {
        return isPresent(toBB(key));
    }
    
    private ByteBuffer toBB(String s)
    {
        int strLen=s.length()*2;
        if (strBuffer==null || strBuffer.capacity()<strLen)
        {
            strBuffer=ByteBuffer.allocate( Math.max(strLen*2,512) );
        }
        else
            strBuffer.clear();
        
        byte[] b = new byte[s.length()*2];
        
        for (int i=s.length(),j=b.length;i-->0;)
        {
            char c = s.charAt(i);
            
            byte b1 = (byte) (c & 0xFF);
            strBuffer.put(--j,b1);
            byte b2 = (byte) ( (c & 0xFF00) >>8);
            strBuffer.put(--j,b2);
            
//            System.out.println("2BB: "+c+"-> "+Integer.toHexString(b1)+' '+Integer.toHexString(b2));
        }
        
//        System.out.println("2BB: "+Arrays.toString(b));
        strBuffer.limit(strLen).position(0);
        
        assert Arrays.equals( Arrays.copyOf(strBuffer.array(),strLen), toByteBuffer(s).array());
        
        return strBuffer;
    }

    private static ByteBuffer toByteBuffer(String s)
    {
        byte[] b = new byte[s.length()*2];
        
        for (int i=s.length(),j=b.length;i-->0;)
        {
            char c = s.charAt(i);
            
            byte b1 = (byte) (c & 0xFF);
            b[--j]=b1;
            byte b2 = (byte) ( (c & 0xFF00) >>8);
            b[--j]=b2;
            
//            System.out.println("2BB: "+c+"-> "+Integer.toHexString(b1)+' '+Integer.toHexString(b2));
        }
        
//        System.out.println("2BB: "+Arrays.toString(b));
        
        return ByteBuffer.wrap(b);
    }
    
    public void clear()
    {
        bitset.clear(0, bitset.size());
    }
    
    /** @return a BloomFilter that always returns a positive match, for testing */
    public static BloomFilter alwaysMatchingBloomFilter()
    {
        OpenBitSet set = new OpenBitSet(64);
        set.set(0, 64);
        return new BloomFilter(1, set);
    }    
}
