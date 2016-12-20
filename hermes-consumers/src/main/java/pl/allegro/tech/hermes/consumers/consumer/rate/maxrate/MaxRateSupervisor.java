package pl.allegro.tech.hermes.consumers.consumer.rate.maxrate;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.curator.framework.CuratorFramework;
import pl.allegro.tech.hermes.common.config.ConfigFactory;
import pl.allegro.tech.hermes.common.config.Configs;
import pl.allegro.tech.hermes.common.metric.HermesMetrics;
import pl.allegro.tech.hermes.consumers.subscription.cache.SubscriptionsCache;
import pl.allegro.tech.hermes.infrastructure.zookeeper.ZookeeperPaths;

import javax.inject.Inject;
import java.time.Clock;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MaxRateSupervisor implements Runnable {

    private final Set<NegotiatedMaxRateProvider> providers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConfigFactory configFactory;
    private final CuratorFramework curator;
    private final SubscriptionConsumersCache subscriptionConsumersCache;
    private final MaxRateRegistry maxRateRegistry;
    private final SubscriptionsCache subscriptionsCache;
    private final ZookeeperPaths zookeeperPaths;
    private final HermesMetrics metrics;
    private final Clock clock;

    @Inject
    public MaxRateSupervisor(ConfigFactory configFactory,
                             CuratorFramework curator,
                             SubscriptionConsumersCache subscriptionConsumersCache,
                             MaxRateRegistry maxRateRegistry,
                             SubscriptionsCache subscriptionsCache,
                             ZookeeperPaths zookeeperPaths,
                             HermesMetrics metrics,
                             Clock clock) {
        this.configFactory = configFactory;
        this.curator = curator;
        this.subscriptionConsumersCache = subscriptionConsumersCache;
        this.maxRateRegistry = maxRateRegistry;
        this.subscriptionsCache = subscriptionsCache;
        this.zookeeperPaths = zookeeperPaths;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void start() throws Exception {
        startCalculator();
        startSelfUpdate();
    }

    private void startCalculator() {
        MaxRateBalancer balancer = new MaxRateBalancer(
                configFactory.getDoubleProperty(Configs.CONSUMER_MAXRATE_BUSY_TOLERANCE),
                configFactory.getDoubleProperty(Configs.CONSUMER_MAXRATE_MIN_MAX_RATE),
                configFactory.getDoubleProperty(Configs.CONSUMER_MAXRATE_MIN_ALLOWED_CHANGE_PERCENT));

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat("max-rate-calculator-%d").build());

        new MaxRateCalculatorJob(
                curator,
                configFactory,
                executor,
                subscriptionConsumersCache,
                balancer,
                maxRateRegistry,
                zookeeperPaths.maxRateLeaderPath(),
                subscriptionsCache,
                metrics,
                clock
        ).start();
    }

    private void startSelfUpdate() {
        int selfUpdateInterval = configFactory.getIntProperty(Configs.CONSUMER_MAXRATE_UPDATE_INTERVAL_SECONDS);
        Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat("max-rate-provider-%d").build()
        ).scheduleAtFixedRate(this, selfUpdateInterval, selfUpdateInterval, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        providers.forEach(NegotiatedMaxRateProvider::tickForHistory);
    }

    public void register(NegotiatedMaxRateProvider maxRateProvider) {
        providers.add(maxRateProvider);
    }

    public void unregister(NegotiatedMaxRateProvider maxRateProvider) {
        providers.remove(maxRateProvider);
    }
}
