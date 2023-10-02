package io.github.philopaegmon.reactive.messaging.jms;

import static io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction.OUTGOING;
import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsExceptions.ex;
import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsLogging.log;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.annotations.ConnectorAttribute;
import io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction;
import io.smallrye.reactive.messaging.connector.InboundConnector;
import io.smallrye.reactive.messaging.connector.OutboundConnector;
import io.smallrye.reactive.messaging.json.JsonMapping;
import io.smallrye.reactive.messaging.providers.i18n.ProviderLogging;

@ApplicationScoped
@Connector(JmsConnector.CONNECTOR_NAME)

@ConnectorAttribute(name = "connection-factory-name", description = "The name of the JMS connection factory  (`jakarta.jms.ConnectionFactory`) to be used. If not set, it uses any exposed JMS connection factory", direction = Direction.INCOMING_AND_OUTGOING, type = "String")
@ConnectorAttribute(name = "username", description = "The username to connect to to the JMS server", direction = Direction.INCOMING_AND_OUTGOING, type = "String")
@ConnectorAttribute(name = "password", description = "The password to connect to to the JMS server", direction = Direction.INCOMING_AND_OUTGOING, type = "String")
@ConnectorAttribute(name = "session-mode", description = "The session mode. Accepted values are AUTO_ACKNOWLEDGE, SESSION_TRANSACTED, CLIENT_ACKNOWLEDGE, DUPS_OK_ACKNOWLEDGE", direction = Direction.INCOMING_AND_OUTGOING, type = "String", defaultValue = "AUTO_ACKNOWLEDGE")
@ConnectorAttribute(name = "client-id", description = "The client id", direction = Direction.INCOMING_AND_OUTGOING, type = "String")
@ConnectorAttribute(name = "destination", description = "The name of the JMS destination. If not set the name of the channel is used", direction = Direction.INCOMING_AND_OUTGOING, type = "String")
@ConnectorAttribute(name = "selector", description = "The JMS selector", direction = Direction.INCOMING, type = "String")
@ConnectorAttribute(name = "no-local", description = "Enable or disable local delivery", direction = Direction.INCOMING, type = "boolean", defaultValue = "false")
@ConnectorAttribute(name = "broadcast", description = "Whether or not the JMS message should be dispatched to multiple consumers", direction = Direction.INCOMING, type = "boolean", defaultValue = "false")
@ConnectorAttribute(name = "durable", description = "Set to `true` to use a durable subscription", direction = Direction.INCOMING, type = "boolean", defaultValue = "false")
@ConnectorAttribute(name = "destination-type", description = "The type of destination. It can be either `queue` or `topic`", direction = Direction.INCOMING_AND_OUTGOING, type = "string", defaultValue = "queue")

@ConnectorAttribute(name = "disable-message-id", description = "Omit the message id in the outbound JMS message", direction = Direction.OUTGOING, type = "boolean")
@ConnectorAttribute(name = "disable-message-timestamp", description = "Omit the message timestamp in the outbound JMS message", direction = Direction.OUTGOING, type = "boolean")
@ConnectorAttribute(name = "delivery-mode", description = "The delivery mode. Either `persistent` or `non_persistent`", direction = Direction.OUTGOING, type = "string")
@ConnectorAttribute(name = "delivery-delay", description = "The delivery delay", direction = Direction.OUTGOING, type = "long")
@ConnectorAttribute(name = "ttl", description = "The JMS Message time-to-live", direction = Direction.OUTGOING, type = "long")
@ConnectorAttribute(name = "correlation-id", description = "The JMS Message correlation id", direction = Direction.OUTGOING, type = "string")
@ConnectorAttribute(name = "priority", description = "The JMS Message priority", direction = Direction.OUTGOING, type = "int")
@ConnectorAttribute(name = "reply-to", description = "The reply to destination if any", direction = Direction.OUTGOING, type = "string")
@ConnectorAttribute(name = "reply-to-destination-type", description = "The type of destination for the response. It can be either `queue` or `topic`", direction = Direction.OUTGOING, type = "string", defaultValue = "queue")
@ConnectorAttribute(name = "merge", direction = OUTGOING, description = "Whether the connector should allow multiple upstreams", type = "boolean", defaultValue = "false")
public class JmsConnector implements InboundConnector, OutboundConnector {

    /**
     * The name of the connector: {@code smallrye-jms}
     */
    public static final String CONNECTOR_NAME = "smallrye-jms";

    /**
     * The default max-pool-size: {@code 10}
     */
    @SuppressWarnings("WeakerAccess")
    static final String DEFAULT_MAX_POOL_SIZE = "10";

    /**
     * The default thread ideal TTL: {@code 60} seconds
     */
    @SuppressWarnings("WeakerAccess")
    static final String DEFAULT_THREAD_TTL = "60";

    @Inject
    @Any
    Instance<ConnectionFactory> factories;

