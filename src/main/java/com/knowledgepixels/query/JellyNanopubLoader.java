package com.knowledgepixels.query;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.NanopubStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads nanopubs from the attached Nanopub Registry via a restartable Jelly stream.
 */
public class JellyNanopubLoader {
    private static final String registryUrl;
    private static long lastCommittedCounter = -1;
    private static Long lastKnownSetupId = null;
    private static final CloseableHttpClient metadataClient;
    private static final CloseableHttpClient jellyStreamClient;

    private static final int MAX_RETRIES_METADATA = 10;
    private static final int RETRY_DELAY_METADATA = 3000;
    private static final int RETRY_DELAY_JELLY = 5000;

    private static final Logger log = LoggerFactory.getLogger(JellyNanopubLoader.class);

    /**
     * Registry metadata returned by a HEAD request.
     */
    record RegistryMetadata(long loadCounter, Long setupId) {}

    /**
     * The interval in milliseconds at which the updates loader should poll for new nanopubs.
     */
    public static final int UPDATES_POLL_INTERVAL = 2000;

    enum LoadingType {
        INITIAL,
        UPDATE,
    }

    static {
        // Initialize registryUrl
        var url = Utils.getEnvString(
                "REGISTRY_FIXED_URL", "https://registry.knowledgepixels.com/"
        );
        if (!url.endsWith("/")) url += "/";
        registryUrl = url;

        metadataClient = HttpClientBuilder.create().setDefaultRequestConfig(Utils.getHttpRequestConfig()).build();
        jellyStreamClient = NanopubUtils.getHttpClient();
    }

    /**
     * Start or continue (after restart) the initial loading procedure. This simply loads all
     * nanopubs from the attached Registry.
     *
     * @param afterCounter which counter to start from (-1 for the beginning)
     */
    public static void loadInitial(long afterCounter) {
        RegistryMetadata metadata = fetchRegistryMetadata();
        long targetCounter = metadata.loadCounter();
        log.info("Fetched Registry load counter: {}", targetCounter);
        // Store setupId on initial load
        if (metadata.setupId() != null && lastKnownSetupId == null) {
            lastKnownSetupId = metadata.setupId();
            StatusController.get().setRegistrySetupId(metadata.setupId());
        }
        lastCommittedCounter = afterCounter;
        while (lastCommittedCounter < targetCounter) {
            try {
                loadBatch(lastCommittedCounter, LoadingType.INITIAL);
                log.info("Initial load: loaded batch up to counter {}", lastCommittedCounter);
            } catch (Exception e) {
                log.info("Failed to load batch starting from counter {}", lastCommittedCounter);
                log.info("Failure reason: ", e);
                try {
                    Thread.sleep(RETRY_DELAY_JELLY);
                } catch (InterruptedException e2) {
                    throw new RuntimeException("Interrupted while waiting to retry loading batch.");
                }
            }
        }
        log.info("Initial load complete.");
    }

    /**
     * Check if the Registry has any new nanopubs. If it does, load them.
     * This method should be called periodically, and you should wait for it to finish before
     * calling it again.
     */
    public static void loadUpdates() {
        try {
            final var status = StatusController.get().getState();
            lastCommittedCounter = status.loadCounter;
            RegistryMetadata metadata = fetchRegistryMetadata();
            long targetCounter = metadata.loadCounter();
            Long currentSetupId = metadata.setupId();

            // Detect reset via setupId change
            if (lastKnownSetupId != null && currentSetupId != null
                    && !lastKnownSetupId.equals(currentSetupId)) {
                log.warn("Registry reset detected: setupId {} -> {}", lastKnownSetupId, currentSetupId);
                performResync(currentSetupId);
                return;
            }
            // Detect reset via counter decrease (also covers first run after upgrade
            // where no setupId was persisted yet but the registry has already been reset)
            if (lastCommittedCounter > 0 && targetCounter >= 0
                    && targetCounter < lastCommittedCounter) {
                log.warn("Registry counter decreased {} -> {}, triggering resync",
                        lastCommittedCounter, targetCounter);
                performResync(currentSetupId);
                return;
            }

            // Update lastKnownSetupId on first successful poll
            if (currentSetupId != null && lastKnownSetupId == null) {
                lastKnownSetupId = currentSetupId;
                StatusController.get().setRegistrySetupId(currentSetupId);
            }

            StatusController.get().setLoadingUpdates(status.loadCounter);
            if (lastCommittedCounter >= targetCounter) {
                StatusController.get().setReady();
                return;
            }
            loadBatch(lastCommittedCounter, LoadingType.UPDATE);
            log.info("Loaded {} update(s). Counter: {}, target was: {}",
                    lastCommittedCounter - status.loadCounter, lastCommittedCounter, targetCounter);
            if (lastCommittedCounter < targetCounter) {
                log.info("Warning: expected to load nanopubs up to (inclusive) counter " +
                        targetCounter + " based on the counter reported in Registry's headers, " +
                        "but loaded only up to {}.", lastCommittedCounter);
            }
        } catch (Exception e) {
            log.info("Failed to load updates. Current counter: {}", lastCommittedCounter);
            log.info("Failure Reason: ", e);
        } finally {
            try {
                StatusController.get().setReady();
            } catch (Exception e) {
                log.info("Update loader: failed to set status to READY.");
                log.info("Failure Reason: ", e);
            }
        }
    }

