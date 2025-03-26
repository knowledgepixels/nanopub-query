package com.knowledgepixels.query;

import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.NanopubStream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads nanopubs from the attached Nanopub Registry via a restartable Jelly stream.
 * TODO: implement periodic checks for new nanopubs
 */
public class JellyNanopubLoader {
    private static final String registryUrl;
    // TODO: this should be persisted in the DB, via the ServiceStatus class probably
    private static long lastCommittedCounter = -1;
    private static final HttpClient metadataClient;
    private static final CloseableHttpClient jellyStreamClient;

    private static final int MAX_RETRIES_METADATA = 10;
    private static final int RETRY_DELAY_METADATA = 3000;
    private static final int RETRY_DELAY_JELLY = 5000;
    public static final int UPDATES_POLL_INTERVAL = 2000;

    private enum LoadingType {
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

        // Initialize HTTP clients
        var rqConfig = RequestConfig.custom()
                .setConnectTimeout(1000)
                .setConnectionRequestTimeout(1000)
                .setSocketTimeout(1000)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build();
        metadataClient = HttpClientBuilder.create().setDefaultRequestConfig(rqConfig).build();
        jellyStreamClient = NanopubUtils.getHttpClient();
    }

    /**
     * Start or continue (after restart) the initial loading procedure. This simply loads all
     * nanopubs from the attached Registry.
     * @param afterCounter which counter to start from (-1 for the beginning)
     */
    public static void loadInitial(long afterCounter) {
        long targetCounter = fetchRegistryLoadCounter();
        System.err.println("Fetched Registry load counter: " + targetCounter);
        lastCommittedCounter = afterCounter;
        while (lastCommittedCounter < targetCounter) {
            try {
                loadBatch(lastCommittedCounter, LoadingType.INITIAL);
                System.err.println("Initial load: loaded batch up to counter " + lastCommittedCounter);
            } catch (Exception e) {
                System.err.println("Failed to load batch starting from counter " + lastCommittedCounter);
                System.err.println(e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_JELLY);
                } catch (InterruptedException e2) {
                    throw new RuntimeException("Interrupted while waiting to retry loading batch.");
                }
            }
        }
        System.err.println("Initial load complete.");
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
            StatusController.get().setLoadingUpdates(status.loadCounter);
            long targetCounter = fetchRegistryLoadCounter();
            if (lastCommittedCounter >= targetCounter) {
                // Keep quiet so as not to spam the log every second
                // System.err.println("No updates to load.");
                StatusController.get().setReady();
                return;
            }
            loadBatch(lastCommittedCounter, LoadingType.UPDATE);
            System.err.println("Loaded " + (targetCounter - status.loadCounter) +
                    " update(s). Counter: " + lastCommittedCounter);
        } catch (Exception e) {
            System.err.println("Failed to load updates. Current counter: " + lastCommittedCounter);
            System.err.println(e.getMessage());
        } finally {
            StatusController.get().setReady();
        }
    }

    /**
     * Load a batch of nanopubs from the Jelly stream.
     * <p>
     * The method requests the list of all nanopubs from the Registry and reads it for as long
     * as it can. If the stream is interrupted, the method will throw an exception, and you
     * can resume loading from the last known counter.
     * @param afterCounter the last known nanopub counter to have been committed in the DB
     * @param type the type of loading operation (initial or update)
     */
    private static void loadBatch(long afterCounter, LoadingType type) {
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
                NanopubLoader.load(m.getNanopub());
                try {
                    if (type == LoadingType.INITIAL) {
                        StatusController.get().setLoadingInitial(m.getCounter());
                    } else {
                        StatusController.get().setLoadingUpdates(m.getCounter());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Could not update the nanopub counter in DB", e);
                }
                lastCommittedCounter = m.getCounter();
                loaded.getAndIncrement();

                if (loaded.get() % 50 == 0) {
                    long currTime = System.currentTimeMillis();
                    double speed = 50 / ((currTime - checkpointTime.get()) / 1000.0);
                    System.err.println("Loading speed: " + String.format("%.2f", speed) +
                            " np/s. Counter: " + lastCommittedCounter);
                    checkpointTime.set(currTime);
                    checkpointCounter.set(lastCommittedCounter);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("I/O error while reading the response Jelly stream.", e);
        } finally {
            try {
                response.close();
            } catch (IOException e) {
                System.err.println("Failed to close the Jelly stream response.");
            }
        }
    }

    /**
     * Run a HEAD request to the Registry to fetch its current load counter.
     * @return the current load counter
     */
    private static long fetchRegistryLoadCounter() {
        int tries = 0;
        long counter = -1;
        while (counter == -1 && tries < MAX_RETRIES_METADATA) {
            try {
                counter = fetchRegistryLoadCounterInner();
            } catch (Exception e) {
                tries++;
                System.err.println("Failed to fetch registry load counter, try " + tries +
                        ". Retrying in " + RETRY_DELAY_METADATA + "ms...");
                System.err.println(e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_METADATA);
                } catch (InterruptedException e2) {
                    throw new RuntimeException(
                            "Interrupted while waiting to retry fetching registry load counter.");
                }
            }
        }
        if (counter == -1) {
            throw new RuntimeException("Failed to fetch registry load counter after " +
                    MAX_RETRIES_METADATA + " retries.");
        }
        return counter;
    }

    /**
     * Inner logic for fetching the registry load counter.
     * @return the current load counter
     * @throws IOException if the HTTP request fails
     */
    private static long fetchRegistryLoadCounterInner() throws IOException {
        var request = new HttpHead(registryUrl);
        var response = metadataClient.execute(request);
        int status = response.getStatusLine().getStatusCode();
        EntityUtils.consumeQuietly(response.getEntity());
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Registry load counter HTTP status is not 2xx: " +
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

        // Get the actual load counter
        var hCounter = response.getHeaders("Nanopub-Registry-Load-Counter");
        if (hCounter.length == 0) {
            throw new RuntimeException("Registry did not return a Nanopub-Registry-Load-Counter header.");
        }
        return Long.parseLong(hCounter[0].getValue());
    }

    /**
     * Construct the URL for fetching the Jelly stream.
     * @param afterCounter the last known nanopub counter to have been committed in the DB
     * @return the URL for fetching the Jelly stream
     */
    private static String makeStreamFetchUrl(long afterCounter) {
        return registryUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
    }
}
