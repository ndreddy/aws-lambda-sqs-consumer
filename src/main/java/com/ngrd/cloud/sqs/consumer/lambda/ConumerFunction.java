package com.ngrd.cloud.sqs.consumer.lambda;

import com.ngrd.cloud.sqs.consumer.lambda.io.FunctionRes;
import com.ngrd.cloud.sqs.consumer.lambda.service.FunctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

@Component("consumerFunction")
public class ConumerFunction implements Function<Map<String, Object>, FunctionRes> {

    // Initialize the Log4j logger.
    static final Logger log = LoggerFactory.getLogger(ConumerFunction.class);


    private final FunctionService functionService;

    public ConumerFunction(final FunctionService functionService) {
        this.functionService = functionService;
    }

    @Override
    public FunctionRes apply(Map<String, Object> input) {
        final FunctionRes response = new FunctionRes();
        response.setBody(functionService.processRequest());
        response.setStatusCode(200);
        return response;
    }


}
