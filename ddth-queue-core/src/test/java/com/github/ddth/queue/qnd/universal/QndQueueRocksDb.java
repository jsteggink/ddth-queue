package com.github.ddth.queue.qnd.universal;

import java.util.Date;

import com.github.ddth.queue.impl.universal.UniversalQueueMessage;
import com.github.ddth.queue.impl.universal.UniversalRocksDbQueue;

public class QndQueueRocksDb {

    public static void main(String[] args) throws Exception {
        try (final UniversalRocksDbQueue queue = new UniversalRocksDbQueue()) {
            queue.setStorageDir("/tmp/rocksdb").init();

            UniversalQueueMessage msg = UniversalQueueMessage.newInstance();
            msg.content("Content: [" + System.currentTimeMillis() + "] " + new Date());
            System.out.println("Queue: " + queue.queue(msg));

            msg = queue.take();
            while (msg.qNumRequeues() < 2) {
                System.out.println("Message: " + msg);
                System.out.println("Content: " + new String(msg.content()));
                System.out.println("Requeue: " + queue.requeue(msg));
                msg = queue.take();
            }

            queue.finish(msg);
        }
    }
}
