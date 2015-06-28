package com.github.ddth.queue.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.ddth.dao.jdbc.BaseJdbcDao;
import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.utils.QueueException;

/**
 * Abstract JDBC implementation of {@link IQueue}.
 * 
 * <p>
 * Implementation:
 * <ul>
 * <li>Queue storage & Ephemeral storage are 2 database tables, same structure!</li>
 * </ul>
 * </p>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.1.0
 */
public abstract class JdbcQueue extends BaseJdbcDao implements IQueue {

    public static int DEFAULT_MAX_RETRIES = 3;

    private Logger LOGGER = LoggerFactory.getLogger(JdbcQueue.class);

    private String tableName, tableNameEphemeral;
    private String SQL_COUNT = "SELECT COUNT(*) AS num_entries FROM {0}";
    private String SQL_COUNT_EPHEMERAL = "SELECT COUNT(*) AS num_entries FROM {0}";

    private int maxRetries = DEFAULT_MAX_RETRIES;

    // private int transactionIsolationLevel =
    // Connection.TRANSACTION_REPEATABLE_READ;
    private int transactionIsolationLevel = Connection.TRANSACTION_READ_COMMITTED;

    /*----------------------------------------------------------------------*/
    public JdbcQueue setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String getTableName() {
        return tableName;
    }

    public JdbcQueue setTableNameEphemeral(String tableNameEphemeral) {
        this.tableNameEphemeral = tableNameEphemeral;
        return this;
    }

    public String getTableNameEphemeral() {
        return tableNameEphemeral;
    }

    public JdbcQueue setTransactionIsolationLevel(int transactionIsolationLevel) {
        this.transactionIsolationLevel = transactionIsolationLevel;
        return this;
    }

    public int getTransactionIsolationLevel() {
        return transactionIsolationLevel;
    }

    public JdbcQueue setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /*----------------------------------------------------------------------*/

    /**
     * {@inheritDoc}
     */
    @Override
    public JdbcQueue init() {
        SQL_COUNT = MessageFormat.format(SQL_COUNT, tableName);
        SQL_COUNT_EPHEMERAL = MessageFormat.format(SQL_COUNT_EPHEMERAL, tableNameEphemeral);
        return (JdbcQueue) super.init();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        super.destroy();
    }

    /*----------------------------------------------------------------------*/

    /**
     * Reads a message from head of queue storage.
     * 
     * @param jdbcTemplate
     * @return
     */
    protected abstract IQueueMessage readFromQueueStorage(JdbcTemplate jdbcTemplate);

    /**
     * Reads a message from the ephemeral storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     * @since 0.2.1
     */
    protected abstract IQueueMessage readFromEphemeralStorage(JdbcTemplate jdbcTemplate,
            IQueueMessage msg);

    /**
     * Gets all orphan messages (messages that were left in ephemeral storage
     * for a long time).
     * 
     * @param jdbcTemplate
     * @param thresholdTimestampMs
     *            get all orphan messages that were queued
     *            <strong>before</strong> this timestamp
     * @return
     * @since 0.2.0
     */
    protected abstract Collection<IQueueMessage> getOrphanFromEphemeralStorage(
            JdbcTemplate jdbcTemplate, long thresholdTimestampMs);

    /**
     * Puts a message to tail of the queue storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean putToQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Puts a message to the ephemeral storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean putToEphemeralStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Removes a message from the queue storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean removeFromQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage msg);

    /**
     * Removes a message from the ephemeral storage.
     * 
     * @param jdbcTemplate
     * @param msg
     * @return
     */
    protected abstract boolean removeFromEphemeralStorage(JdbcTemplate jdbcTemplate,
            IQueueMessage msg);

