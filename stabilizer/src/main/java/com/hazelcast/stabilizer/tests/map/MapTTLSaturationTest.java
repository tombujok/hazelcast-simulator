/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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
package com.hazelcast.stabilizer.tests.map;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IList;
import com.hazelcast.core.IMap;
import com.hazelcast.spi.exception.DistributedObjectDestroyedException;
import com.hazelcast.stabilizer.tests.TestContext;
import com.hazelcast.stabilizer.tests.TestRunner;
import com.hazelcast.stabilizer.tests.annotations.Run;
import com.hazelcast.stabilizer.tests.annotations.Setup;
import com.hazelcast.stabilizer.tests.annotations.Verify;
import com.hazelcast.stabilizer.tests.map.helpers.MapOpperationsCount;
import com.hazelcast.stabilizer.tests.utils.ThreadSpawner;
import com.sun.jna.Structure;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class MapTTLSaturationTest {

    public String basename = this.getClass().getName();
    public int threadCount = 3;

    private TestContext testContext;
    private HazelcastInstance targetInstance;

    public MapTTLSaturationTest(){
    }

    @Setup
    public void setup(TestContext testContext) throws Exception {
        this.testContext = testContext;
        targetInstance = testContext.getTargetInstance();

        long free = Runtime.getRuntime().freeMemory();
        long total =  Runtime.getRuntime().totalMemory();
        long used = total - free;
        long max =  Runtime.getRuntime().maxMemory();

        System.out.println("free = "+humanReadableByteCount(free, true)+" = "+free);
        System.out.println("used = "+humanReadableByteCount(used, true)+" = "+used);
        System.out.println("max = "+humanReadableByteCount(max, true)+" = "+max);



        final IMap map = targetInstance.getMap(basename);

        for(long i=0; i <  1000 ; i++){
            map.put(i, i, 24, TimeUnit.HOURS);
        }

        free = Runtime.getRuntime().freeMemory();
        total =  Runtime.getRuntime().totalMemory();
        used = total - free;
        max =  Runtime.getRuntime().maxMemory();


        System.out.println("map = "+ map.size());
        System.out.println("free = "+humanReadableByteCount(free, true)+" = "+free);
        System.out.println("used = "+humanReadableByteCount(used, true)+" = "+used);
        System.out.println("max = "+humanReadableByteCount(max, true)+" = "+max);

    }

    @Run
    public void run() {
        ThreadSpawner spawner = new ThreadSpawner(testContext.getTestId());
        for (int k = 0; k < threadCount; k++) {
            spawner.spawn(new Worker());
        }
        spawner.awaitCompletion();
    }

    private class Worker implements Runnable {
        private MapOpperationsCount count = new MapOpperationsCount();
        private final Random random = new Random();
        @Override
        public void run() {
            while (!testContext.isStopped()) {



                final IMap map = targetInstance.getMap(basename);




            }
            IList<MapOpperationsCount> results = targetInstance.getList(basename+"report");
            results.add(count);
        }
    }

    @Verify(global = true)
    public void globalVerify() throws Exception {

        IList<MapOpperationsCount> results = targetInstance.getList(basename+"report");
        MapOpperationsCount total = new MapOpperationsCount();
        for(MapOpperationsCount i : results){
            total.add(i);
        }
        System.out.println(basename+": "+total+" total of "+results.size());

        final IMap map = targetInstance.getMap(basename);



        assertEquals("Map Size not 0, some TTL events not processed", 0, map.size());
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static void main(String[] args) throws Throwable {
        new TestRunner(new MapTTLSaturationTest()).run();
    }
}
