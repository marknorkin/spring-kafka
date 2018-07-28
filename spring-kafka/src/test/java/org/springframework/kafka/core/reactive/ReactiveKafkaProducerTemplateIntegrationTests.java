/*
 * Copyright 2018 the original author or authors.
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

package org.springframework.kafka.core.reactive;

import static org.springframework.kafka.test.assertj.KafkaConditions.match;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Subscription;

import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;

/**
 * @author Mark Norkin
 */
public class ReactiveKafkaProducerTemplateIntegrationTests {
	private static final int DEFAULT_PARTITIONS_COUNT = 2;
	private static final Integer DEFAULT_KEY = 42;
	private static final String DEFAULT_VALUE = "foo_data";
	private static final int DEFAULT_PARTITION = 1;
	private static final long DEFAULT_TIMESTAMP = Instant.now().toEpochMilli();
	private static final String REACTIVE_INT_KEY_TOPIC = "reactive_int_key_topic";

	@ClassRule
	public static KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, DEFAULT_PARTITIONS_COUNT, REACTIVE_INT_KEY_TOPIC);
	private static Map<String, Object> consumerProps;

	private ReactiveKafkaProducerTemplate<Integer, String> reactiveKafkaProducerTemplate;
	private ReactiveKafkaConsumerTemplate<Integer, String> reactiveKafkaConsumerTemplate;


	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		consumerProps = KafkaTestUtils.consumerProps("reactive_consumer_group", "false", embeddedKafka);
	}

	@Before
	public void setUp() {
		Map<String, Object> senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString());

		SenderOptions<Integer, String> senderOptions = SenderOptions.create(senderProps);
		RecordMessageConverter messagingConverter = new MessagingMessageConverter();
		reactiveKafkaProducerTemplate = new ReactiveKafkaProducerTemplate<>(senderOptions, messagingConverter);
		reactiveKafkaConsumerTemplate = new ReactiveKafkaConsumerTemplate<>(setupReceiverOptionsWithDefaultTopic(consumerProps));
	}

	private ReceiverOptions<Integer, String> setupReceiverOptionsWithDefaultTopic(Map<String, Object> consumerProps) {
		ReceiverOptions<Integer, String> basicReceiverOptions = ReceiverOptions.create(consumerProps);
		return basicReceiverOptions
				.consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
				.addAssignListener(p -> Assertions.assertThat(p.iterator().next().topicPartition().topic()).isEqualTo(REACTIVE_INT_KEY_TOPIC))
				.subscription(Collections.singletonList(REACTIVE_INT_KEY_TOPIC));
	}

	@After
	public void tearDown() throws Exception {
		reactiveKafkaProducerTemplate.close();
	}

	@Test
	public void shouldSendSingleRecordAsKeyAndReceiveIt() {
		Mono<SenderResult<Void>> senderResultMono = reactiveKafkaProducerTemplate.send(REACTIVE_INT_KEY_TOPIC, DEFAULT_VALUE);

		StepVerifier.create(senderResultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsKeyValueAndReceiveIt() {
		Mono<SenderResult<Void>> resultMono = reactiveKafkaProducerTemplate.send(REACTIVE_INT_KEY_TOPIC, DEFAULT_KEY, DEFAULT_VALUE);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.key()).isEqualTo(DEFAULT_KEY);
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsPartitionKeyValueAndReceiveIt() {
		Mono<SenderResult<Void>> resultMono = reactiveKafkaProducerTemplate.send(REACTIVE_INT_KEY_TOPIC, DEFAULT_PARTITION, DEFAULT_KEY, DEFAULT_VALUE);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())))
							.has(match(recordMetadata -> DEFAULT_PARTITION == recordMetadata.partition()));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.partition()).isEqualTo(DEFAULT_PARTITION);
								Assertions.assertThat(receiverRecord.key()).isEqualTo(DEFAULT_KEY);
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsPartitionTimestampKeyValueAndReceiveIt() {
		Mono<SenderResult<Void>> resultMono = reactiveKafkaProducerTemplate.send(REACTIVE_INT_KEY_TOPIC, DEFAULT_PARTITION, DEFAULT_TIMESTAMP, DEFAULT_KEY, DEFAULT_VALUE);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())))
							.has(match(recordMetadata -> DEFAULT_PARTITION == recordMetadata.partition()))
							.has(match(recordMetadata -> DEFAULT_TIMESTAMP == recordMetadata.timestamp()));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.partition()).isEqualTo(DEFAULT_PARTITION);
								Assertions.assertThat(receiverRecord.timestamp()).isEqualTo(DEFAULT_TIMESTAMP);
								Assertions.assertThat(receiverRecord.key()).isEqualTo(DEFAULT_KEY);
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsProducerRecordAndReceiveIt() {
		List<Header> producerRecordHeaders = convertToKafkaHeaders(
				new SimpleImmutableEntry<>(KafkaHeaders.PARTITION_ID, 0),
				new SimpleImmutableEntry<>("foo", "bar"),
				new SimpleImmutableEntry<>(KafkaHeaders.RECEIVED_TOPIC, "dummy"));

		ProducerRecord<Integer, String> producerRecord =
				new ProducerRecord<>(REACTIVE_INT_KEY_TOPIC, DEFAULT_PARTITION, DEFAULT_TIMESTAMP, DEFAULT_KEY, DEFAULT_VALUE, producerRecordHeaders);

		Mono<SenderResult<Void>> resultMono = reactiveKafkaProducerTemplate.send(producerRecord);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())))
							.has(match(recordMetadata -> DEFAULT_PARTITION == recordMetadata.partition()))
							.has(match(recordMetadata -> DEFAULT_TIMESTAMP == recordMetadata.timestamp()));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.partition()).isEqualTo(DEFAULT_PARTITION);
								Assertions.assertThat(receiverRecord.timestamp()).isEqualTo(DEFAULT_TIMESTAMP);
								Assertions.assertThat(receiverRecord.key()).isEqualTo(DEFAULT_KEY);
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
								Assertions.assertThat(receiverRecord.headers().toArray()).isEqualTo(producerRecordHeaders.toArray());
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsSenderRecordAndReceiveIt() {
		List<Header> producerRecordHeaders = convertToKafkaHeaders(
				new SimpleImmutableEntry<>(KafkaHeaders.PARTITION_ID, 0),
				new SimpleImmutableEntry<>("foo", "bar"),
				new SimpleImmutableEntry<>(KafkaHeaders.RECEIVED_TOPIC, "dummy"));

		ProducerRecord<Integer, String> producerRecord =
				new ProducerRecord<>(REACTIVE_INT_KEY_TOPIC, DEFAULT_PARTITION, DEFAULT_TIMESTAMP, DEFAULT_KEY, DEFAULT_VALUE, producerRecordHeaders);

		int correlationMetadata = 42;
		SenderRecord<Integer, String, Integer> senderRecord = SenderRecord.create(producerRecord, correlationMetadata);
		Mono<SenderResult<Integer>> resultMono = reactiveKafkaProducerTemplate.send(senderRecord);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())))
							.has(match(recordMetadata -> DEFAULT_PARTITION == recordMetadata.partition()))
							.has(match(recordMetadata -> DEFAULT_TIMESTAMP == recordMetadata.timestamp()))
							.has(match(recordMetadata -> correlationMetadata == senderRecord.correlationMetadata()));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.partition()).isEqualTo(DEFAULT_PARTITION);
								Assertions.assertThat(receiverRecord.timestamp()).isEqualTo(DEFAULT_TIMESTAMP);
								Assertions.assertThat(receiverRecord.key()).isEqualTo(DEFAULT_KEY);
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);
								Assertions.assertThat(receiverRecord.headers().toArray()).isEqualTo(producerRecordHeaders.toArray());
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldSendSingleRecordAsMessageAndReceiveIt() {
		Message<String> message = MessageBuilder.withPayload(DEFAULT_VALUE)
				.setHeader(KafkaHeaders.PARTITION_ID, 0)
				.setHeader("foo", "bar")
				.setHeader(KafkaHeaders.RECEIVED_TOPIC, "dummy")
				.build();

		Mono<SenderResult<Void>> resultMono = reactiveKafkaProducerTemplate.send(REACTIVE_INT_KEY_TOPIC, message);

		StepVerifier.create(resultMono)
				.assertNext(senderResult -> {
					Assertions.assertThat(senderResult.recordMetadata())
							.has(match(recordMetadata -> REACTIVE_INT_KEY_TOPIC.equals(recordMetadata.topic())));
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.assertNext(receiverRecord -> {
								Assertions.assertThat(receiverRecord.value()).isEqualTo(DEFAULT_VALUE);

								List<Header> messageHeaders = convertToKafkaHeaders(message.getHeaders());
								Assertions.assertThat(receiverRecord.headers().toArray()).isEqualTo(messageHeaders.toArray());
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void sendMultipleRecordsAsPublisherAndReceiveThem() {
		int msgCount = 10;
		List<SenderRecord<Integer, String, Integer>> senderRecords =
				IntStream.range(0, msgCount)
						.mapToObj(i -> SenderRecord.create(REACTIVE_INT_KEY_TOPIC, DEFAULT_PARTITION, System.currentTimeMillis(), DEFAULT_KEY, DEFAULT_VALUE + i, i))
						.collect(Collectors.toList());

		Flux<SenderRecord<Integer, String, Integer>> senderRecordWithDelay = Flux.fromIterable(senderRecords).delayElements(Duration.ofSeconds(1));
		Flux<SenderResult<Integer>> resultFlux = reactiveKafkaProducerTemplate.send(senderRecordWithDelay);

		StepVerifier.create(resultFlux)
				.recordWith(ArrayList::new)
				.expectNextCount(msgCount)
				.consumeRecordedWith(senderResults -> {
					Assertions.assertThat(senderResults).hasSize(msgCount);

					List<RecordMetadata> records = senderResults.stream().map(SenderResult::recordMetadata).collect(Collectors.toList());

					Assertions.assertThat(records).extracting(RecordMetadata::topic).areExactly(msgCount, match(REACTIVE_INT_KEY_TOPIC::equals));
					Assertions.assertThat(records).extracting(RecordMetadata::partition).areExactly(msgCount, match(actualPartition -> DEFAULT_PARTITION == actualPartition));
					List<Long> senderRecordsTimestamps = senderRecords.stream().map(SenderRecord::timestamp).collect(Collectors.toList());
					Assertions.assertThat(records).extracting(RecordMetadata::timestamp).containsExactlyElementsOf(senderRecordsTimestamps);
					List<Integer> senderRecordsCorrelationMetadata = senderRecords.stream().map(SenderRecord::correlationMetadata).collect(Collectors.toList());
					Assertions.assertThat(senderRecords).extracting(SenderRecord::correlationMetadata).containsExactlyElementsOf(senderRecordsCorrelationMetadata);
				})
				.then(() -> {
					StepVerifier.create(reactiveKafkaConsumerTemplate.receive())
							.recordWith(ArrayList::new)
							.expectNextCount(msgCount)
							.consumeSubscriptionWith(Subscription::cancel)
							.consumeRecordedWith(receiverRecords -> {
								Assertions.assertThat(receiverRecords).hasSize(msgCount);

								Assertions.assertThat(receiverRecords).extracting(ReceiverRecord::partition).areExactly(msgCount, match(actualPartition -> DEFAULT_PARTITION == actualPartition));
								Assertions.assertThat(receiverRecords).extracting(ReceiverRecord::key).areExactly(msgCount, match(DEFAULT_KEY::equals));
								List<Long> senderRecordsTimestamps = senderRecords.stream().map(SenderRecord::timestamp).collect(Collectors.toList());
								Assertions.assertThat(receiverRecords).extracting(ReceiverRecord::timestamp).containsExactlyElementsOf(senderRecordsTimestamps);
								List<String> senderRecordsValues = senderRecords.stream().map(SenderRecord::value).collect(Collectors.toList());
								Assertions.assertThat(receiverRecords).extracting(ReceiverRecord::value).containsExactlyElementsOf(senderRecordsValues);
							})
							.thenCancel()
							.verify(Duration.ofSeconds(10));
				})
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	@Test//todo
	@Ignore
	public void shouldFlushRecordsOnDemand() {
		Mono<Void> flushMono = reactiveKafkaProducerTemplate.flush();
	}

	@Test
	public void shouldReturnPartitionsForTopic() {
		Mono<List<PartitionInfo>> topicPartitionsMono = reactiveKafkaProducerTemplate.partitionsFromProducerFor(REACTIVE_INT_KEY_TOPIC);

		StepVerifier.create(topicPartitionsMono)
				.expectNextMatches(partitionInfo -> {
					Assertions.assertThat(partitionInfo).isNotNull().hasSize(DEFAULT_PARTITIONS_COUNT);
					return true;
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@Test
	public void shouldReturnMetrics() {
		Mono<? extends Map<MetricName, ? extends Metric>> metricsMono = reactiveKafkaProducerTemplate.metricsFromProducer();

		StepVerifier.create(metricsMono)
				.expectNextMatches(metrics -> {
					Assertions.assertThat(metrics).isNotNull().isNotEmpty();
					return true;
				})
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	@SafeVarargs
	private final List<Header> convertToKafkaHeaders(Map.Entry<String, Object>... headerEntries) {
		Map<String, Object> headers = Stream.of(headerEntries).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		return convertToKafkaHeaders(headers);
	}

	private List<Header> convertToKafkaHeaders(Map<String, Object> headers) {
		KafkaHeaderMapper headerMapper = new DefaultKafkaHeaderMapper();
		RecordHeaders result = new RecordHeaders();
		headerMapper.fromHeaders(new MessageHeaders(headers), result);
		return Stream.of(result.toArray()).collect(Collectors.toList());
	}
}