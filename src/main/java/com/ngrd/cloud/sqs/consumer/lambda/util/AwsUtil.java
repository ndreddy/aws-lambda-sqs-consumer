package com.ngrd.cloud.sqs.consumer.lambda.util;

import com.amazonaws.services.sqs.model.Message;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AwsUtil {
    public static Map<String, String> convertMessages(List<Message> messages) {
        return messages.stream().collect(Collectors.toMap(Message::getReceiptHandle, Message::getBody));
    }
}
