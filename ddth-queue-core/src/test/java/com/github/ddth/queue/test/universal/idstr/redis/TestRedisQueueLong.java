package com.github.ddth.queue.test.universal.idstr.redis;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.impl.universal.idstr.UniversalRedisQueue;
import com.github.ddth.queue.test.universal.BaseQueueLongTest;

import junit.framework.Test;
import junit.framework.TestSuite;
import redis.clients.jedis.Jedis;

/*
 * mvn test -DskipTests=false -Dtest=com.github.ddth.queue.test.universal.idstr.redis.TestRedisQueueLong -DenableTestsRedis=true
 */

public class TestRedisQueueLong extends BaseQueueLongTest<String> {
    public TestRedisQueueLong(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestRedisQueueLong.class);
    }

    private static class MyRedisQueue extends UniversalRedisQueue {
        public void flush() {
            try (Jedis jedis = getJedisConnector().getJedis()) {
                jedis.flushAll();
            }
        }
    }

    @Override
    protected IQueue<String, byte[]> initQueueInstance() throws Exception {
        if (System.getProperty("enableTestsRedis") == null) {
            return null;
        }
        String redisHost = System.getProperty("redis.host", "localhost");
        String redisPort = System.getProperty("redis.port", "6379");

        MyRedisQueue queue = new MyRedisQueue();
        queue.setRedisHostAndPort(redisHost + ":" + redisPort).setEphemeralDisabled(false).init();
        queue.flush();
        return queue;
    }

    protected int numTestMessages() {
        // to make a very long queue
        return 128 * 1024;
    }

}
