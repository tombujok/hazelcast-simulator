/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.tests.map;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.simulator.test.TestContext;
import com.hazelcast.simulator.test.TestRunner;
import com.hazelcast.simulator.test.annotations.Run;
import com.hazelcast.simulator.test.annotations.Setup;
import com.hazelcast.simulator.test.annotations.Verify;
import com.hazelcast.simulator.utils.ThreadSpawner;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.simulator.utils.CommonUtils.sleepMillis;
import static com.hazelcast.simulator.utils.FormatUtils.humanReadableByteCount;

public class MapTTLSaturationTest {

    private static final ILogger LOGGER = Logger.getLogger(MapTTLSaturationTest.class);

    // properties
    public String basename = MapTTLSaturationTest.class.getSimpleName();
    public int threadCount = 3;
    public double maxHeapUsagePercentage = 80;

    private TestContext testContext;
    private IMap<Long, Long> map;

    @Setup
    public void setup(TestContext testContext) {
        this.testContext = testContext;
        map = testContext.getTargetInstance().getMap(basename);
    }

    private double heapUsedPercentage() {
        long total = Runtime.getRuntime().totalMemory();
        long max = Runtime.getRuntime().maxMemory();
        return (100d * total) / max;
    }

    @Run
    public void run() {
        ThreadSpawner spawner = new ThreadSpawner(basename);
        for (int i = 0; i < threadCount; i++) {
            spawner.spawn(new Worker());
        }
        spawner.awaitCompletion();
    }

    private class Worker implements Runnable {
        @Override
        public void run() {
            long free = Runtime.getRuntime().freeMemory();
            long total = Runtime.getRuntime().totalMemory();
            long baseLineUsed = total - free;
            long maxBytes = Runtime.getRuntime().maxMemory();
            double usedOfMax = 100.0 * ((double) baseLineUsed / (double) maxBytes);

            LOGGER.info(basename + " before Init");
            LOGGER.info(basename + " free = " + humanReadableByteCount(free, true) + " = " + free);
            LOGGER.info(basename + " used = " + humanReadableByteCount(baseLineUsed, true) + " = " + baseLineUsed);
            LOGGER.info(basename + " max = " + humanReadableByteCount(maxBytes, true) + " = " + maxBytes);
            LOGGER.info(basename + " usedOfMax = " + usedOfMax + '%');

            int counter = 1;
            Random random = new Random();

            while (!testContext.isStopped()) {
                double usedPercentage = heapUsedPercentage();
                if (usedPercentage >= maxHeapUsagePercentage) {
                    LOGGER.info("heap used: " + usedPercentage + " % map.size:" + map.size());

                    sleepMillis(10000);
                } else {
                    for (int i = 0; i < 1000; i++) {
                        counter++;
                        if (counter % 100000 == 0) {
                            LOGGER.info("at:" + counter + " heap used: " + usedPercentage + " % map.size:" + map.size());
                        }
                        long key = random.nextLong();
                        map.put(key, 0L, 24, TimeUnit.HOURS);
                    }
                }
            }

            free = Runtime.getRuntime().freeMemory();
            total = Runtime.getRuntime().totalMemory();
            long nowUsed = total - free;
            maxBytes = Runtime.getRuntime().maxMemory();
            usedOfMax = 100.0 * ((double) nowUsed / (double) maxBytes);

            LOGGER.info(basename + " After Init");
            LOGGER.info(basename + " map = " + map.size());
            LOGGER.info(basename + " free = " + humanReadableByteCount(free, true) + " = " + free);
            LOGGER.info(basename + " used = " + humanReadableByteCount(nowUsed, true) + " = " + nowUsed);
            LOGGER.info(basename + " max = " + humanReadableByteCount(maxBytes, true) + " = " + maxBytes);
            LOGGER.info(basename + " usedOfMax = " + usedOfMax + '%');
            LOGGER.info(basename + " map size:" + map.size());
        }
    }

    @Verify(global = false)
    public void globalVerify() {
        long free = Runtime.getRuntime().freeMemory();
        long total = Runtime.getRuntime().totalMemory();
        long used = total - free;
        long maxBytes = Runtime.getRuntime().maxMemory();
        double usedOfMax = 100.0 * ((double) used / (double) maxBytes);

        LOGGER.info(basename + " map = " + map.size());
        LOGGER.info(basename + "free = " + humanReadableByteCount(free, true) + " = " + free);
        LOGGER.info(basename + "used = " + humanReadableByteCount(used, true) + " = " + used);
        LOGGER.info(basename + "max = " + humanReadableByteCount(maxBytes, true) + " = " + maxBytes);
        LOGGER.info(basename + "usedOfMax = " + usedOfMax + '%');
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.addMapConfig(new MapConfig("mapttlsaturation*").setBackupCount(0).setStatisticsEnabled(false));
        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        new TestRunner<MapTTLSaturationTest>(new MapTTLSaturationTest()).withHazelcastInstance(hz).withDuration(6000).run();
    }
}
