package com.github.ddth.queue.impl;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.utils.QueueException;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.lang3.StringUtils;

import javax.jms.*;
import java.util.Collection;
import java.util.Date;

/**
 * (Experimental) ActiveMQ implementation of {@link IQueue}.
 *
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.6.1
 */
public abstract class ActiveMqQueue<ID, DATA> extends AbstractQueue<ID, DATA> {

    public final static String DEFAULT_URI = "tcp://localhost:61616";
    public final static String DEFAULT_QUEUE_NAME = "ddth-queue";

    private ActiveMQConnectionFactory connectionFactory;
    private boolean myOwnConnectionFactory = true;
    private String uri = DEFAULT_URI, username, password;
    private String queueName = DEFAULT_QUEUE_NAME;
    private Connection connection;

    /**
     * Get ActiveMQ's connection URI (see http://activemq.apache.org/connection-configuration-uri.html).
     *
     * @return
     */
    public String getUri() {
        return uri;
    }

    /**
     * Set ActiveMQ's connection URI (see http://activemq.apache.org/connection-configuration-uri.html).
     *
     * @param uri
     * @return
     */
    public ActiveMqQueue<ID, DATA> setUri(String uri) {
        this.uri = uri;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public ActiveMqQueue<ID, DATA> setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public ActiveMqQueue<ID, DATA> setPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Name of ActiveMQ queue to send/receive messages.
     *
     * @return
     */
    public String getQueueName() {
        return queueName;
    }

    public ActiveMqQueue<ID, DATA> setQueueName(String queueName) {
        this.queueName = queueName;
        return this;
    }

    protected ActiveMQConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public ActiveMqQueue<ID, DATA> setConnectionFactory(
            ActiveMQConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        myOwnConnectionFactory = false;
        return this;
    }

    protected Connection getConnection() throws JMSException {
        if (connection == null) {
            synchronized (this) {
                if (connection == null) {
                    connection = StringUtils.isEmpty(username)
                            ? connectionFactory.createConnection()
                            : connectionFactory.createConnection(getUsername(), getPassword());
                    connection.start();
                }
            }
        }
        return connection;
    }

    protected Session createSession(int acknowledgeMode) throws JMSException {
        return getConnection().createSession(false, acknowledgeMode);
    }

    private Session producerSession;
    private MessageProducer messageProducer;

    /**
     * Get the {@link Session} dedicated for sending messages.
     *
     * @return
     * @throws JMSException
     */
    protected Session getProducerSession() throws JMSException {
        if (producerSession == null) {
            synchronized (this) {
                if (producerSession == null) {
                    producerSession = createSession(Session.AUTO_ACKNOWLEDGE);
                }
            }
        }
        return producerSession;
    }

    protected MessageProducer getMessageProducer() throws JMSException {
        if (messageProducer == null) {
            synchronized (this) {
                if (messageProducer == null) {
                    Session session = getProducerSession();
                    Destination destination = session.createQueue(queueName);
                    messageProducer = session.createProducer(destination);
                }
            }
        }
        return messageProducer;
    }

    private Session consumerSession;
    private MessageConsumer messageConsumer;

    /**
     * Get the {@link Session} dedicated for consuming messages.
     *
     * @return
     * @throws JMSException
     */
    protected Session getConsumerSession() throws JMSException {
        if (consumerSession == null) {
            synchronized (this) {
                if (consumerSession == null) {

                    consumerSession = createSession(Session.AUTO_ACKNOWLEDGE);
                }
            }
        }
        return consumerSession;
    }

    protected MessageConsumer getMessageConsumer() throws JMSException {
        if (messageConsumer == null) {
            synchronized (this) {
                if (messageConsumer == null) {
                    Session session = getConsumerSession();
                    Destination destination = session.createQueue(queueName);
                    messageConsumer = session.createConsumer(destination);
                }
            }
        }
        return messageConsumer;
    }

    /*----------------------------------------------------------------------*/

    /**
     * Init method.
     *
     * @return
     */
    public ActiveMqQueue<ID, DATA> init() {
        if (connectionFactory == null) {
            ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(getUri());
            myOwnConnectionFactory = true;
            this.connectionFactory = cf;
        }

        return this;
    }

    /**
     * Destroy method.
     */
    public void destroy() {
        closeQuietly(connection);
        closeQuietly(messageProducer);
        closeQuietly(producerSession);
        closeQuietly(messageConsumer);
        closeQuietly(consumerSession);

        if (connectionFactory != null && myOwnConnectionFactory) {
            connectionFactory = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Serializes a queue message to store in Redis.
     *
     * @param msg
     * @return
     */
    protected abstract byte[] serialize(IQueueMessage<ID, DATA> msg);

    /**
     * Deserilizes a queue message.
     *
     * @param msgData
     * @return
     */
    protected abstract IQueueMessage<ID, DATA> deserialize(byte[] msgData);

    protected void closeQuietly(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {

        }
    }

    protected void closeQuietly(Session session) {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception e) {

        }
    }

    protected void closeQuietly(MessageConsumer consumer) {
        try {
            if (consumer != null) {
                consumer.close();
            }
        } catch (Exception e) {

        }
    }

    protected void closeQuietly(MessageProducer producer) {
        try {
            if (producer != null) {
                producer.close();
            }
        } catch (Exception e) {

        }
    }

    /**
     * Puts a message to Kafka queue, partitioning message by {@link IQueueMessage#qId()}
     *
     * @param msg
     * @return
     */
    protected boolean putToQueue(IQueueMessage<ID, DATA> msg) {
        try {
            BytesMessage message = getProducerSession().createBytesMessage();
            message.writeBytes(serialize(msg));
            getMessageProducer().send(message);
            return true;
        } catch (Exception e) {
            throw e instanceof QueueException ? (QueueException) e : new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(IQueueMessage<ID, DATA> _msg) {
        IQueueMessage<ID, DATA> msg = _msg.clone();
        Date now = new Date();
        msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(IQueueMessage<ID, DATA> _msg) {
        IQueueMessage<ID, DATA> msg = _msg.clone();
        Date now = new Date();
        msg.qIncNumRequeues().qTimestamp(now);
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage<ID, DATA> _msg) {
        IQueueMessage<ID, DATA> msg = _msg.clone();
        return putToQueue(msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage<ID, DATA> msg) {
        // EMPTY
    }

    /**
     * {@inheritDoc}
     *
     * @throws QueueException.EphemeralIsFull
     *         if the ephemeral storage is full
     */
    @Override
    public IQueueMessage<ID, DATA> take() throws QueueException.EphemeralIsFull {
        try {
            MessageConsumer consumer = getMessageConsumer();
            synchronized (consumer) {
                //Message message = consumer.receiveNoWait();
                Message message = consumer.receive(1000);
                if (message instanceof BytesMessage) {
                    BytesMessage msg = (BytesMessage) message;
                    byte[] buff = new byte[(int) msg.getBodyLength()];
                    msg.readBytes(buff);
                    return deserialize(buff);
                } else {
                    return null;
                }
            }
        } catch (Exception e) {
            throw e instanceof QueueException ? (QueueException) e : new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<IQueueMessage<ID, DATA>> getOrphanMessages(long thresholdTimestampMs) {
        throw new QueueException.OperationNotSupported(
                "This queue does not support retrieving orphan messages.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage<ID, DATA> msg) {
        throw new QueueException.OperationNotSupported(
                "This queue does not support ephemeral storage.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        return -1;
    }
}