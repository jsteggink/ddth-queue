package com.github.ddth.queue.test.universal.idstr.disruptor;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.impl.DisruptorQueue;
import com.github.ddth.queue.impl.universal.idstr.UniversalDisruptorQueue;
import com.github.ddth.queue.test.universal.BaseQueueMultiThreadsTest;

import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * Tests queue with large ring size.
 */
public class TestDisruptorQueueLarge extends BaseQueueMultiThreadsTest<String> {
    public TestDisruptorQueueLarge(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestDisruptorQueueLarge.class);
    }

    @Override
    protected IQueue<String, byte[]> initQueueInstance() throws Exception {
        if (System.getProperty("skipTestsDisruptor") != null) {
            return null;
        }
        int ringSize = 8192;
        DisruptorQueue<String, byte[]> queue = new UniversalDisruptorQueue();
        queue.setRingSize(ringSize).setEphemeralDisabled(false).init();
        return queue;
    }

}
