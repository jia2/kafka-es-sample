package com.example;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.kafka.ConsumerMessage;
import akka.kafka.ConsumerMessage.CommittableMessage;
import akka.kafka.ConsumerSettings;
import akka.kafka.Subscriptions;
import akka.kafka.javadsl.Consumer;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.alpakka.elasticsearch.ElasticsearchSinkSettings;
import akka.stream.alpakka.elasticsearch.IncomingMessage;
import akka.stream.alpakka.elasticsearch.IncomingMessageResult;
import akka.stream.alpakka.elasticsearch.javadsl.ElasticsearchFlow;
import akka.stream.javadsl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.elasticsearch.client.RestClient;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public class App {

    // fake domain model class to represent a parsed event
    static class Event {
    }

    // these would come from config, but put here to make the sample self contained
    private static final String KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9091";
    private static final String ELASTIC_SEARCH_SERVER = "127.0.0.1";
    private static final int ELASTIC_SEARCH_PORT = 9201;

    public static void main(String[] args) {
        // Akka bootstrap
        final ActorSystem system = ActorSystem.create("example");
        final Materializer materializer = ActorMaterializer.create(system);

        // kafka consumer setup
        final ConsumerSettings<byte[], byte[]> consumerSettings =
            // option: we could choose to instead do parsing/deserialization already here, with the Kafka infrastructure
            ConsumerSettings.create(system, new ByteArrayDeserializer(), new ByteArrayDeserializer())
                .withBootstrapServers(KAFKA_BOOTSTRAP_SERVERS)
                .withGroupId("my-consumer-group")
                // I think this may be a bit dangerous because TTL of offset, if no write within N hours it will be lost
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        final Source<CommittableMessage<byte[], byte[]>, Consumer.Control> kafkaSource =
            Consumer.committableSource(consumerSettings, Subscriptions.topics("my-kafka-topic"));


        // elastic search client setup
        final RestClient esClient = RestClient.builder(new HttpHost(ELASTIC_SEARCH_SERVER, ELASTIC_SEARCH_PORT)).build();

        final Flow<IncomingMessage<Event, CommittableMessage<byte[], byte[]>>, List<IncomingMessageResult<Event, CommittableMessage<byte[], byte[]>>>, NotUsed> writeToElasticSearch =
            ElasticsearchFlow.createWithPassThrough(
                "index-name",
                "type-name",
                ElasticsearchSinkSettings.Default()
                    // if not disabled, this could re-order messages and break committing to kafka
                    .withRetryPartialFailure(false)
                    // these could also be configured in application.conf
                    .withBufferSize(10)
                    .withMaxRetry(100)
                    .withRetryInterval(5000),
                esClient,
                // uses Jackson for serialization
                new ObjectMapper());

        // construct stream
        final RunnableGraph<Consumer.DrainingControl<Done>> kafkaToEsGraph = kafkaSource
            // make sure we eagerly fetch, up to 30 records at all times - this is an optimization
            // but it would be good to know if it really helps anything
            .buffer(30, OverflowStrategy.backpressure())
            // parse the kafka message into our own data structure - but keep the original kafka message
            // so that we can commit it when done
            .map(kafkaMessage -> {
                // decision: how to deal with parse errors here - fail stream and require manual solution, throw away message?
                Event event = parseKafkaMessage(kafkaMessage.record().value());
                // incoming message is specific to the es-connector
                return IncomingMessage.create(event, kafkaMessage);
            })
            // the elastic search flow does batching and bulk inserts, and retries
            .via(writeToElasticSearch)
            .mapAsync(1, writeResults -> {
                // figure out if any write in the batch failed
                List<IncomingMessageResult<Event, CommittableMessage<byte[], byte[]>>> failures =
                    writeResults.stream()
                        .filter(result -> !result.success())
                        .collect(Collectors.toList());

                if (failures.isEmpty()) {
                    // everything was ok, let's do a batched commit
                    List<ConsumerMessage.CommittableOffset> offsets = writeResults.stream()
                        .map(result -> result.passThrough().committableOffset())
                        .collect(Collectors.toList());

                    ConsumerMessage.CommittableOffsetBatch committableOffsetBatch = ConsumerMessage.createCommittableOffsetBatch(offsets);
                    return committableOffsetBatch.commitJavadsl();
                } else {
                    // decision: how to deal with ES-insert errors here - fail stream?
                    throw new RuntimeException("There was failed inserts: " + failures);
                }
            })
            // this is a technicality, a more natural way of thinking about it would be .foreachAsync(1, elem -> CS())
            // which is worked on in Akka issue #25152, will likely end up in 2.5.15
            .toMat(Sink.ignore(), Keep.both())
            .mapMaterializedValue(Consumer::createDrainingControl);


        Consumer.DrainingControl<Done> drainingControl = kafkaToEsGraph.run(materializer);


        // somewhere else, who wants to kill the system, will then
        /*
        drainingControl.shutdown().whenComplete((done, failure) -> {
          if (failure != null) system.log().error("Error when shutting down stream", failure);
          else system.log().info("Stream shut down gracefully");
        });
        */

        // we can also hook onto the stream shutdown like this to make sure we log if the stream stops or fails
        drainingControl.isShutdown().whenComplete((done, failure) -> {
            if (failure != null) system.log().error("Stream failure", failure);
            else system.log().info("Stream completed");
        });
    }

    public static Event parseKafkaMessage(byte[] bytes) {
        // parsing, this could potentially throw an exception if the message cannot be parsed
        return new Event();
    }

}