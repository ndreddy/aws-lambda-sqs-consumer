package com.ngrd.cloud.sqs.consumer.lambda.service;

import com.amazonaws.services.simplesystemsmanagement.model.ParameterNotFoundException;
import com.ngrd.cloud.sqs.consumer.lambda.io.FunctionReq;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Services the message processing requests.
 * It fetches messages (10 in batch) from Queue and submits to worker pool for processing.
 * It make REST calls to token end point and Trade Automation end points.
 * Finally it deletes message from the Queue if success.
 */
@Service
public class FunctionService {

    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);
    private static final String ACCEPT = "Accept";
    private static final String ON_BEHALF = "ON_BEHALF";
    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_CREDENTIALS = "client_credentials";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    public static final int N_THREADS = 10;
    private static String TOKEN_API = System.getenv("TOKEN_API");
    private static String CLIENT_ID_VAL = System.getenv("CLIENT_ID_VAL");
    private static String CLIENT_SECRET_VAL = System.getenv("CLIENT_SECRET_VAL");
    private static String ON_BEHALF_TOKEN = null;
    private static final String ACCESS_TOKEN_KEY = "access_token";
    private static final String QUEUE_URL = "IntegrationMessagesQueue.fifo";
    private static final String DEFAULT = "default";
    private MessageService messageService;

    // Thread Pool
    private ExecutorService executor;

    // Auto wiring of messageService
    public FunctionService(MessageService messageService) {
        this.messageService = messageService;
    }


    /**
     * Starts processing request. Triggered from lambda.
     *
     * @return status
     */
    public String processRequest() {
        log.debug("Polling queue for messages...");

        // 1. Polls for messages and fetches 10 in batch
        Map<String, String> messages = messageService.receiveMessage(QUEUE_URL);
        initOnBehalfToken();

        //2. Submits each message to worker thread pool
        executor = Executors.newFixedThreadPool(N_THREADS);
        messages.forEach((k, v) -> executor.submit(() -> processMessage(k, v)));

        // 3. Waits for workers to complete.
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            log.info("Executor await InterruptedException - " + e.getMessage());
        }

        return "Request processed 200 OK";
    }


    /**
     * Starts processing message.
     *
     * @param messageReceiptHandle required for delete
     * @param message              - message to be processed
     */
    private void processMessage(String messageReceiptHandle, String message) {
        log.debug("Processing message by thread..." + Thread.currentThread().getId());
        // Convert json message to object
        FunctionReq msgObj = getMsgObjFromJson(message);

        //  E.g /test/default/integrationUri : https://ta1569.tradeautomation15.com/TA/IntegrationMessageLoader;
        final String uri = lookupTAUri(msgObj.getApiKey(), msgObj.getStage());


        // Sends to TA and deletes the msg from SQS
        sendToTA(messageReceiptHandle, msgObj, uri);
    }

    /**
     * Sends the message to TA and deletes from Queue on success.
     *
     * @param messageReceiptHandle - needed for delete
     * @param msgObj               -
     * @param uri                  - TA url
     */
    private void sendToTA(String messageReceiptHandle, FunctionReq msgObj, String uri) {

        String body = msgObj.getBody();
        String userId = msgObj.getUserId();
        String requestId = msgObj.getRequestId();
        ResponseEntity<String> response = null;

        // 1. Posts the message to TA
        log.debug("POSTing to TA " + uri + " request-id:"+requestId);
        try {
            response = postToTA(uri, ON_BEHALF_TOKEN, body, userId);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.error(" TA Response UNAUTHORIZED for request-id: " + requestId);

                //2. Retry one more time with new access token
                ON_BEHALF_TOKEN = null;
                log.info("Refreshing the token for request-id: " + requestId);
                initOnBehalfToken();
                response = postToTA(uri, ON_BEHALF_TOKEN, body, userId);
            }
        }

        log.info("TA Response for request-id:  " + requestId + ":" + response);
        //3. Deletes from Queue
        deleteFromQ(messageReceiptHandle, response);
    }

    /**
     * Makes POST request to Trade Automation.
     *
     * @param uri         - TA Url
     * @param accessToken - Bearer token
     * @param body        - XML paylod
     * @param userId      - On behalf user
     * @return Response entity of type String
     */
    private static ResponseEntity<String> postToTA(String uri, String accessToken, String body, String userId) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add(ON_BEHALF, userId);
        HttpEntity<?> httpEntity = new HttpEntity<Object>(body, headers);
        return restTemplate.exchange(uri, HttpMethod.POST, httpEntity, String.class);
    }

    /**
     * Deletes message from Queue
     *
     * @param messageReceiptHandle message handle
     * @param response             http response
     */
    private void deleteFromQ(String messageReceiptHandle, ResponseEntity<String> response) {
        // If 200 OK and status is Success, deletes the messages.
        // Else it will be tried 3 times and moved to DLQ.
        if (response != null && response.getStatusCode() == HttpStatus.OK) {

            //Parses the XML response body for status ....
            Element rootElem = parseXml(response.getBody());

            if (rootElem == null) {
                return;
            }

            String status = getString("STATUS", rootElem);

            log.debug("TA response status - " + status);
            if ("SUCCESS".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                // Delete the message from Queue
                log.debug("Deleting the message from queue...");
                messageService.deleteMessage(QUEUE_URL, messageReceiptHandle);
            }
        }
    }



    /**
     * Looks up the TA url in Parameter Store
     *
     * @param apiKey api key
     * @param stage  deployment stage
     * @return URI of TA service
     */
    private String lookupTAUri(String apiKey, String stage) {
        String value = null;
        StringBuilder key = new StringBuilder("/" + stage + "/");
        if (apiKey != null) {
            key.append(apiKey);
            key.append("/" + "integrationUri");
            try {
                value = messageService.getParameterFromStore(key.toString());
            }catch (ParameterNotFoundException pe){
                key = new StringBuilder("/" + stage + "/");
                key.append(DEFAULT);
                key.append("/" + "integrationUri");
                value =  messageService.getParameterFromStore(key.toString());
            }
        } else {
            key.append(DEFAULT);
        }

        log.debug("Looking into config store for TA URL with key = " + key.toString());

        log.debug("TA URL  = " + value);
        return value;
    }


    /**
     * Get the token
     *
     * @return token
     */
    private String initOnBehalfToken() {

        if (ON_BEHALF_TOKEN != null) {
            return ON_BEHALF_TOKEN;
        }

        // REST Call to token end point
        String response = makeGetReqOnTokenEndpoint();

        ON_BEHALF_TOKEN = getAccessTokenFromJson(response);

        return ON_BEHALF_TOKEN;
    }


    /**
     * Makes rest call to token end point with client credential flow
     *
     * @return json
     */
    private String makeGetReqOnTokenEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set(ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(TOKEN_API)
                .queryParam(GRANT_TYPE, CLIENT_CREDENTIALS)
                .queryParam(CLIENT_ID, CLIENT_ID_VAL)
                .queryParam(CLIENT_SECRET, CLIENT_SECRET_VAL);

        HttpEntity<?> entity = new HttpEntity<>(headers);
        log.debug("Making GET on Token API " + builder.toUriString());
        ResponseEntity<String> response = restTemplate.exchange(
                builder.toUriString(),
                HttpMethod.GET,
                entity,
                String.class);
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new HttpClientErrorException(response.getStatusCode());
        }
        log.debug("GET Response Body : " + response.getBody());
        return response.getBody();
    }

    /**
     * Converts json to object.
     *
     * @param msg Json message
     * @return Json object of the message.
     */
    private static FunctionReq getMsgObjFromJson(String msg) {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        return gson.fromJson(msg, FunctionReq.class);
    }

    /**
     * Gets value from Json string for a given key
     *
     * @param response json response
     * @return value string
     */
    private static String getAccessTokenFromJson(String response) {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        JsonObject jsonObject = gson.fromJson(response, JsonObject.class);

        if (jsonObject.has(FunctionService.ACCESS_TOKEN_KEY)) {
            return jsonObject.get(FunctionService.ACCESS_TOKEN_KEY).getAsString();
        }
        log.error("Access token not found in Token API response.");
        return null;
    }

    private Element parseXml(String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element rootElement = document.getDocumentElement();
            return rootElement;
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getString(String tagName, Element element) {
        NodeList list = element.getElementsByTagName(tagName);
        if (list != null && list.getLength() > 0) {
            NodeList subList = list.item(0).getChildNodes();

            if (subList != null && subList.getLength() > 0) {
                return subList.item(0).getNodeValue();
            }
        }

        return null;
    }
}
