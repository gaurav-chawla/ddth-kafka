package com.github.ddth.kafka;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.ddth.kafka.internal.KafkaConsumerWorker;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * A simple Kafka consumer client.
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 1.0.0
 */
public class KafkaConsumer {

    private final Logger LOGGER = LoggerFactory.getLogger(KafkaConsumer.class);

    private String consumerGroupId;
    private boolean consumeFromBeginning = false;

    /* Mapping {topic -> consumer-connector} */
    private ConcurrentMap<String, ConsumerConnector> topicConsumerConnectors = new ConcurrentHashMap<String, ConsumerConnector>();

    /* Mapping {topic -> [kafka-message-listerners]} */
    private Multimap<String, IKafkaMessageListener> topicMessageListeners = HashMultimap.create();

    /* Mapping {topic -> [kafka-consumer-workers]} */
    private Multimap<String, KafkaConsumerWorker> topicConsumerWorkers = HashMultimap.create();

    private KafkaClient kafkaClient;

    /**
     * Constructs an new {@link KafkaConsumer} object.
     */
    public KafkaConsumer(KafkaClient kafkaClient, String consumerGroupId) {
        this.kafkaClient = kafkaClient;
        this.consumerGroupId = consumerGroupId;
    }

    /**
     * Constructs an new {@link KafkaConsumer} object.
     */
    public KafkaConsumer(KafkaClient kafkaClient, String consumerGroupId,
            boolean consumeFromBeginning) {
        this.kafkaClient = kafkaClient;
        this.consumerGroupId = consumerGroupId;
        this.consumeFromBeginning = consumeFromBeginning;
    }

