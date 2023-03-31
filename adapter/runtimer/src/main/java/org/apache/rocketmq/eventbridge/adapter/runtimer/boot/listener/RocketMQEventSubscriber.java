/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.rocketmq.eventbridge.adapter.runtimer.boot.listener;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.data.ConnectRecord;
import io.openmessaging.connector.api.data.RecordOffset;
import io.openmessaging.connector.api.data.RecordPartition;
import io.openmessaging.connector.api.data.Schema;
import io.openmessaging.internal.DefaultKeyValue;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.eventbridge.adapter.runtimer.common.entity.TargetRunnerConfig;
import org.apache.rocketmq.eventbridge.adapter.runtimer.common.enums.RefreshTypeEnum;
import org.apache.rocketmq.eventbridge.adapter.runtimer.config.RuntimerConfigDefine;
import org.apache.rocketmq.eventbridge.adapter.runtimer.service.TargetRunnerConfigObserver;
import org.apache.rocketmq.eventbridge.exception.EventBridgeException;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RocketMQ implement event subscriber
 */
public class RocketMQEventSubscriber extends EventSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(RocketMQEventSubscriber.class);

    private DefaultLitePullConsumer pullConsumer;

    private TargetRunnerConfigObserver runnerConfigObserver;

    private Integer pullTimeOut;

    private String namesrvAddr;

    private static final String SEMICOLON = ";";

    private static final String SYS_DEFAULT_GROUP = "event-bridge-default-group";

    public static final String QUEUE_OFFSET = "queueOffset";

    public RocketMQEventSubscriber(TargetRunnerConfigObserver runnerConfigObserver) {
        this.runnerConfigObserver = runnerConfigObserver;
        this.initMqProperties();
        this.initPullConsumer();
    }

    @Override
    public void refresh(TargetRunnerConfig targetRunnerConfig, RefreshTypeEnum refreshTypeEnum) {
        if(Objects.isNull(pullConsumer)){
            pullConsumer = initDefaultMQPullConsumer();
            return;
        }
        Set<String> currentTopics = parseTopicsByRunnerConfigs(Sets.newHashSet(targetRunnerConfig));
        for (String topic : currentTopics){
            switch (refreshTypeEnum){
                case ADD:
                case UPDATE:
                        subscribe(topic);
                        break;
                case DELETE:
                        unSubscribe(topic);
                        break;
                default:
                    break;
            }
        }
    }

    @Override
    public List<ConnectRecord> pull() {
        List<MessageExt> messageExts = pullConsumer.poll(pullTimeOut);
        if (CollectionUtils.isEmpty(messageExts)) {
            logger.info("consumer poll message empty , consumer - {}", JSON.toJSONString(pullConsumer));
            return null;
        }
        List<ConnectRecord> connectRecords = Lists.newArrayList();
        for (MessageExt messageExt : messageExts) {
            ConnectRecord eventRecord = convertToSinkRecord(messageExt);
            connectRecords.add(eventRecord);
            if(logger.isDebugEnabled()){
                logger.debug("offer listen event record -  {} - by message event- {}", eventRecord, messageExt);
            }
        }
        return connectRecords;
    }

    @Override
    public void commit(List<ConnectRecord> connectRecordList) {

    }

    /**
     * parse topics by specific target runner configs
     * @param targetRunnerConfigs
     * @return
     */
    public Set<String> parseTopicsByRunnerConfigs(Set<TargetRunnerConfig> targetRunnerConfigs){
        if(org.apache.commons.collections.CollectionUtils.isEmpty(targetRunnerConfigs)){
            logger.warn("target runner config is empty, parse to topic failed!");
            return null;
        }
        Set<String> listenTopics = Sets.newHashSet();
        for(TargetRunnerConfig runnerConfig : targetRunnerConfigs){
            List<Map<String,String>> runnerConfigMap = runnerConfig.getComponents();
            if(org.apache.commons.collections.CollectionUtils.isEmpty(runnerConfigMap)){
                continue;
            }
            listenTopics.addAll(runnerConfigMap.stream().map(item->item.get(RuntimerConfigDefine.CONNECT_TOPICNAME)).collect(Collectors.toSet()));
        }
        return listenTopics;
    }

    /**
     * init rocketmq ref config
     */
    private void initMqProperties() {
        try {
            Properties properties = PropertiesLoaderUtils.loadAllProperties("runtimer.properties");
            namesrvAddr = properties.getProperty("rocketmq.namesrvAddr");
            pullTimeOut = Integer.valueOf(properties.getProperty("rocketmq.consumer.pullTimeOut"));
        }catch (Exception exception){

        }

    }

    /**
     * init rocket mq pull consumer
     */
    private void initPullConsumer() {
        pullConsumer = initDefaultMQPullConsumer();
    }

    /**
     * first init default rocketmq pull consumer
     * @return
     */
    public DefaultLitePullConsumer initDefaultMQPullConsumer () {
        Set<TargetRunnerConfig> targetRunnerConfigs = runnerConfigObserver.getTargetRunnerConfig();
        Set<String> topics = parseTopicsByRunnerConfigs(targetRunnerConfigs);
        DefaultLitePullConsumer consumer = new DefaultLitePullConsumer();
        consumer.setConsumerGroup(createGroupName(SYS_DEFAULT_GROUP));
        consumer.setNamesrvAddr(namesrvAddr);
        try {
            for(String topic : topics){
                consumer.subscribe(topic, "*");
            }
            consumer.start();
        } catch (Exception exception) {
            logger.error("init default pull consumer exception, topic -" + topics.toString() + "-stackTrace-", exception);
            throw new EventBridgeException(" init rocketmq consumer failed");
        }
        return consumer;
    }

    private String createGroupName(String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("-");
        sb.append(RemotingUtil.getLocalAddress()).append("-");
        sb.append(UtilAll.getPid()).append("-");
        sb.append(System.nanoTime());
        return sb.toString().replace(".", "-");
    }

    private String createInstance(String servers) {
        String[] serversArray = servers.split(";");
        List<String> serversList = new ArrayList<String>();
        for (String server : serversArray) {
            if (!serversList.contains(server)) {
                serversList.add(server);
            }
        }
        Collections.sort(serversList);
        return String.valueOf(serversList.toString().hashCode());
    }

    /**
     * parse msg queue by queue json
     *
     * @param messageQueueStr
     * @return
     */
    private MessageQueue parseMessageQueueList(String messageQueueStr) {
        List<String> messageQueueStrList = Splitter.on(SEMICOLON).omitEmptyStrings().trimResults().splitToList(messageQueueStr);
        if (org.apache.commons.collections.CollectionUtils.isEmpty(messageQueueStrList) || messageQueueStrList.size() != 3) {
            return null;
        }
        return new MessageQueue(messageQueueStrList.get(0), messageQueueStrList.get(1), Integer.valueOf(messageQueueStrList.get(2)));
    }

    /**
     * MessageExt convert to connect record
     * @param messageExt
     * @return
     */
    private ConnectRecord convertToSinkRecord(MessageExt messageExt) {
        Map<String, String> properties = messageExt.getProperties();
        Schema schema;
        Long timestamp;
        ConnectRecord sinkRecord;
        String connectTimestamp = properties.get(RuntimerConfigDefine.CONNECT_TIMESTAMP);
        timestamp = StringUtils.isNotEmpty(connectTimestamp) ? Long.valueOf(connectTimestamp) : null;
        String connectSchema = properties.get(RuntimerConfigDefine.CONNECT_SCHEMA);
        schema = StringUtils.isNotEmpty(connectSchema) ? JSON.parseObject(connectSchema, Schema.class) : null;
        byte[] body = messageExt.getBody();
        RecordPartition recordPartition = convertToRecordPartition(messageExt.getTopic(), messageExt.getBrokerName(), messageExt.getQueueId());
        RecordOffset recordOffset = convertToRecordOffset(messageExt.getQueueOffset());
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        sinkRecord = new ConnectRecord(recordPartition, recordOffset, timestamp, schema, bodyStr);
        KeyValue keyValue = new DefaultKeyValue();
        keyValue.put(RuntimerConfigDefine.CONNECT_TOPICNAME, messageExt.getTopic());
        if (MapUtils.isNotEmpty(properties)) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                keyValue.put(entry.getKey(), entry.getValue());
            }
        }
        sinkRecord.addExtension(keyValue);
        return sinkRecord;
    }

    private RecordPartition convertToRecordPartition(String topic, String brokerName, int queueId) {
        Map<String, String> map = new HashMap<>();
        map.put("topic", topic);
        map.put("brokerName", brokerName);
        map.put("queueId", queueId + "");
        RecordPartition recordPartition = new RecordPartition(map);
        return recordPartition;
    }

    private RecordOffset convertToRecordOffset(Long offset) {
        Map<String, String> offsetMap = new HashMap<>();
        offsetMap.put(QUEUE_OFFSET, offset + "");
        RecordOffset recordOffset = new RecordOffset(offsetMap);
        return recordOffset;
    }

    /**
     * new topic for subscribe
     * @param topic
     */
    private void subscribe(String topic) {
        try {
            pullConsumer.subscribe(topic, "*");
        } catch (MQClientException exception) {
            logger.error("rocketmq event subscribe new topic failed, stack trace - ", exception);
        }
    }

    /**
     * unsubscribe old topic
     * @param topic
     */
    private void unSubscribe(String topic) {
        pullConsumer.unsubscribe(topic);
    }

}