    /**
     * Re-stream all nanopubs from the registry after a reset is detected.
     * Existing nanopubs are skipped by NanopubLoader's per-repo dedup.
     *
     * @param newSetupId the new setup ID from the registry, or null if unknown
     */
    private static void performResync(Long newSetupId) {
        log.warn("Starting resync with registry. New setupId: {}", newSetupId);
        StatusController.get().setResetting();
        lastKnownSetupId = newSetupId;
        if (newSetupId != null) {
            StatusController.get().setRegistrySetupId(newSetupId);
        }
        StatusController.get().setLoadingInitial(-1);
        loadInitial(-1);
        StatusController.get().setReady();
        log.warn("Resync complete. Counter: {}", lastCommittedCounter);
    }

    /**
     * Load a batch of nanopubs from the Jelly stream.
     * <p>
     * The method requests the list of all nanopubs from the Registry and reads it for as long
     * as it can. If the stream is interrupted, the method will throw an exception, and you
     * can resume loading from the last known counter.
     *
     * @param afterCounter the last known nanopub counter to have been committed in the DB
     * @param type         the type of loading operation (initial or update)
     */
    static void loadBatch(long afterCounter, LoadingType type) {
        CloseableHttpResponse response;
        try {
            var request = new HttpGet(makeStreamFetchUrl(afterCounter));
            response = jellyStreamClient.execute(request);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Jelly stream from the Registry (I/O error).", e);
        }

        int httpStatus = response.getStatusLine().getStatusCode();
        if (httpStatus < 200 || httpStatus >= 300) {
            EntityUtils.consumeQuietly(response.getEntity());
            throw new RuntimeException("Jelly stream HTTP status is not 2xx: " + httpStatus + ".");
        }

        try (
                var is = response.getEntity().getContent();
                var npStream = NanopubStream.fromByteStream(is).getAsNanopubs()
        ) {
            AtomicLong checkpointTime = new AtomicLong(System.currentTimeMillis());
            AtomicLong checkpointCounter = new AtomicLong(lastCommittedCounter);
            AtomicLong lastSavedCounter = new AtomicLong(lastCommittedCounter);
            AtomicLong loaded = new AtomicLong(0L);

            npStream.forEach(m -> {
                if (!m.isSuccess()) throw new RuntimeException("Failed to load " +
                        "nanopub from Jelly stream. Last known counter: " + lastCommittedCounter,
                        m.getException()
                );
                if (m.getCounter() < lastCommittedCounter) {
                    throw new RuntimeException("Received a nanopub with a counter lower than " +
                            "the last known counter. Last known counter: " + lastCommittedCounter +
                            ", received counter: " + m.getCounter());
                }
                NanopubLoader.load(m.getNanopub(), m.getCounter());
                if (m.getCounter() % 10 == 0) {
                    // Save the committed counter only every 10 nanopubs to reduce DB load
                    saveCommittedCounter(type);
                    lastSavedCounter.set(m.getCounter());
                }
                lastCommittedCounter = m.getCounter();
                loaded.getAndIncrement();

                if (loaded.get() % 50 == 0) {
                    long currTime = System.currentTimeMillis();
                    double speed = 50 / ((currTime - checkpointTime.get()) / 1000.0);
                    log.info("Loading speed: " + String.format("%.2f", speed) +
                            " np/s. Counter: " + lastCommittedCounter);
                    checkpointTime.set(currTime);
                    checkpointCounter.set(lastCommittedCounter);
                }
            });
            // Make sure to save the last committed counter at the end of the batch
            if (lastCommittedCounter >= lastSavedCounter.get()) {
                saveCommittedCounter(type);
            }
        } catch (IOException e) {
            throw new RuntimeException("I/O error while reading the response Jelly stream.", e);
        } finally {
            try {
                response.close();
            } catch (IOException e) {
                log.info("Failed to close the Jelly stream response.");
            }
        }
    }