    /**
     * Queues a message, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param conn
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     */
    protected boolean _queueWithRetries(final Connection conn, final IQueueMessage msg,
            final int numRetries, final int maxRetries) throws SQLException {
        try {
            // startTransaction(conn);
            // conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            Date now = new Date();
            msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
            boolean result = putToQueueStorage(jdbcTemplate, msg);

            // commitTransaction(conn);
            return result;
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (PessimisticLockingFailureException ex) {
            // rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                return _queueWithRetries(conn, msg, numRetries + 1, maxRetries);
            }
        } catch (Exception e) {
            // rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(final IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            Connection conn = connection();
            try {
                boolean result = _queueWithRetries(conn, msg, 0, this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(queue) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Re-queues a message, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param conn
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     */
    protected boolean _requeueWithRetries(final Connection conn, final IQueueMessage msg,
            final int numRetries, final int maxRetries) throws SQLException {
        try {
            startTransaction(conn);
            conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            removeFromEphemeralStorage(jdbcTemplate, msg);
            Date now = new Date();
            msg.qIncNumRequeues().qTimestamp(now);
            boolean result = putToQueueStorage(jdbcTemplate, msg);

            commitTransaction(conn);
            return result;
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (PessimisticLockingFailureException ex) {
            rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                /*
                 * call _requeueSilentWithRetries(...) here is correct because
                 * we do not want message's num-requeues is increased with every
                 * retry
                 */
                return _requeueSilentWithRetries(conn, msg, numRetries + 1, maxRetries);
            }
        } catch (Exception e) {
            rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(final IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            Connection conn = connection();
            try {
                boolean result = _requeueWithRetries(conn, msg, 0, this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(requeue) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Re-queues a message silently, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param conn
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     */
    protected boolean _requeueSilentWithRetries(final Connection conn, final IQueueMessage msg,
            final int numRetries, final int maxRetries) throws SQLException {
        try {
            startTransaction(conn);
            conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            removeFromEphemeralStorage(jdbcTemplate, msg);
            boolean result = putToQueueStorage(jdbcTemplate, msg);

            commitTransaction(conn);
            return result;
        } catch (DuplicateKeyException dke) {
            LOGGER.warn(dke.getMessage(), dke);
            return true;
        } catch (PessimisticLockingFailureException ex) {
            rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                return _requeueSilentWithRetries(conn, msg, numRetries + 1, maxRetries);
            }
        } catch (Exception e) {
            rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(final IQueueMessage msg) {
        if (msg == null) {
            return false;
        }
        try {
            Connection conn = connection();
            try {
                boolean result = _requeueSilentWithRetries(conn, msg, 0, this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(requeueSilent) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Performs "finish" action, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param conn
     * @param msg
     * @param numRetries
     * @param maxRetries
     * @throws SQLException
     */
    protected void _finishWithRetries(final Connection conn, final IQueueMessage msg,
            final int numRetries, final int maxRetries) throws SQLException {
        try {
            // startTransaction(conn);
            // conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            removeFromEphemeralStorage(jdbcTemplate, msg);

            // commitTransaction(conn);
        } catch (PessimisticLockingFailureException ex) {
            // rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                _finishWithRetries(conn, msg, numRetries + 1, maxRetries);
            }
        } catch (Exception e) {
            // rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(final IQueueMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            Connection conn = connection();
            try {
                _finishWithRetries(conn, msg, 0, this.maxRetries);
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(finish) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Takes a message from queue, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param conn
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     */
    protected IQueueMessage _takeWithRetries(final Connection conn, final int numRetries,
            final int maxRetries) throws SQLException {
        try {
            startTransaction(conn);
            conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            boolean result = true;
            IQueueMessage msg = readFromQueueStorage(jdbcTemplate);
            if (msg != null) {
                result = result && removeFromQueueStorage(jdbcTemplate, msg);
                try {
                    result = result && putToEphemeralStorage(jdbcTemplate, msg);
                } catch (DuplicateKeyException dke) {
                    LOGGER.warn(dke.getMessage(), dke);
                }
            }

            if (result) {
                commitTransaction(conn);
                return msg;
            } else {
                rollbackTransaction(conn);
                return null;
            }
        } catch (PessimisticLockingFailureException ex) {
            rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                return _takeWithRetries(conn, numRetries + 1, maxRetries);
            }
        } catch (Exception e) {
            rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQueueMessage take() {
        try {
            Connection conn = connection();
            try {
                IQueueMessage result = _takeWithRetries(conn, 0, this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(take) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Gets all orphan messages (messages that were left in ephemeral storage
     * for a long time), retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param thresholdTimestampMs
     * @param conn
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     * @since 0.2.0
     */
    protected Collection<IQueueMessage> _getOrphanMessagesWithRetries(
            final long thresholdTimestampMs, final Connection conn, final int numRetries,
            final int maxRetries) throws SQLException {
        try {
            startTransaction(conn);
            conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            Collection<IQueueMessage> msgs = getOrphanFromEphemeralStorage(jdbcTemplate,
                    thresholdTimestampMs);

            commitTransaction(conn);
            return msgs;
        } catch (PessimisticLockingFailureException ex) {
            rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                return _getOrphanMessagesWithRetries(thresholdTimestampMs, conn, numRetries + 1,
                        maxRetries);
            }
        } catch (Exception e) {
            rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        try {
            Connection conn = connection();
            try {
                Collection<IQueueMessage> result = _getOrphanMessagesWithRetries(
                        thresholdTimestampMs, conn, 0, this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(getOrphanMessages) Exception [" + e.getClass().getName()
                    + "]: " + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * Moves a message from ephemeral back to queue storage, retry if deadlock.
     * 
     * <p>
     * Note: http://dev.mysql.com/doc/refman/5.0/en/innodb-deadlocks.html
     * </p>
     * <p>
     * InnoDB uses automatic row-level locking. You can get deadlocks even in
     * the case of transactions that just insert or delete a single row. That is
     * because these operations are not really "atomic"; they automatically set
     * locks on the (possibly several) index records of the row inserted or
     * deleted.
     * </p>
     * 
     * @param msg
     * @param conn
     * @param numRetries
     * @param maxRetries
     * @return
     * @throws SQLException
     */
    protected boolean _moveFromEphemeralToQueueStorageWithRetries(final IQueueMessage msg,
            final Connection conn, final int numRetries, final int maxRetries) throws SQLException {
        try {
            startTransaction(conn);
            conn.setTransactionIsolation(transactionIsolationLevel);
            JdbcTemplate jdbcTemplate = jdbcTemplate(conn);

            IQueueMessage orphanMsg = readFromEphemeralStorage(jdbcTemplate, msg);
            if (orphanMsg != null) {
                removeFromEphemeralStorage(jdbcTemplate, msg);
                boolean result = putToQueueStorage(jdbcTemplate, msg);

                commitTransaction(conn);
                return result;
            }

            rollbackTransaction(conn);
            return false;
        } catch (PessimisticLockingFailureException ex) {
            rollbackTransaction(conn);
            if (numRetries > maxRetries) {
                throw new QueueException(ex);
            } else {
                return _moveFromEphemeralToQueueStorageWithRetries(msg, conn, numRetries + 1,
                        maxRetries);
            }
        } catch (Exception e) {
            rollbackTransaction(conn);
            throw new QueueException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage msg) {
        try {
            Connection conn = connection();
            try {
                boolean result = _moveFromEphemeralToQueueStorageWithRetries(msg, conn, 0,
                        this.maxRetries);
                return result;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(moveFromEphemeralToQueueStorage) Exception ["
                    + e.getClass().getName() + "]: " + e.getMessage();
            LOGGER.error(logMsg, e);
            if (e instanceof QueueException) {
                throw (QueueException) e;
            } else {
                throw new QueueException(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        try {
            Connection conn = connection();
            try {
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                Integer result = jdbcTemplate.queryForObject(SQL_COUNT, null, Integer.class);
                return result != null ? result.intValue() : 0;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(queueSize) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            return -1;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        try {
            Connection conn = connection();
            try {
                JdbcTemplate jdbcTemplate = jdbcTemplate(conn);
                Integer result = jdbcTemplate.queryForObject(SQL_COUNT_EPHEMERAL, null,
                        Integer.class);
                return result != null ? result.intValue() : 0;
            } finally {
                returnConnection(conn);
            }
        } catch (Exception e) {
            final String logMsg = "(ephemeralSize) Exception [" + e.getClass().getName() + "]: "
                    + e.getMessage();
            LOGGER.error(logMsg, e);
            return -1;
        }
    }
}
