package org.ekstep.ep.samza.task;

import org.apache.samza.config.Config;
import org.apache.samza.metrics.Counter;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.*;
import org.ekstep.ep.samza.Child;
import org.ekstep.ep.samza.external.UserService;
import org.ekstep.ep.samza.external.UserServiceClient;
import org.ekstep.ep.samza.Event;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DeNormalizationTask implements StreamTask, InitableTask, WindowableTask{
    private static final String TAG = "DeNormalizationTask";
    static Logger LOGGER = LoggerFactory.getLogger(DeNormalizationTask.class);
    private static final String RETRY_BACKOFF_BASE_DEFAULT = "10";
    private static final String RETRY_BACKOFF_LIMIT_DEFAULT = "4";
    private String successTopic;
    private String failedTopic;
    private KeyValueStore<String, Child> childData;
    private String retryTopic;

    private Counter messageCount;
    private int retryBackoffBase;
    private int retryBackoffLimit;
    private KeyValueStore<String, Object> retryStore;
    private String userServiceEndpoint;

    @Override
    public void init(Config config, TaskContext context) throws Exception {
        successTopic = config.get("output.success.topic.name", "events_with_de_normalization");
        failedTopic = config.get("output.failed.topic.name", "events_failed_de_normalization");
        retryTopic = config.get("output.retry.topic.name", "events_retry");
        childData = (KeyValueStore<String, Child>) context.getStore("de-normalization");
        userServiceEndpoint = config.get("user.service.endpoint");
        retryBackoffBase = Integer.parseInt(config.get("retry.backoff.base"));
        retryBackoffLimit = Integer.parseInt(config.get("retry.backoff.limit"));
        messageCount = context
                .getMetricsRegistry()
                .newCounter(getClass().getName(), "message-count");
        retryStore = (KeyValueStore<String, Object>) context.getStore("retry");
    }

    @Override
    public void process(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator) {
        try {
            Map<String, Object> message = (Map<String, Object>) envelope.getMessage();
            UserService dataSource = new UserServiceClient(userServiceEndpoint);
            Event event = new Event(message, childData);
            processEvent(collector, event, dataSource);
            messageCount.inc();
        }catch (Exception e){
            System.err.println("Error while processing message"+e);
            e.printStackTrace();
        }
    }

    public void processEvent(MessageCollector collector, Event event, UserService dataSource) {
        event.initialize(retryBackoffBase,retryBackoffLimit,retryStore);
        LOGGER.info(TAG + " EVENT:", event.getMap());
        if(!event.isSkipped()){
            LOGGER.info(TAG+" PROCESS");
            event.process(dataSource, DateTime.now());
        } else {
            LOGGER.info(TAG+" SKIP");
        }
        populateTopic(collector,event);
    }

    private void populateTopic(MessageCollector collector, Event event) {

        boolean childDataNotProcessed = event.canBeProcessed() && !event.isProcessed();
        boolean hadProblemWithDb = event.hadIssueWithDb();
        if (childDataNotProcessed || hadProblemWithDb)
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", retryTopic), event.getData()));
        else
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", successTopic), event.getData()));
    }

    @Override
    public void window(MessageCollector collector, TaskCoordinator coordinator) throws Exception {
        messageCount.clear();
    }
}