    /**
     * Save the last committed counter to the DB. Do this every N nanopubs to reduce DB load.
     * Remember to call this method at the end of the batch as well.
     *
     * @param type the type of loading operation (initial or update)
     */
    private static void saveCommittedCounter(LoadingType type) {
        try {
            if (type == LoadingType.INITIAL) {
                StatusController.get().setLoadingInitial(lastCommittedCounter);
            } else {
                StatusController.get().setLoadingUpdates(lastCommittedCounter);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not update the nanopub counter in DB", e);
        }
    }

    /**
     * Set the last known setup ID. Called from MainVerticle on startup to restore persisted state.
     *
     * @param setupId the setup ID to set, or null if not known
     */
    static void setLastKnownSetupId(Long setupId) {
        lastKnownSetupId = setupId;
    }

    /**
     * Run a HEAD request to the Registry to fetch its current metadata (load counter and setup ID).
     *
     * @return the registry metadata
     */
    static RegistryMetadata fetchRegistryMetadata() {
        int tries = 0;
        RegistryMetadata metadata = null;
        while (metadata == null && tries < MAX_RETRIES_METADATA) {
            try {
                metadata = fetchRegistryMetadataInner();
            } catch (Exception e) {
                tries++;
                log.info("Failed to fetch registry metadata, try " + tries +
                        ". Retrying in {}ms...", RETRY_DELAY_METADATA);
                log.info("Failure Reason: ", e);
                try {
                    Thread.sleep(RETRY_DELAY_METADATA);
                } catch (InterruptedException e2) {
                    throw new RuntimeException(
                            "Interrupted while waiting to retry fetching registry metadata.");
                }
            }
        }
        if (metadata == null) {
            throw new RuntimeException("Failed to fetch registry metadata after " +
                    MAX_RETRIES_METADATA + " retries.");
        }
        return metadata;
    }

    /**
     * Inner logic for fetching the registry metadata via HEAD request.
     *
     * @return the registry metadata (load counter and setup ID)
     * @throws IOException if the HTTP request fails
     */
    private static RegistryMetadata fetchRegistryMetadataInner() throws IOException {
        var request = new HttpHead(registryUrl);
        try (var response = metadataClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            EntityUtils.consumeQuietly(response.getEntity());
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Registry metadata HTTP status is not 2xx: " +
                        status + ".");
            }

            // Check if the registry is ready
            var hStatus = response.getHeaders("Nanopub-Registry-Status");
            if (hStatus.length == 0) {
                throw new RuntimeException("Registry did not return a Nanopub-Registry-Status header.");
            }
            if (!"ready".equals(hStatus[0].getValue()) && !"updating".equals(hStatus[0].getValue())) {
                throw new RuntimeException("Registry is not in ready state.");
            }

            // Get the load counter
            var hCounter = response.getHeaders("Nanopub-Registry-Load-Counter");
            if (hCounter.length == 0) {
                throw new RuntimeException("Registry did not return a Nanopub-Registry-Load-Counter header.");
            }
            long loadCounter = Long.parseLong(hCounter[0].getValue());

            // Get the setup ID (optional — older registries may not have it)
            Long setupId = null;
            var hSetupId = response.getHeaders("Nanopub-Registry-Setup-Id");
            if (hSetupId.length > 0) {
                try {
                    setupId = Long.parseLong(hSetupId[0].getValue());
                } catch (NumberFormatException e) {
                    log.info("Could not parse Nanopub-Registry-Setup-Id header: {}", hSetupId[0].getValue());
                }
            }

            return new RegistryMetadata(loadCounter, setupId);
        }
    }

    /**
     * Construct the URL for fetching the Jelly stream.
     *
     * @param afterCounter the last known nanopub counter to have been committed in the DB
     * @return the URL for fetching the Jelly stream
     */
    private static String makeStreamFetchUrl(long afterCounter) {
        return registryUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
    }
}
