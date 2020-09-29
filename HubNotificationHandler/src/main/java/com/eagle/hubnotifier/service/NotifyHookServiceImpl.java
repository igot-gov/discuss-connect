package com.eagle.hubnotifier.service;

import java.util.*;

import com.eagle.hubnotifier.config.Configuration;
import com.eagle.hubnotifier.model.TopicData;
import com.eagle.hubnotifier.util.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.eagle.hubnotifier.model.HubUser;
import com.eagle.hubnotifier.model.NotificationEvent;
import com.eagle.hubnotifier.model.TopicFollower;
import com.eagle.hubnotifier.producer.NotifyHookProducer;
import com.eagle.hubnotifier.repository.HubUserRepository;
import com.eagle.hubnotifier.repository.TopicFollowerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

@Service
public class NotifyHookServiceImpl implements NotifyHookService {
    Logger logger = LogManager.getLogger(NotifyHookServiceImpl.class);

    @Autowired
    private NotifyHookProducer producer;

    @Autowired
    private NotifyHandlerServiceImpl notifyHandler;

    @Autowired
    private TopicFollowerRepository topicRepository;

    @Autowired
    private HubUserRepository userRepository;

    @Autowired
    private Configuration configuration;

    @Autowired
    private OutboundRequestHandlerServiceImpl requestHandlerService;

    @Override
    public void handleNotifiyRestRequest(Map<String, Object> data) {
        if (logger.isDebugEnabled()) {
            logger.info("Recived request from Rest Controller");
        }
        producer.push(configuration.getNotifyTopic(), data);
    }

