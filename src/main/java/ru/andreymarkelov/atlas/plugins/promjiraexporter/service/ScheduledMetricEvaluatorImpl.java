package ru.andreymarkelov.atlas.plugins.promjiraexporter.service;

import com.atlassian.applinks.api.ApplicationLink;
import com.atlassian.applinks.api.ApplicationLinkService;
import com.atlassian.applinks.spi.manifest.ApplicationStatus;
import com.atlassian.applinks.spi.manifest.ManifestRetriever;
import com.atlassian.jira.ofbiz.DefaultOfBizConnectionFactory;
import io.prometheus.client.Collector;
import io.prometheus.client.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Thread.MIN_PRIORITY;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class ScheduledMetricEvaluatorImpl implements ScheduledMetricEvaluator, DisposableBean, InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(ScheduledMetricEvaluator.class);

    private final ScrapingSettingsManager scrapingSettingsManager;
    private final ApplicationLinkService applicationLinkService;
    private final ManifestRetriever manifestRetriever;

    private final AtomicLong totalAttachmentSize;
    private final AtomicLong lastExecutionTimestamp;

    /**
     * Scheduled executor to grab metrics.
     */
    private final ThreadFactory threadFactory;
    private ScheduledExecutorService executorService;

    private ScheduledFuture<?> scraper;
    private final Lock lock;

    //--> Metrics

    private final Gauge applicationLinkStatusGauge = Gauge.build()
            .name("jira_application_link_status_gauge")
            .help("Application Link Status Gauge")
            .labelNames("name")
            .create();

    private final Gauge applicationLinkCountGauge = Gauge.build()
            .name("jira_application_link_count_gauge")
            .help("Application Link Count Gauge")
            .create();

    //<-- Metrics

    public ScheduledMetricEvaluatorImpl(
            ScrapingSettingsManager scrapingSettingsManager,
            ApplicationLinkService applicationLinkService,
            ManifestRetriever manifestRetriever) {
        this.scrapingSettingsManager = scrapingSettingsManager;
        this.applicationLinkService = applicationLinkService;
        this.manifestRetriever = manifestRetriever;
        this.totalAttachmentSize = new AtomicLong(0);
        this.lastExecutionTimestamp = new AtomicLong(-1);

        this.threadFactory = defaultThreadFactory();
        this.executorService = newSingleThreadScheduledExecutor(r -> {
            Thread thread = threadFactory.newThread(r);
            thread.setPriority(MIN_PRIORITY);
            return thread;
        });
        this.lock = new ReentrantLock();
    }

    @Override
    public void afterPropertiesSet() {
        lock.lock();
        try {
            startScraping(getDelay());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void destroy() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }

    @Override
    public void restartScraping(int newDelay) {
        lock.lock();
        try {
            stopScraping();
            startScraping(newDelay);
        } finally {
            lock.unlock();
        }
    }

    private void startScraping(int delay) {
        if (delay <= 0) {
            return;
        }

        scraper = executorService.scheduleWithFixedDelay(() -> {
            calculateAttachmentSize();
            calculateLinkStatuses();
            lastExecutionTimestamp.set(System.currentTimeMillis());
        }, 0, delay, TimeUnit.MINUTES);
    }

    private void calculateAttachmentSize() {
        try (Connection connection = new DefaultOfBizConnectionFactory().getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select sum(filesize) from fileattachment")) {
                if (rs.next()) {
                    totalAttachmentSize.set(rs.getLong(1));
                }
            }
        } catch (Exception ex) {
            log.error("Failed to resolve attachments size.", ex);
        }
    }

    /**
     * Calculate application links statuses.
     */
    private void calculateLinkStatuses() {
        int count = 0;
        for (ApplicationLink al : applicationLinkService.getApplicationLinks()) {
            count++;
            ApplicationStatus applicationStatus = manifestRetriever.getApplicationStatus(al.getRpcUrl(), al.getType());
            applicationLinkStatusGauge.labels(al.getName()).set(applicationStatus.ordinal());
        }
        applicationLinkCountGauge.set(count);
    }

    private void stopScraping() {
        boolean success = scraper.cancel(true);
        if (!success) {
            log.debug("Unable to cancel scraping, typically because it has already completed.");
        }
    }

    @Override
    public long getTotalAttachmentSize() {
        return totalAttachmentSize.get();
    }

    @Override
    public long getLastExecutionTimestamp() {
        return lastExecutionTimestamp.get();
    }

    @Override
    public int getDelay() {
        return scrapingSettingsManager.getDelay();
    }

    @Override
    public void setDelay(int delay) {
        scrapingSettingsManager.setDelay(delay);
    }

    @Override
    public List<Collector.MetricFamilySamples> collect() {
        List<Collector.MetricFamilySamples> result = new ArrayList<>();
        result.addAll(applicationLinkStatusGauge.collect());
        result.addAll(applicationLinkCountGauge.collect());
        return result;
    }
}