    /**
     * Each Kafka consumer is associated with a consumer group id.
     * 
     * <p>
     * If two or more comsumers have a same group id, and consume messages from
     * a same topic: messages will be consumed just like a queue: no message is
     * consumed by more than one consumer. Which consumer consumes which message
     * is undetermined.
     * </p>
     * 
     * <p>
     * If two or more comsumers with different group ids, and consume messages
     * from a same topic: messages will be consumed just like publish-subscribe
     * pattern: one message is consumed by all consumers.
     * </p>
     * 
     * @return
     */
    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    /**
     * Each Kafka consumer is associated with a consumer group id.
     * 
     * <p>
     * If two or more comsumers have a same group id, and consume messages from
     * a same topic: messages will be consumed just like a queue: no message is
     * consumed by more than one consumer. Which consumer consumes which message
     * is undetermined.
     * </p>
     * 
     * <p>
     * If two or more comsumers with different group ids, and consume messages
     * from a same topic: messages will be consumed just like publish-subscribe
     * pattern: one message is consumed by all consumers.
     * </p>
     * 
     * @param consumerGroupId
     * @return
     */
    public KafkaConsumer setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
        return this;
    }

    /**
     * Consume messages from the beginning? See {@code auto.offset.reset} option
     * at http://kafka.apache.org/08/configuration.html.
     * 
     * @return
     */
    public boolean isConsumeFromBeginning() {
        return consumeFromBeginning;
    }

    /**
     * Alias of {@link #isConsumeFromBeginning()}.
     * 
     * @return
     */
    public boolean getConsumeFromBeginning() {
        return consumeFromBeginning;
    }

    /**
     * Consume messages from the beginning? See {@code auto.offset.reset} option
     * at http://kafka.apache.org/08/configuration.html.
     * 
     * @param consumeFromBeginning
     * @return
     */
    public KafkaConsumer setConsumeFromBeginning(boolean consumeFromBeginning) {
        this.consumeFromBeginning = consumeFromBeginning;
        return this;
    }

    /**
     * Initializing method.
     */
    public void init() {
    }

    /**
     * Destroying method.
     */
    public void destroy() {
        Set<String> topicNames = new HashSet<String>(topicConsumerConnectors.keySet());
        for (String topic : topicNames) {
            try {
                removeConsumer(topic);
            } catch (Exception e) {
                LOGGER.warn(e.getMessage(), e);
            }
        }
    }

    private ConsumerConfig createConsumerConfig(String consumerGroupId, boolean consumeFromBeginning) {
        Properties props = new Properties();
        props.put("zookeeper.connect", kafkaClient.getZookeeperConnectString());
        props.put("group.id", consumerGroupId);
        props.put("zookeeper.session.timeout.ms", "600000");
        props.put("zookeeper.connection.timeout.ms", "10000");
        props.put("zookeeper.sync.time.ms", "2000");
        props.put("auto.commit.enable", "true");
        props.put("auto.commit.interval.ms", "5000");
        props.put("socket.timeout.ms", "5000");
        props.put("fetch.wait.max.ms", "2000");
        props.put("auto.offset.reset", consumeFromBeginning ? "smallest" : "largest");
        return new ConsumerConfig(props);
    }

    private ConsumerConnector _createConsumer(String topic) {
        ConsumerConfig consumerConfig = createConsumerConfig(consumerGroupId, consumeFromBeginning);
        ConsumerConnector consumer = Consumer.createJavaConsumerConnector(consumerConfig);
        return consumer;
    }

    private void _initConsumerWorkers(String topic, ConsumerConnector consumer) {
        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
        int numPartitions = kafkaClient.getTopicNumPartitions(topic);
        if (numPartitions < 1) {
            numPartitions = 1;
        }
        topicCountMap.put(topic, new Integer(numPartitions));
        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer
                .createMessageStreams(topicCountMap);
        List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);
        for (KafkaStream<byte[], byte[]> stream : streams) {
            /* Note: Multimap.get() never returns null */
            /*
             * Note: Changes to the returned collection will update the
             * underlying multimap, and vice versa.
             */
            Collection<IKafkaMessageListener> messageListeners = topicMessageListeners.get(topic);
            KafkaConsumerWorker worker = new KafkaConsumerWorker(stream, messageListeners);
            topicConsumerWorkers.put(topic, worker);
            kafkaClient.submitTask(worker);
        }
    }

    private ConsumerConnector initConsumer(String topic) {
        ConsumerConnector consumer = topicConsumerConnectors.get(topic);
        if (consumer == null) {
            consumer = _createConsumer(topic);
            ConsumerConnector existingConsumer = topicConsumerConnectors.putIfAbsent(topic,
                    consumer);
            if (existingConsumer != null) {
                consumer.shutdown();
                consumer = existingConsumer;
            } else {
                _initConsumerWorkers(topic, consumer);
            }
        }
        return consumer;
    }

    private void removeConsumer(String topic) {
        // cleanup workers for a topic-consumer
        Collection<KafkaConsumerWorker> workers = topicConsumerWorkers.removeAll(topic);
        if (workers != null) {
            for (KafkaConsumerWorker worker : workers) {
                try {
                    worker.stop();
                } catch (Exception e) {
                    LOGGER.warn(e.getMessage(), e);
                }
            }
        }

        // cleanup message-listeners
        @SuppressWarnings("unused")
        Collection<IKafkaMessageListener> listeners = topicMessageListeners.removeAll(topic);

        // finally, cleanup the consumer-connector
        try {
            ConsumerConnector consumer = topicConsumerConnectors.remove(topic);
            if (consumer != null) {
                consumer.shutdown();
            }
        } catch (Exception e) {
            LOGGER.warn(e.getMessage(), e);
        }

    }

    /**
     * Adds a message listener for a topic.
     * 
     * @param topic
     * @param messageListener
     * @return {@code true} if successful, {@code false} otherwise (the listener
     *         may have been added already)
     */
    public boolean addMessageListener(String topic, IKafkaMessageListener messageListener) {
        synchronized (topicMessageListeners) {
            if (!topicMessageListeners.put(topic, messageListener)) {
                return false;
            }
            initConsumer(topic);
            return true;
        }
    }

    /**
     * Removes a topic message listener.
     * 
     * @param topic
     * @param messageListener
     * @return {@code true} if successful, {@code false} otherwise (the topic
     *         may have no such listener added before)
     */
    public boolean removeMessageListener(String topic, IKafkaMessageListener messageListener) {
        synchronized (topicMessageListeners) {
            if (!topicMessageListeners.remove(topic, messageListener)) {
                return false;
            }
            // Collection<IKafkaMessageListener> listeners =
            // topicListeners.get(topic);
            // if (listeners == null || listeners.size() == 0) {
            // // no more listeners, remove the consumer
            // removeConsumer(topic);
            // }
        }
        return true;
    }

    /**
     * Consumes one message from a topic.
     * 
     * <p>
     * This method blocks until message is available.
     * </p>
     * 
     * @param topic
     * @return
     * @throws InterruptedException
     */
    public KafkaMessage consume(String topic) throws InterruptedException {
        final BlockingQueue<KafkaMessage> buffer = new LinkedBlockingQueue<KafkaMessage>();
        final IKafkaMessageListener listener = new AbstractKafkaMessagelistener(topic, this) {
            @Override
            public void onMessage(KafkaMessage message) {
                removeMessageListener(message.topic(), this);
                buffer.add(message);
            }
        };
        addMessageListener(topic, listener);
        KafkaMessage result = buffer.take();
        removeMessageListener(topic, listener);
        return result;
    }

    /**
     * Consumes one message from a topic, wait up to specified wait time.
     * 
     * @param topic
     * @param waitTime
     * @param waitTimeUnit
     * @return {@code null} if there is no message available
     * @throws InterruptedException
     */
    public KafkaMessage consume(String topic, long waitTime, TimeUnit waitTimeUnit)
            throws InterruptedException {
        final BlockingQueue<KafkaMessage> buffer = new LinkedBlockingQueue<KafkaMessage>();
        final IKafkaMessageListener listener = new AbstractKafkaMessagelistener(topic, this) {
            @Override
            public void onMessage(KafkaMessage message) {
                removeMessageListener(message.topic(), this);
                buffer.add(message);
            }
        };
        addMessageListener(topic, listener);
        KafkaMessage result = buffer.poll(waitTime, waitTimeUnit);
        removeMessageListener(topic, listener);
        return result;
    }
}
