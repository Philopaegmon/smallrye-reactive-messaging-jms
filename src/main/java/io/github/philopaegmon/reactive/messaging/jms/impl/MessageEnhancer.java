package io.github.philopaegmon.reactive.messaging.jms.impl;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import io.github.philopaegmon.reactive.messaging.jms.JmsConnectorOutgoingConfiguration;
import io.github.philopaegmon.reactive.messaging.jms.JmsProperties;
import io.github.philopaegmon.reactive.messaging.jms.JmsPropertiesBuilder;
import io.github.philopaegmon.reactive.messaging.jms.OutgoingJmsMessageMetadata;

import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsExceptions.ex;

import java.util.Optional;

public final class MessageEnhancer {

    private MessageEnhancer() {
    }

    public static Enhancer config(JmsConnectorOutgoingConfiguration config,
            Optional<OutgoingJmsMessageMetadata> metadata) {
        return new Enhancer(config, metadata);
    }

    public static class Enhancer {
        private final JmsConnectorOutgoingConfiguration config;
        private final Optional<OutgoingJmsMessageMetadata> metadata;

        public Enhancer(JmsConnectorOutgoingConfiguration config, Optional<OutgoingJmsMessageMetadata> metadata) {
            this.config = config;
            this.metadata = metadata;
        }

        public Message enhance(Session session, Message message) {
            setJMSReplyTo(session, message);
            setMetadata(message);
            return message;
        }

        private void setJMSReplyTo(Session session, Message message) {
            config.getReplyTo().ifPresent(rt -> {
                try {
                    String replyToDestinationType = config.getReplyToDestinationType();
                    Destination replyToDestination;
                    if (replyToDestinationType.equalsIgnoreCase("topic")) {
                        replyToDestination = session.createTopic(rt);
                    } else if (replyToDestinationType.equalsIgnoreCase("queue")) {
                        replyToDestination = session.createQueue(rt);
                    } else {
                        throw ex.illegalArgumentInvalidDestinationType(replyToDestinationType);
                    }
                    message.setJMSReplyTo(replyToDestination);
                } catch (JMSException e) {
                    // TODO: handle exception
                }
            });
        }

        private void setMetadata(Message message) {
            metadata.map(OutgoingJmsMessageMetadata::getCorrelationId)
                    .ifPresent(correlationID -> setJMSCorrelationID(message, correlationID));

            metadata.map(OutgoingJmsMessageMetadata::getReplyTo)
                    .ifPresent(replyTo -> setJMSReplyTo(message, replyTo));

            metadata.map(OutgoingJmsMessageMetadata::getDestination)
                    .ifPresent(destination -> setDestination(message, destination));

            metadata.map(OutgoingJmsMessageMetadata::getDeliveryMode)
                    .ifPresent(deliveryMode -> setDeliveryMode(message, deliveryMode));

            metadata.map(OutgoingJmsMessageMetadata::getType)
                    .ifPresent(type -> setJMSType(message, type));

            metadata.map(OutgoingJmsMessageMetadata::getProperties)
                    .ifPresent(properties -> setProperties(message, properties));
        }

        private void setJMSCorrelationID(Message message, String correlationId) {
            try {
                message.setJMSCorrelationID(correlationId);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setJMSReplyTo(Message message, Destination replyTo) {
            try {
                message.setJMSReplyTo(replyTo);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setDestination(Message message, Destination destination) {
            try {
                message.setJMSDestination(destination);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setDeliveryMode(Message message, int deliveryMode) {
            try {
                if (deliveryMode != -1) {
                    message.setJMSDeliveryMode(deliveryMode);
                }
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setJMSType(Message message, String type) {
            try {
                message.setJMSType(type);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setProperties(Message message, JmsProperties properties) {
            if (!(properties instanceof JmsPropertiesBuilder.OutgoingJmsProperties)) {
                throw ex.illegalStateUnableToMapProperties(properties.getClass().getName());
            }
            JmsPropertiesBuilder.OutgoingJmsProperties op = ((JmsPropertiesBuilder.OutgoingJmsProperties) properties);
            op.getProperties().forEach(p -> p.apply(message));
        }
    }
}
