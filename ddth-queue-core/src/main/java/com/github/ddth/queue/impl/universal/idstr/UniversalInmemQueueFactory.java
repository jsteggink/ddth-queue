package com.github.ddth.queue.impl.universal.idstr;

import com.github.ddth.queue.QueueSpec;
import com.github.ddth.queue.impl.InmemQueueFactory;

/**
 * Factory to create {@link UniversalInmemQueue} instances.
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.4.1
 */
public class UniversalInmemQueueFactory
        extends InmemQueueFactory<UniversalInmemQueue, String, byte[]> {

    /**
     * {@inheritDoc}
     */
    @Override
    protected UniversalInmemQueue createQueueInstance(final QueueSpec spec) {
        UniversalInmemQueue queue = new UniversalInmemQueue();
        return queue;
    }

}
