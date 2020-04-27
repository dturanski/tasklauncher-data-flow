/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.task.launcher.dataflow.sink;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.dataflow.core.DataFlowPropertyKeys;
import org.springframework.cloud.dataflow.rest.client.DataFlowOperations;
import org.springframework.cloud.dataflow.rest.client.DataFlowTemplate;
import org.springframework.cloud.dataflow.rest.client.config.DataFlowClientProperties;
import org.springframework.cloud.dataflow.rest.util.HttpClientConfigurer;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.DefaultPollableMessageSource;
import org.springframework.cloud.stream.binder.PollableMessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.integration.util.DynamicPeriodicTrigger;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration class for the TaskLauncher Data Flow Sink.
 *
 * @author David Turanski
 * @author Gunnar Hillert
 */
@EnableBinding(PollingSink.class)
@EnableConfigurationProperties({TriggerProperties.class, DataflowTaskLauncherSinkProperties.class})
public class TaskLauncherDataflowSinkConfiguration {

	@Value("${autostart:true}")
	private boolean autoStart;

	@Bean
	public DynamicPeriodicTrigger periodicTrigger(TriggerProperties triggerProperties) {
		DynamicPeriodicTrigger trigger = new DynamicPeriodicTrigger(triggerProperties.getPeriod());
		trigger.setInitialDuration(Duration.ofMillis(triggerProperties.getInitialDelay()));
		return trigger;
	}

	/*
	 * For backward compatibility with spring-cloud-stream-2.1.x
	 */
	@EventListener
	public void addInterceptorToPollableMessageSource(ApplicationPreparedEvent event) {
		DefaultPollableMessageSource pollableMessageSource = event.getApplicationContext().getBean(DefaultPollableMessageSource.class);
		pollableMessageSource.addInterceptor(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(Message<?> message, MessageChannel channel) {
				Message<?> newMessage = message;
				if (message.getHeaders().containsKey("originalContentType")) {
					newMessage = MessageBuilder.fromMessage(message)
							.setHeader("contentType", message.getHeaders().get("originalContentType"))
							.build();
				}
				return newMessage;
			}
		});
	}

	@Bean
	public LaunchRequestConsumer launchRequestConsumer(PollableMessageSource input,
													   DataFlowOperations dataFlowOperations, DynamicPeriodicTrigger trigger, TriggerProperties triggerProperties,
													   DataflowTaskLauncherSinkProperties sinkProperties) {

		if (dataFlowOperations.taskOperations() == null) {
			throw new IllegalArgumentException("The SCDF server does not support task operations");
		}
		LaunchRequestConsumer consumer = new LaunchRequestConsumer(input, trigger, triggerProperties.getMaxPeriod(),
				dataFlowOperations.taskOperations());
		consumer.setAutoStartup(autoStart);
		consumer.setPlatformName(sinkProperties.getPlatformName());
		return consumer;
	}

	@Configuration
	@ConditionalOnProperty(prefix = DataFlowPropertyKeys.PREFIX + "client.authentication", name = "client-id")
	@EnableConfigurationProperties(DataFlowClientProperties.class)
	static class DataFlowClientConfiguration {

		private static Log logger = LogFactory.getLog(DataFlowClientConfiguration.class);

		private static final String DEFAULT_REGISTRATION_ID = "default";

		@Bean
		public DataFlowOperations dataFlowOperations(@Nullable RestTemplate restTemplate,
													 ClientRegistrationRepository clientRegistrations,
													 OAuth2AccessTokenResponseClient clientCredentialsTokenResponseClient,
													 DataFlowClientProperties properties,
													 OAuth2AccessTokenRenewingClientHttpRequestInterceptor interceptor) throws Exception {
			RestTemplate template = DataFlowTemplate.prepareRestTemplate(restTemplate);
			final HttpClientConfigurer httpClientConfigurer = HttpClientConfigurer.create(new URI(properties.getServerUri()))
					.skipTlsCertificateVerification(properties.isSkipSslValidation());

			template.setRequestFactory(httpClientConfigurer.buildClientHttpRequestFactory());
			template.getInterceptors().add(interceptor);
			logger.debug("Configured OAuth2 Client Credentials for accessing the Data Flow Server");
			return new DataFlowTemplate(new URI(properties.getServerUri()), template);
		}

		@Bean
		public InMemoryClientRegistrationRepository clientRegistrationRepository(
				DataFlowClientProperties properties) {
			ClientRegistration clientRegistration = ClientRegistration
					.withRegistrationId(DEFAULT_REGISTRATION_ID)
					.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
					.tokenUri(properties.getAuthentication().getTokenUri())
					.clientId(properties.getAuthentication().getClientId())
					.clientSecret(properties.getAuthentication().getClientSecret())
					.scope(properties.getAuthentication().getScope())
					.build();
			return new InMemoryClientRegistrationRepository(clientRegistration);
		}

		@Bean
		OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> clientCredentialsTokenResponseClient() {
			return new DefaultClientCredentialsTokenResponseClient();
		}

		@Bean
		OAuth2AccessTokenRenewingClientHttpRequestInterceptor oAuth2AccessTokenRenewingClientHttpRequestInterceptor
				(OAuth2AccessTokenResponse oAuth2AccessTokenResponse) {
			return new OAuth2AccessTokenRenewingClientHttpRequestInterceptor(oAuth2AccessTokenResponse);
		}

		@Bean
		OAuth2AccessTokenResponse renewAccessTokenResponse(ClientRegistrationRepository clientRegistrations,
														   OAuth2AccessTokenResponseClient clientCredentialsTokenResponseClient) {

			ClientRegistration clientRegistration = clientRegistrations.findByRegistrationId(DEFAULT_REGISTRATION_ID);
			OAuth2ClientCredentialsGrantRequest grantRequest = new OAuth2ClientCredentialsGrantRequest(clientRegistration);
			return clientCredentialsTokenResponseClient.getTokenResponse(grantRequest);
		}

		static class OAuth2AccessTokenRenewingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

			private RetryTemplate retryTemplate = new RetryTemplate();

			private final OAuth2AccessTokenResponse oAuth2AccessTokenResponse;

			private ThreadLocal<String> oauthAccessToken = new ThreadLocal<>();

			OAuth2AccessTokenRenewingClientHttpRequestInterceptor(OAuth2AccessTokenResponse oAuth2AccessTokenResponse) {
				Assert.notNull(oAuth2AccessTokenResponse, "'oAuth2AccessTokenResponse' cannot be null");
				this.oAuth2AccessTokenResponse = oAuth2AccessTokenResponse;
				this.oauthAccessToken.set(oAuth2AccessTokenResponse.getAccessToken().getTokenValue());
			}

			@Override
			public ClientHttpResponse intercept(HttpRequest httpRequest, byte[] bytes, ClientHttpRequestExecution execution) throws IOException {
				return retryTemplate.execute(retryContext -> {
					httpRequest.getHeaders().add("Authorization",
							OAuth2AccessToken.TokenType.BEARER.getValue() + " " + this.oauthAccessToken.get());
					ClientHttpResponse response = execution.execute(httpRequest, bytes);
					if (response.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {

						try {
							this.oauthAccessToken.set(oAuth2AccessTokenResponse.getAccessToken().getTokenValue());
							httpRequest.getHeaders().add("Authorization",
									OAuth2AccessToken.TokenType.BEARER.getValue() + " " + this.oauthAccessToken.get());
							response = execution.execute(httpRequest, bytes);
						} catch (IOException e) {
							throw new RuntimeException(e.getCause());
						}
					}
					return response;
				});
			}
		}
	}
}


