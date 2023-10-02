package io.github.philopaegmon.reactive.messaging.jms;

import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsExceptions.ex;
import static io.github.philopaegmon.reactive.messaging.jms.i18n.JmsLogging.log;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jms.listener.DefaultMessageListenerContainer;

import jakarta.jms.*;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.Subscriptions;
import io.smallrye.reactive.messaging.json.JsonMapping;

class JmsSource {

    private final Flow.Publisher<IncomingJmsMessage<?>> source;

    private final JmsPublisher publisher;

    JmsSource(DefaultMessageListenerContainer container, JmsConnectorIncomingConfiguration config, JsonMapping jsonMapping, Executor executor) {
        String name = config.getDestination().orElseGet(config::getChannel);
        String selector = config.getSelector().orElse(null);
        boolean nolocal = config.getNoLocal();
        boolean broadcast = config.getBroadcast();
        boolean durable = config.getDurable();

        DefaultMessageListenerContainer containerWithDestination = withDestination(container, name, config);

        containerWithDestination.setMessageSelector(selector);
        containerWithDestination.setPubSubNoLocal(nolocal);

        publisher = new JmsPublisher(containerWithDestination);

        Multi<IncomingJmsMessage<?>> multi = Multi.createFrom().publisher(publisher)
                .map(m -> new IncomingJmsMessage<>(m, executor, jsonMapping));
        if (!broadcast) {
            source = multi;
        } else {
            source = multi.broadcast().toAllSubscribers();
        }
    }

    void close() {
        publisher.close();
    }

    private DefaultMessageListenerContainer withDestination(DefaultMessageListenerContainer container, String name, JmsConnectorIncomingConfiguration config) {
        String type = config.getDestinationType();
        switch (type.toLowerCase()) {
            case "queue":
                log.creatingQueue(name);
                container.setDestinationName(name);
                return container;
            case "topic":
                log.creatingTopic(name);
                container.setDurableSubscriptionName(name);
                container.setPubSubDomain(true);
                return container;
            default:
                throw ex.illegalArgumentUnknownDestinationType(type);
        }

    }

    Flow.Publisher<IncomingJmsMessage<?>> getSource() {
        return source;
    }

    @SuppressWarnings("PublisherImplementation")
    private static class JmsPublisher implements Flow.Publisher<Message>, Flow.Subscription {

        private final AtomicLong requests = new AtomicLong();
        private final AtomicReference<Flow.Subscriber<? super Message>> downstream = new AtomicReference<>();
        private final DefaultMessageListenerContainer container;
        private final ExecutorService executor;
        private boolean unbounded;

        private JmsPublisher(DefaultMessageListenerContainer container) {
            this.container = container;
            this.executor = Executors.newSingleThreadExecutor();
            container.setTaskExecutor(executor);
            container.initialize();
            container.start();
        }

        void close() {
            Flow.Subscriber<? super Message> subscriber = downstream.getAndSet(null);
            if (subscriber != null) {
                subscriber.onComplete();
            }
            container.shutdown();
            executor.shutdown();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Message> s) {
            if (downstream.compareAndSet(null, s)) {
                s.onSubscribe(this);
            } else {
                Subscriptions.fail(s, ex.illegalStateAlreadySubscriber());
            }
        }

        @Override
        public void request(long n) {
            if (n > 0) {
                boolean u = unbounded;
                if (!u) {
                    long v = add(n);
                    if (v == Long.MAX_VALUE) {
                        unbounded = true;
                        startUnboundedReception();
                    } else {
                        enqueue(n);
                    }
                }
            }
        }

        private void enqueue(long n) {
            for (int i = 0; i < n; i++) {
                container.setupMessageListener((MessageListener) message -> {
                    if (message != null) { // null means closed.
                        requests.decrementAndGet();
                        downstream.get().onNext(message);
                    }
                });
            }
        }

        private void startUnboundedReception() {
            container.setupMessageListener((MessageListener) message -> {
                    if (message != null) { // null means closed.
                        requests.decrementAndGet();
                        downstream.get().onNext(message);
                    }
                });
        }

        @Override
        public void cancel() {
            close();
        }

        long add(long req) {
            for (;;) {
                long r = requests.get();
                if (r == Long.MAX_VALUE) {
                    return Long.MAX_VALUE;
                }
                long u = r + req;
                long v;
                if (u < 0L) {
                    v = Long.MAX_VALUE;
                } else {
                    v = u;
                }
                if (requests.compareAndSet(r, v)) {
                    return v;
                }
            }
        }
    }
}
