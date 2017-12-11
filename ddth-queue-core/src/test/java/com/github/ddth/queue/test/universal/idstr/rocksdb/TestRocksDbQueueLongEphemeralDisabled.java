package com.github.ddth.queue.test.universal.idstr.rocksdb;

import java.io.File;

import org.apache.commons.io.FileUtils;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.NoopQueueObserver;
import com.github.ddth.queue.impl.RocksDbQueue;
import com.github.ddth.queue.impl.universal.idstr.UniversalRocksDbQueue;
import com.github.ddth.queue.test.universal.BaseQueueLongTest;

import junit.framework.Test;
import junit.framework.TestSuite;

/*
 * mvn test -DskipTests=false -Dtest=com.github.ddth.queue.test.universal.idstr.rocksdb.TestRocksDbQueueLongEphemeralDisabled -DenableTestsRocksDb=true
 */

public class TestRocksDbQueueLongEphemeralDisabled extends BaseQueueLongTest<String> {
    public TestRocksDbQueueLongEphemeralDisabled(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestRocksDbQueueLongEphemeralDisabled.class);
    }

    @Override
    protected IQueue<String, byte[]> initQueueInstance() throws Exception {
        if (System.getProperty("enableTestsRocksDb") == null
                && System.getProperty("enableTestsRocksDB") == null) {
            return null;
        }
        File tempDir = FileUtils.getTempDirectory();
        File testDir = new File(tempDir, String.valueOf(System.currentTimeMillis()));
        RocksDbQueue<String, byte[]> queue = new UniversalRocksDbQueue();
        queue.setObserver(new NoopQueueObserver<String, byte[]>() {
            @Override
            public void postDestroy(IQueue<String, byte[]> queue) {
                FileUtils.deleteQuietly(testDir);
            }
        });
        queue.setStorageDir(testDir.getAbsolutePath()).setEphemeralDisabled(true).init();
        return queue;
    }

    protected int numTestMessages() {
        // to make a very long queue
        return 1024 * 1024;
    }

}
