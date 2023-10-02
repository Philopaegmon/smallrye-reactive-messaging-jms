package io.github.philopaegmon.reactive.messaging.jms.impl;

import io.smallrye.reactive.messaging.json.JsonMapping;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

public final class MessageCreator {

    private MessageCreator() {}

    private static boolean isPrimitiveBoxed(Class<?> c) {
        return c.equals(Boolean.class)
            || c.equals(Integer.class)
            || c.equals(Byte.class)
            || c.equals(Double.class)
            || c.equals(Float.class)
            || c.equals(Short.class)
            || c.equals(Character.class)
            || c.equals(Long.class);
    }

    public static Message createMessage(Session session, Object payload, JsonMapping jsonMapping) throws JMSException {
        if (payload instanceof String || payload.getClass().isPrimitive() || isPrimitiveBoxed(payload.getClass())) {
            TextMessage outgoing = session.createTextMessage(payload.toString());
            outgoing.setStringProperty("_classname", payload.getClass().getName());
            outgoing.setJMSType(payload.getClass().getName());
            return outgoing;
        } else if (payload.getClass().isArray() && payload.getClass().getComponentType().equals(Byte.TYPE)) {
            BytesMessage o = session.createBytesMessage();
            o.writeBytes((byte[]) payload);
            return o;
        } else {
            TextMessage outgoing = session.createTextMessage(jsonMapping.toJson(payload));
            outgoing.setJMSType(payload.getClass().getName());
            outgoing.setStringProperty("_classname", payload.getClass().getName());
            return outgoing;
        }
    } 
}
