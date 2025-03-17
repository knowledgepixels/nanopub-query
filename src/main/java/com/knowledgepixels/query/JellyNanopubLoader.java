package com.knowledgepixels.query;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Loads nanopubs from the attached Nanopub Registry via a restartable Jelly stream.
 * TODO: implement periodic checks for new nanopubs
 */
public class JellyNanopubLoader {
    private static final String registryUrl;
    private static long lastCommittedCounter = -1;
    private static final HttpClient metadataClient;
    private static final HttpClient jellyStreamClient;

    private static final int MAX_RETRIES_METADATA = 5;
    private static final int RETRY_DELAY_METADATA = 1000;

    static {
        // Initialize registryUrl
        var url = Utils.getEnvString(
                "REGISTRY_FIXED_URL", "https://registry.knowledgepixels.com/"
        );
        if (!url.endsWith("/")) url += "/";
        registryUrl = url;

        // Initialize HTTP clients
        // TODO: see if those options make sense
        var rqConfig = RequestConfig.custom()
                .setConnectTimeout(1000)
                .setConnectionRequestTimeout(1000)
                .setSocketTimeout(1000)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build();
        metadataClient = HttpClientBuilder.create().setDefaultRequestConfig(rqConfig).build();
        jellyStreamClient = HttpClientBuilder.create().setDefaultRequestConfig(rqConfig).build();
    }

    public static void initialLoad() {
        // State management: if this method fails, the whole service is in an inconsistent state
        // and should be shut down.
        // TODO: make it possible to recover from this state across restarts -- this requires
        //   persisting the state machine in the DB.
        if (!ServiceStatus.transitionTo(ServiceStatus.State.LOADING_INITIAL)) {
            throw new IllegalStateException("Cannot transition to LOADING_INITIAL, as the " +
                    "current state is " + ServiceStatus.getState());
        }
        long targetCounter = fetchRegistryLoadCounter();

        lastCommittedCounter = -1;
        while (lastCommittedCounter < targetCounter) {
            loadBatch(lastCommittedCounter);
        }
    }

    /**
     *
     * @param fromCounter
     */
    private static void loadBatch(long fromCounter) {
        // TODO

        lastCommittedCounter = 10L; // TODO: fetch from registry
    }

    /**
     * Run a HEAD request to the Registry to fetch its current load counter.
     * @return the current load counter
     */
    private static long fetchRegistryLoadCounter() {
        int tries = 0;
        HttpResponse response = null;
        while (response == null && tries < MAX_RETRIES_METADATA) {
            try {
                var request = new HttpHead(registryUrl);
                response = metadataClient.execute(request);
                if (response.getStatusLine().getStatusCode() != 200) {
                    System.err.println("Registry load counter HTTP status is not 200: " +
                            response.getStatusLine().getStatusCode() + ".");
                    response = null;

                }
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
        if (response == null) {
            // Non-recoverable
            throw new RuntimeException("Failed to fetch registry load counter after " +
                    MAX_RETRIES_METADATA + " retries.");
        }

        var h = response.getHeaders("Nanopub-Registry-Load-Counter");
        if (h.length == 0) {
            throw new RuntimeException("Registry did not return a Nanopub-Registry-Load-Counter header.");
        }
        long counter = Long.parseLong(h[0].getValue());
        System.err.println("Fetched Registry load counter: " + counter);
        return counter;
    }

    private static String makeFetchUrl(long fromCounter) {
        return registryUrl + "nanopubs.jelly?fromCounter=" + fromCounter;
    }
}