    @Inject
    Instance<JsonMapping> jsonMapper;

    @Inject
    @ConfigProperty(name = "smallrye.smallrye-jms.threads.max-pool-size", defaultValue = DEFAULT_MAX_POOL_SIZE)
    int maxPoolSize;

    @Inject
    @ConfigProperty(name = "smallrye.smallrye-jms.threads.ttl", defaultValue = DEFAULT_THREAD_TTL)
    int ttl;

    private ExecutorService executor;
    private JsonMapping jsonMapping;
    private final List<JmsSource> sources = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        this.executor = Executors.newFixedThreadPool(maxPoolSize);
        if (jsonMapper.isUnsatisfied()) {
            log.warn("Please add one of the additional mapping modules (-jsonb or -jackson) to be able to (de)serialize JSON messages.");
        } else if (jsonMapper.isAmbiguous()) {
            log.warn("Please select only one of the additional mapping modules (-jsonb or -jackson) to be able to (de)serialize JSON messages.");
            this.jsonMapping = jsonMapper.stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unable to find JSON Mapper"));
        } else {
            this.jsonMapping = jsonMapper.get();
        }

    }

    @PreDestroy
    public void cleanup() {
        sources.forEach(JmsSource::close);
        this.executor.shutdown();
    }

    @Override
    public Flow.Publisher<? extends Message<?>> getPublisher(Config config) {
        JmsConnectorIncomingConfiguration ic = new JmsConnectorIncomingConfiguration(config);
        String factoryName = ic.getConnectionFactoryName().orElse(null);
        ConnectionFactory factory = pickTheFactory(factoryName);
        DefaultMessageListenerContainer container = createDefaultMessageListenerContainer(factory, ic.getSessionMode());
        ic.getClientId().ifPresent(container::setClientId);
        JmsSource source = new JmsSource(container, ic, jsonMapping, executor);
        sources.add(source);
        return source.getSource();
    }

    @Override
    public Flow.Subscriber<? extends Message<?>> getSubscriber(Config config) {
        JmsConnectorOutgoingConfiguration oc = new JmsConnectorOutgoingConfiguration(config);
        String factoryName = oc.getConnectionFactoryName().orElse(null);
        ConnectionFactory factory = pickTheFactory(factoryName);
        JmsTemplate template = createJmsTemplate(factory, oc.getSessionMode());
        return new JmsSink(template, oc, jsonMapping, executor).getSink();
    }

    private ConnectionFactory pickTheFactory(String factoryName) {
        if (factories.isUnsatisfied()) {
            if (factoryName == null) {
                throw ex.illegalStateCannotFindFactory();
            } else {
                throw ex.illegalStateCannotFindNamedFactory(factoryName);
            }
        }

        Iterator<ConnectionFactory> iterator;
        if (factoryName == null) {
            iterator = factories.iterator();
        } else {
            Instance<ConnectionFactory> matching = factories.select(Identifier.Literal.of(factoryName));
            if (matching.isUnsatisfied()) {
                // this `if` block should be removed when support for the `@Named` annotation is
                // removed
                matching = factories.select(NamedLiteral.of(factoryName));
                if (!matching.isUnsatisfied()) {
                    ProviderLogging.log.deprecatedNamed();
                }
            }
            iterator = matching.iterator();
        }

        if (!iterator.hasNext()) {
            if (factoryName == null) {
                throw ex.illegalStateCannotFindFactory();
            } else {
                throw ex.illegalStateCannotFindNamedFactory(factoryName);
            }
        }

        return iterator.next();
    }

    private int pickSessionMode(String mode) {
        return switch (mode.toUpperCase()) {
            case "AUTO_ACKNOWLEDGE" -> Session.AUTO_ACKNOWLEDGE;
            case "SESSION_TRANSACTED" -> Session.SESSION_TRANSACTED;
            case "CLIENT_ACKNOWLEDGE" -> Session.CLIENT_ACKNOWLEDGE;
            case "DUPS_OK_ACKNOWLEDGE" -> Session.DUPS_OK_ACKNOWLEDGE;
            default -> throw ex.illegalStateUnknowSessionMode(mode);
        };
    }

    private JmsTemplate createJmsTemplate(ConnectionFactory factory, String mode) {
        JmsTemplate template = new JmsTemplate(factory);
        
        template.setSessionTransacted(false);
        template.setSessionAcknowledgeMode(pickSessionMode(mode));

        return template;
    }

    private DefaultMessageListenerContainer createDefaultMessageListenerContainer(ConnectionFactory factory, String mode) {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();

        container.setConnectionFactory(factory);
        container.setSessionTransacted(false);
        container.setAutoStartup(false);
        container.setAcceptMessagesWhileStopping(false);
        container.setSessionAcknowledgeMode(pickSessionMode(mode));

        return container;
    }
}
