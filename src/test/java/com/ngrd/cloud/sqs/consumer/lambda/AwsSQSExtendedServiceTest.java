package com.ngrd.cloud.sqs.consumer.lambda;

import com.amazonaws.services.sqs.model.Message;
import com.ngrd.cloud.sqs.consumer.lambda.util.AwsUtil;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AwsSQSExtendedServiceTest {


    @Test
    public void testConvert() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Message message = new Message();
            message.setBody("Test " + i);
            message.setReceiptHandle("fsdffsadfasdfs " + i);
            messages.add(message);
        }

       Map<String, String> sMessages = AwsUtil.convertMessages(messages);
        sMessages.forEach((k,v)->System.out.println("Key : " + k + " Value : " + v));
        assertEquals(sMessages.size(),5);

    }


}