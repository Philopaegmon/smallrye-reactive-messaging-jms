package io.github.philopaegmon.reactive.messaging.jms;

import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsExceptions.ex;
import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsLogging.log;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import jakarta.jms.Destination;
import jakarta.jms.MessageProducer;
import io.github.philopaegmon.reactive.messaging.jms.impl.MessageCreator;
import io.github.philopaegmon.reactive.messaging.jms.impl.MessageEnhancer;
import io.github.philopaegmon.reactive.messaging.jms.impl.MessageProducerEnhancer;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.ProducerCallback;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.json.JsonMapping;
import io.smallrye.reactive.messaging.providers.helpers.MultiUtils;

class JmsSink {
    private final Flow.Subscriber<Message<?>> sink;
    private final JmsTemplate jmsTemplate;
    private final JsonMapping jsonMapping;
    private final Executor executor;

    JmsSink(JmsTemplate jmsTemplate, JmsConnectorOutgoingConfiguration config, JsonMapping jsonMapping, Executor executor) {
        this.jmsTemplate = withDestination(jmsTemplate, config);
        this.jsonMapping = jsonMapping;
        this.executor = executor;

        sink = MultiUtils.via(m -> m.onItem().transformToUniAndConcatenate(m1 -> send(m1, config))
                .onFailure().invoke(log::unableToSend));

    }

    private Optional<Destination> extractDestination(Message<?> message) {
        return message.getMetadata(OutgoingJmsMessageMetadata.class)
            .map(OutgoingJmsMessageMetadata::getDestination);
    }

    private ProducerCallback<Void> createProducerCallback(Message<?> message, JmsConnectorOutgoingConfiguration config) {
        return (session, producer) -> {
            MessageProducer enhancedProducer = MessageProducerEnhancer.config(config).enhance(producer);
            Object payload = message.getPayload();

            // If the payload is a JMS Message, send it as it is, ignoring metadata.
            if (payload instanceof jakarta.jms.Message) {
                jakarta.jms.Message outgoing = MessageEnhancer.config(config, Optional.empty()).enhance(session, (jakarta.jms.Message) payload);
                enhancedProducer.send(outgoing);
                return null;
            }
            jakarta.jms.Message outgoing = MessageEnhancer.config(config, message.getMetadata(OutgoingJmsMessageMetadata.class))
                .enhance(session, MessageCreator.createMessage(session, payload, jsonMapping));
            enhancedProducer.send(outgoing);
            return null;
        };
    } 

    private Uni<? extends Message<?>> send(Message<?> message, JmsConnectorOutgoingConfiguration config) {
        return Uni.createFrom()
            .item(message)
            .call(() -> 
                Uni.createFrom()
                    .emitter(emitter -> {
                        try {
                            Object payload = message.getPayload();

                            // If the payload is a JMS Message, send it as it is, ignoring metadata.
                            if (payload instanceof jakarta.jms.Message) {
                                jmsTemplate.send(session -> (jakarta.jms.Message) payload);
                                emitter.complete(null);
                            } else {
                                ProducerCallback<Void> action = createProducerCallback(message, config);

                                extractDestination(message)
                                    .ifPresentOrElse(
                                        actualDestination -> {
                                            jmsTemplate.execute(actualDestination, action);
                                            emitter.complete(null);
                                        }, 
                                        () -> {
                                            jmsTemplate.execute(action);
                                            emitter.complete(null);
                                        }
                                    );
                            }
                        } catch (JmsException e) {
                            emitter.fail(e.getCause());
                        }
                    })
            )
            .call(() -> Uni.createFrom().completionStage(message::ack))
            .runSubscriptionOn(executor);
    }

    private JmsTemplate withDestination(JmsTemplate jmsTemplate, JmsConnectorOutgoingConfiguration config) {
        String name = config.getDestination().orElseGet(config::getChannel);
        String type = config.getDestinationType();
        switch (type.toLowerCase()) {
            case "queue":
                log.creatingQueue(name);
                jmsTemplate.setDefaultDestinationName(name);
                return jmsTemplate;
            case "topic":
                log.creatingTopic(name);
                jmsTemplate.setDefaultDestinationName(name);
                jmsTemplate.setPubSubDomain(true);
                return jmsTemplate;
            default:
                throw ex.illegalArgumentUnknownDestinationType(type);
        }
    }

    Flow.Subscriber<Message<?>> getSink() {
        return sink;
    }

}
