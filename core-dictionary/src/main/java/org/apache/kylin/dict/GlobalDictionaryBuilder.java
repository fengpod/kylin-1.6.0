/*
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

package org.apache.kylin.dict;

import java.io.IOException;
import java.util.ArrayList;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.NavigableSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.Dictionary;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.common.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * GlobalDictinary based on whole cube, to ensure one value has same dict id in different segments.
 * GlobalDictinary mainly used for count distinct measure to support rollup among segments.
 * Created by sunyerui on 16/5/24.
 */
public class GlobalDictionaryBuilder implements IDictionaryBuilder {
    private static Logger logger = LoggerFactory.getLogger(GlobalDictionaryBuilder.class);

//    private DistributedLock lock;
//    private String sourceColumn;
    //the job thread name is UUID+threadID
    //private final String jobUUID = Thread.currentThread().getName();
//    private final String lockData = getServerName() + "_" + Thread.currentThread().getName();
//    private int counter = 0;

    @Override
    public Dictionary<String> build(DictionaryInfo dictInfo, IDictionaryValueEnumerator valueEnumerator, int baseId, int nSamples, ArrayList<String> returnSamples) throws IOException {
        if (dictInfo == null) {
            throw new IllegalArgumentException("GlobalDictinaryBuilder must used with an existing DictionaryInfo");
        }

//        this.sourceColumn = dictInfo.getSourceTable() + "_" + dictInfo.getSourceColumn();
//        lock(sourceColumn);

        try {
            AppendTrieDictionary.Builder<String> builder;
            builder = AppendTrieDictionary.Builder.getInstance(dictInfo.getResourceDir());

            byte[] value;
            while (valueEnumerator.moveNext()) {
                value = valueEnumerator.current();

//                if (++counter % 1_000_000 == 0) {
//                    if (lock.lockWithName(sourceColumn, lockData)) {
//                        logger.info("processed {} values for {}", counter, sourceColumn);
//                    } else {
//                        throw new RuntimeException("Failed to create global dictionary on " + sourceColumn + " This client doesn't keep the lock");
//                    }
//                }

                if (value == null) {
                    continue;
                }
                String v = Bytes.toString(value);

                try {
                    builder.addValue(v);
                } catch (Throwable e) {
                    throw new RuntimeException("Failed to create global dictionary on "+dictInfo.getSourceColumn(), e);
                }

                if (returnSamples.size() < nSamples && returnSamples.contains(v) == false)
                    returnSamples.add(v);
            }
            return builder.build(baseId);
//            if (lock.lockWithName(sourceColumn, lockData)) {
//                return builder.build(baseId);
//            } else {
//                throw new RuntimeException("Failed to create global dictionary on " + sourceColumn + " This client doesn't keep the lock");
//            }

        } finally {
//            checkAndUnlock();
        }
    }

//    private void lock(final String sourceColumn) throws IOException {
//        lock = (DistributedLock) ClassUtil.newInstance("org.apache.kylin.storage.hbase.util.ZookeeperDistributedJobLock");
//
//        if (!lock.lockWithName(sourceColumn, lockData)) {
//            logger.info("{} will wait the lock for {} ", lockData, sourceColumn);
//
//            long start = System.currentTimeMillis();
//
//            //TODO 是否需要超时控制
//            try {
//                while (true) {
//                    Thread.sleep(10 * 1000);
//                    if (lock.lockWithName(sourceColumn, lockData)) {
//                        break;
//                    }
//                }
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//
//            logger.info("{} has waited the lock {} ms for {} ", lockData, (System.currentTimeMillis() - start), sourceColumn);
//        }
//    }

//    private void checkAndUnlock() {
//        if (lock.lockWithName(sourceColumn, lockData)) {
//            lock.unlockWithName(sourceColumn);
//        }
//    }

//    private static String getServerName() {
//        String serverName = null;
//        try {
//            serverName = InetAddress.getLocalHost().getHostName();
//        } catch (UnknownHostException e) {
//            logger.error("fail to get the serverName");
//        }
//        return serverName;
//    }
}
