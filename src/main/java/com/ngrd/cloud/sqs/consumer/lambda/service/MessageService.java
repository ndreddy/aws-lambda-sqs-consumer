package com.ngrd.cloud.sqs.consumer.lambda.service;

import java.util.Map;

public interface MessageService {

    void deleteMessage(String queueUrl, String messageReceiptHandle);

    Map<String, String> receiveMessage(String queueUrl);

    String getParameterFromStore(String key);

}
