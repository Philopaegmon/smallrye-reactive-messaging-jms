package io.github.philopaegmon.reactive.messaging.jms.impl;

import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import io.github.philopaegmon.reactive.messaging.jms.JmsConnectorOutgoingConfiguration;

import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsExceptions.ex;

public final class MessageProducerEnhancer {
    private MessageProducerEnhancer() {
    }

    public static Enhancer config(JmsConnectorOutgoingConfiguration config) {
        return new Enhancer(config);
    }

    public static class Enhancer {
        private final JmsConnectorOutgoingConfiguration config;

        public Enhancer(JmsConnectorOutgoingConfiguration config) {
            this.config = config;
        }

        public MessageProducer enhance(MessageProducer producer) {
            config.getDeliveryDelay().ifPresent(deliveryDelay -> setDeliveryDelay(producer, deliveryDelay));
            config.getDeliveryMode().ifPresent(v -> setDeliveryMode(producer, v));
            config.getDisableMessageId().ifPresent(disableMessageId -> setDisableMessageID(producer, disableMessageId));
            config.getDisableMessageTimestamp().ifPresent(getDisableMessageTimestamp -> setDisableMessageTimestamp(producer, getDisableMessageTimestamp));
            config.getTtl().ifPresent(ttl -> setTimeToLive(producer, ttl));
            config.getPriority().ifPresent(priority -> setPriority(producer, priority));
            return producer;
        }

        private void setDeliveryDelay(MessageProducer producer, long deliveryDelay) {
            try {
                producer.setDeliveryDelay(deliveryDelay);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setDeliveryMode(MessageProducer producer, String v) {
            try {
                if (v.equalsIgnoreCase("persistent")) {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                } else if (v.equalsIgnoreCase("non_persistent")) {
                    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
                } else {
                    throw ex.illegalArgumentInvalidDeliveryMode(v);
                }
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setDisableMessageID(MessageProducer producer, boolean disableMessageId) {
            try {
                producer.setDisableMessageID(disableMessageId);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setDisableMessageTimestamp(MessageProducer producer, boolean getDisableMessageTimestamp) {
            try {
                producer.setDisableMessageTimestamp(getDisableMessageTimestamp);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setTimeToLive(MessageProducer producer, long ttl) {
            try {
                producer.setTimeToLive(ttl);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }

        private void setPriority(MessageProducer producer, int priority) {
            try {
                producer.setPriority(priority);
            } catch (JMSException e) {
                // TODO: handle exception
            }
        }
    }
}