    @Override
    public void handleNotifyKafkaTopicRequest(Map<String, Object> data) {
        logger.info("Recived request from Topic Consumer");
        ObjectMapper mapper = new ObjectMapper();
        try {
            logger.info("Received Data : {}", mapper.writeValueAsString(data));
        } catch (JsonProcessingException ex) {
            logger.error("Not able to parse the data", ex);
        }
        List<String> hookList = (List<String>) data.get("hook");
        if (hookList != null) {
            for (String hook : hookList) {
                switch (hook) {
                    case Constants.FILTER_TOPIC_CREATE:
                        handleTopicCreate(data);
                        break;
                    case Constants.FILTER_POST_CREATE:
                        handlePostCreate(data);
                        break;
                    case Constants.FILTER_TOPIC_REPLY:
                        handleTopicReplyEvent(data);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /**
     * Send the notification on creating the topic
     *
     * @param data
     */
    @SuppressWarnings("unchecked")
    private void handleTopicCreate(Map<String, Object> data) {
        logger.info("Received Topic Creation Event");
        NotificationEvent nEvent = new NotificationEvent();
        nEvent.setEventId(Constants.DISCUSSION_CREATION_EVENT_ID);
        Map<String, Object> tagValues = new HashMap<String, Object>();
        List<String> topicTitleList = (List<String>) data.get(Constants.PARAM_TOPIC_TITLE_CONSTANT);
        tagValues.put(Constants.DISCUSSION_CREATION_TOPIC_TAG, topicTitleList.get(0));
        List<String> topicIds = (List<String>) data.get(Constants.PARAM_TOPIC_TID_CONSTANT);
        tagValues.put(Constants.DISCUSSION_CREATION_TARGET_URL, configuration.getDiscussionCreateUrl() + topicIds.get(0));
        List<String> topicUids = (List<String>) data.get(Constants.PARAM_TOPIC_UID_CONSTANT);
        HubUser user = userRepository.findByKey(Constants.USER_ROLE + ":" + topicUids.get(0));
        Map<String, List<String>> recipients = new HashMap<String, List<String>>();
        recipients.put(Constants.AUTHOR_ROLE, Arrays.asList(user.getUsername()));
        nEvent.setTagValues(tagValues);
        nEvent.setRecipients(recipients);
        notifyHandler.sendNotification(nEvent);
    }

    /**
     *Handle Post Create Request
     * @param data
     */
    private void handlePostCreate(Map<String, Object> data) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            logger.debug("Received Post Creation Event. Data : {}", mapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
        }

        NotificationEvent nEvent = new NotificationEvent();
        nEvent.setEventId("discussion_comment_creation");

        Map<String, List<String>> recipients = new HashMap<String, List<String>>();

        List<String> tidList = (List<String>) data.get("params[post][tid]");
        String tid = tidList.get(0);
        String tIdFolowerKey = "tid:" + tid + ":followers";
        logger.info("Post Created in Topic Id - {}", tid);

        // Get the Topic Followers
        TopicFollower topicFollower = topicRepository.findByKey(tIdFolowerKey);
        List<String> listeners = new ArrayList<String>();
        if (topicFollower != null) {
            for (String uid : topicFollower.getMembers()) {
                logger.info("Fetching User details for UID - {}", uid);
                String userKey = Constants.USER_ROLE + ":" + uid;
                HubUser user = userRepository.findByKey(userKey);
                if (user != null) {
                    listeners.add(user.getUsername());
                }
            }
            logger.info("Topic: {} has followed by : {}", tid, listeners);
        }

        recipients.put("listeners", listeners);

        //TODO -- Construct the nEvent object and send request to NotificationService.
    }

    /**
     * Handle the topic reply event and send the notification on replying on the topic
     * @param data
     */
    private void handleTopicReplyEvent(Map<String, Object> data) {
        NotificationEvent nEvent = new NotificationEvent();
        nEvent.setEventId(Constants.DISCUSSION_REPLY_EVENT_ID);
        Map<String, Object> tagValues = new HashMap<String, Object>();
        List<String> commentList = (List<String>) data.get(Constants.PARAM_CONTENT_CONSTANT);
        tagValues.put(Constants.COMMENT_TAG, commentList.get(0));
        List<String> repliedByUuids = (List<String>) data.get(Constants.PARAM_UID);
        HubUser repliedByUser = userRepository.findByKey(Constants.USER_ROLE + ":" + repliedByUuids.get(0));
        tagValues.put(Constants.COMMENTED_BY_NAME_TAG, repliedByUser.getUsername());
        List<String> topicIds = (List<String>)data.get(Constants.PARAM_TID);
        tagValues.put(Constants.DISCUSSION_CREATION_TARGET_URL, configuration.getDiscussionCreateUrl() + topicIds.get(0));
        Map<String, List<String>> recipients = new HashMap<String, List<String>>();
        TopicData topicData = getTopicData(topicIds.get(0));
        if(!ObjectUtils.isEmpty(topicData)){
            tagValues.put(Constants.DISCUSSION_CREATION_TOPIC_TAG, topicData.getTitle());
            HubUser author = userRepository.findByKey(Constants.USER_ROLE + ":" + topicData.getUid());
            recipients.put(Constants.AUTHOR_ROLE, Arrays.asList(author.getUsername()));
        }
        recipients.put(Constants.COMMENTED_BY_TAG, Arrays.asList(repliedByUser.getUsername()));
        nEvent.setTagValues(tagValues);
        nEvent.setRecipients(recipients);
        notifyHandler.sendNotification(nEvent);
    }

    /**
     * Get topic data based on topic id
     * @param topicId
     * @return Topic data
     */
    private TopicData getTopicData(String topicId) {
        StringBuilder builder = new StringBuilder();
        ObjectMapper mapper = new ObjectMapper();
        TopicData topicData = null;
        String topicSearchPath = configuration.getTopicSearchPath().replace("{topicId}", topicId);
        builder.append(configuration.getHubServiceHost()).append(configuration.getHubServiceGetPath()).append(topicSearchPath);
        try {
           Object response =  requestHandlerService.fetchResult(builder);
           topicData = mapper.convertValue(response, TopicData.class);
        } catch (Exception e) {
            logger.error("Error while searching topic :", e);
        }
        return topicData;
    }
}
