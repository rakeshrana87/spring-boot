/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Integration tests for {@link MetricsAutoConfiguration}.
 *
 * @author Jon Schneider
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = MetricsAutoConfigurationIntegrationTests.MetricsApp.class)
@TestPropertySource(properties = "management.metrics.use-global-registry=false")
public class MetricsAutoConfigurationIntegrationTests {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private RestTemplate external;

	@Autowired
	private TestRestTemplate loopback;

	@Autowired
	private MeterRegistry registry;

	@Autowired
	private CyclicBarrier cyclicBarrier;

	@SuppressWarnings("unchecked")
	@Test
	public void restTemplateIsInstrumented() {
		MockRestServiceServer server = MockRestServiceServer.bindTo(this.external)
				.build();
		server.expect(once(), requestTo("/api/external"))
				.andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
						"{\"message\": \"hello\"}", MediaType.APPLICATION_JSON));
		assertThat(this.external.getForObject("/api/external", Map.class))
				.containsKey("message");
		assertThat(this.registry.get("http.client.requests").timer().count())
				.isEqualTo(1);
	}

	@Test
	public void requestMappingIsInstrumented() {
		this.loopback.getForObject("/api/people", Set.class);
		assertThat(this.registry.get("http.server.requests").timer().count())
				.isEqualTo(1);
	}

	@Test
	public void automaticallyRegisteredBinders() {
		assertThat(this.context.getBeansOfType(MeterBinder.class).values())
				.hasAtLeastOneElementOfType(LogbackMetrics.class)
				.hasAtLeastOneElementOfType(JvmMemoryMetrics.class);
	}

	@Test
	public void asyncRequestMappingIsInstrumented()
			throws InterruptedException, BrokenBarrierException {
		Thread backgroundRequest = new Thread(
				() -> this.loopback.getForObject("/api/async", String.class));
		backgroundRequest.start();
		this.cyclicBarrier.await();
		MockClock.clock(this.registry).addSeconds(2);
		this.cyclicBarrier.await();
		backgroundRequest.join();
		Timer timer = this.registry.get("http.server.requests").tags("uri", "/api/async")
				.timer();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(2);
	}

	@Configuration
	@ImportAutoConfiguration({ MetricsAutoConfiguration.class,
			JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
			RestTemplateAutoConfiguration.class, WebMvcAutoConfiguration.class,
			DispatcherServletAutoConfiguration.class,
			ServletWebServerFactoryAutoConfiguration.class })
	@Import(PersonController.class)
	static class MetricsApp {

		@Bean
		public MeterRegistry registry() {
			return new SimpleMeterRegistry(SimpleConfig.DEFAULT, new MockClock());
		}

		@Bean
		public RestTemplate restTemplate(RestTemplateBuilder restTemplateBuilder) {
			return restTemplateBuilder.build();
		}

		@Bean
		public CyclicBarrier cyclicBarrier() {
			return new CyclicBarrier(2);
		}

	}

	@RestController
	static class PersonController {

		private final CyclicBarrier cyclicBarrier;

		PersonController(CyclicBarrier cyclicBarrier) {
			this.cyclicBarrier = cyclicBarrier;
		}

		@GetMapping("/api/people")
		Set<String> personName() {
			return Collections.singleton("Jon");
		}

		@GetMapping("/api/async")
		CompletableFuture<String> asyncHello()
				throws BrokenBarrierException, InterruptedException {
			this.cyclicBarrier.await();
			return CompletableFuture.supplyAsync(this::awaitAndHello);
		}

		private String awaitAndHello() {
			try {
				this.cyclicBarrier.await();
				return "async-hello";
			}
			catch (InterruptedException | BrokenBarrierException ex) {
				throw new RuntimeException(ex);
			}
		}

	}

}
