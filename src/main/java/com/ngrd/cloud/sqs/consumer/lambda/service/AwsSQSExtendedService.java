package com.ngrd.cloud.sqs.consumer.lambda.service;

import com.amazon.sqs.javamessaging.AmazonSQSExtendedClient;
import com.amazon.sqs.javamessaging.ExtendedClientConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.ngrd.cloud.sqs.consumer.lambda.util.AwsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class AwsSQSExtendedService implements MessageService {

    static final Logger log = LoggerFactory.getLogger(AwsSQSExtendedService.class);
    private static AmazonSQSExtendedClient sqsExtended;
    public static final String BUCKET_NAME = "ambr-integration-messages";

    static {
        sqsExtended = newAmazonSQSExtendedClient(BUCKET_NAME);
    }


    public Map<String, String> receiveMessage(String queueUrl) {
        log.debug("Polling queue, MaxNumberOfMessages(10), WaitTimeSeconds(10) " + queueUrl);
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl);
        receiveMessageRequest.setMaxNumberOfMessages(10);
        receiveMessageRequest.setWaitTimeSeconds(10);
        List<Message> messages = sqsExtended
                .receiveMessage(receiveMessageRequest).getMessages();
        return AwsUtil.convertMessages(messages);
    }


    @Override
    public void deleteMessage(String queueUrl, String messageReceiptHandle) {
        sqsExtended.deleteMessage(new DeleteMessageRequest(queueUrl, messageReceiptHandle));
    }

    /**
     * Instantiates SQS Extended d=Client
     *
     * @param bucketName bucket name
     * @return SQS Extended Client
     */
    private static AmazonSQSExtendedClient newAmazonSQSExtendedClient(String bucketName) {

        /*
         * Create a new instance of the builder with all defaults (credentials
         * and region) set automatically. For more information, see
         * Creating Service Clients in the AWS SDK for Java Developer Guide.
         */
        final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

        /*
         * Set the Amazon S3 bucket name, and then set a lifecycle rule on the
         * bucket to permanently delete objects 14 days after each object's
         * creation date.
         */
        final BucketLifecycleConfiguration.Rule expirationRule =
                new BucketLifecycleConfiguration.Rule();
        expirationRule.withExpirationInDays(14).withStatus("Enabled");
        final BucketLifecycleConfiguration lifecycleConfig =
                new BucketLifecycleConfiguration().withRules(expirationRule);
        s3.setBucketLifecycleConfiguration(bucketName, lifecycleConfig);

        /*
         * Set the Amazon SQS extended client configuration with large payload
         * support enabled.
         */
        final ExtendedClientConfiguration extendedClientConfig =
                new ExtendedClientConfiguration()
                        .withLargePayloadSupportEnabled(s3, bucketName);

        return new AmazonSQSExtendedClient(AmazonSQSClientBuilder
                .defaultClient(), extendedClientConfig);
    }


    /**
     * Gets parameter from AWS SSM Parameter store
     *
     * @param key key of the parameter store
     * @return value
     */
    public String getParameterFromStore(String key) {
        String value = null;
        AWSSimpleSystemsManagement ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();
        GetParameterRequest parameterRequest = new GetParameterRequest();
        parameterRequest.withName(key).setWithDecryption(Boolean.TRUE);
        GetParameterResult parameterResult = ssmClient.getParameter(parameterRequest);
        Parameter param = parameterResult.getParameter();
        if (param != null) {
            value = param.getValue();
        }
        return value;
    }


}



