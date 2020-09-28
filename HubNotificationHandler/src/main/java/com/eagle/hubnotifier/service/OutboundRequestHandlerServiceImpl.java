package com.eagle.hubnotifier.service;

import java.util.Map;

import com.eagle.hubnotifier.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

@Service
public class OutboundRequestHandlerServiceImpl {
	Logger logger = LogManager.getLogger(OutboundRequestHandlerServiceImpl.class);

	@Autowired
	private RestTemplate restTemplate;

	@Autowired
	private Configuration configuration;

	public Object fetchResultUsingPost(StringBuilder uri, Object request) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		Object response = null;
		StringBuilder str = new StringBuilder(this.getClass().getCanonicalName()).append(".fetchResult:")
				.append(System.lineSeparator());
		str.append("URI: ").append(uri.toString()).append(System.lineSeparator());
		try {
			str.append("Request: ").append(mapper.writeValueAsString(request)).append(System.lineSeparator());
			logger.debug(str.toString());
			HttpHeaders headers = new HttpHeaders();
			headers.set("rootOrg", configuration.getHubRootOrg());
			HttpEntity entity = new HttpEntity<>(request, headers);
			response = restTemplate.postForObject(uri.toString(), entity, Map.class);
		} catch (HttpClientErrorException e) {
			logger.error("External Service threw an Exception: ", e);
			throw new Exception(e);
		} catch (Exception e) {
			logger.error("Exception while posting the data in notification service: ", e);
		}
		return response;
	}

	public Object fetchResult(StringBuilder uri) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		Object response = null;
		StringBuilder str = new StringBuilder(this.getClass().getCanonicalName()).append(".fetchResult:")
				.append(System.lineSeparator());
		str.append("URI: ").append(uri.toString()).append(System.lineSeparator());
		try {
			logger.debug(str.toString());
			response = restTemplate.getForObject(uri.toString(), Map.class);
		} catch (HttpClientErrorException e) {
			logger.error("External Service threw an Exception: ", e);
			throw new Exception(e);
		} catch (Exception e) {
			logger.error("Exception while fetching from searcher: ", e);
		}
		return response;
	}
}
