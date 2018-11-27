package com.ngrd.cloud.sqs.consumer.lambda.aws;

import com.ngrd.cloud.sqs.consumer.lambda.io.FunctionRes;
import org.springframework.cloud.function.adapter.aws.SpringBootRequestHandler;

import java.util.Map;

public class ConsumerFunctionHandler extends SpringBootRequestHandler<Map<String, Object>, FunctionRes> {
}
