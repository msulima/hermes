package pl.allegro.tech.hermes.consumers.consumer.rate;

import com.google.common.util.concurrent.RateLimiter;
import pl.allegro.tech.hermes.api.Subscription;
import pl.allegro.tech.hermes.common.metric.HermesMetrics;
import pl.allegro.tech.hermes.consumers.consumer.rate.calculator.OutputRateCalculationResult;
import pl.allegro.tech.hermes.consumers.consumer.rate.calculator.OutputRateCalculator;
import pl.allegro.tech.hermes.consumers.consumer.rate.calculator.OutputRateCalculatorFactory;

import java.time.Clock;
import java.util.Objects;

public class SerialConsumerRateLimiter implements ConsumerRateLimiter {

    private Subscription subscription;

    private final HermesMetrics hermesMetrics;

    private final ConsumerRateLimitSupervisor rateLimitSupervisor;

    private final RateLimiter rateLimiter;

    private final OutputRateCalculator outputRateCalculator;

    private final SendCounters sendCounters;

    private OutputRateCalculator.Mode currentMode;

    public SerialConsumerRateLimiter(Subscription subscription,
                                     OutputRateCalculatorFactory outputRateCalculatorFactory,
                                     HermesMetrics hermesMetrics,
                                     ConsumerRateLimitSupervisor rateLimitSupervisor,
                                     Clock clock) {
        this.subscription = subscription;
        this.hermesMetrics = hermesMetrics;
        this.rateLimitSupervisor = rateLimitSupervisor;
        this.sendCounters = new SendCounters(clock);
        this.outputRateCalculator = outputRateCalculatorFactory.createCalculator(subscription, sendCounters);
        this.currentMode = OutputRateCalculator.Mode.NORMAL;
        this.rateLimiter = RateLimiter.create(calculateInitialRate().rate());
    }

    @Override
    public void initialize() {
        outputRateCalculator.start();
        adjustConsumerRate();
        hermesMetrics.registerOutputRateGauge(
                subscription.getTopicName(), subscription.getName(), rateLimiter::getRate);
        rateLimitSupervisor.register(this);
    }

    @Override
    public void shutdown() {
        hermesMetrics.unregisterOutputRateGauge(subscription.getTopicName(), subscription.getName());
        rateLimitSupervisor.unregister(this);
        outputRateCalculator.shutdown();
    }

    @Override
    public void acquire() {
        rateLimiter.acquire();
        sendCounters.incrementAttempted();
    }

    @Override
    public void adjustConsumerRate() {
        OutputRateCalculationResult result = recalculate();
        rateLimiter.setRate(result.rate());
        currentMode = result.mode();
        sendCounters.reset();
    }

    private OutputRateCalculationResult calculateInitialRate() {
        return outputRateCalculator.recalculateRate(sendCounters, currentMode, 0.0);
    }

    private OutputRateCalculationResult recalculate() {
        return outputRateCalculator.recalculateRate(sendCounters, currentMode, rateLimiter.getRate());
    }

    @Override
    public void updateSubscription(Subscription newSubscription) {
        this.subscription = newSubscription;
    }

    @Override
    public void registerSuccessfulSending() {
        sendCounters.incrementSuccesses();
    }

    @Override
    public void registerFailedSending() {
        sendCounters.incrementFailures();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SerialConsumerRateLimiter that = (SerialConsumerRateLimiter) o;

        return Objects.equals(subscription.getQualifiedName(), that.subscription.getQualifiedName());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(subscription.getQualifiedName());
    }
}
