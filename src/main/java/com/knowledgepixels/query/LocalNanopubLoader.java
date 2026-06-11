package com.knowledgepixels.query;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.MultiNanopubRdfHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Local loader left here in case it's needed for testing or when the Jelly loader breaks.
 */
public class LocalNanopubLoader {

    private static final Logger logger = LoggerFactory.getLogger(LocalNanopubLoader.class);

    private LocalNanopubLoader() {
    }  // no instances allowed

    /**
     * File containing URIs of nanopubs to load.
     */
    public final static File loadUrisFile = new File("load/nanopub-uris.txt");

    /**
     * File containing nanopubs in TRIG format to load.
     */
    public final static File loadNanopubsFile = new File("load/nanopubs.trig.gz");

    private static final int DEFAULT_WAIT_SECONDS = 120;

    /**
     * Load nanopubs from local files.
     *
     * @return true if local nanopubs were found and loaded, false otherwise
     */
    public static boolean init() {
        if (!(loadNanopubsFile.exists() || loadUrisFile.exists())) {
            logger.info("No local nanopub files for loading found. Moving on to loading via Jelly...");
            return false;
        }
        logger.info("Waiting {} seconds to make sure the triple store is up...", getWaitSeconds());
        try {
            for (int w = 0; w < getWaitSeconds(); w++) {
                logger.info("Waited {} seconds...", w);
                Thread.sleep(1000);
            }
        } catch (InterruptedException ex) {
            // ignore
        }

        logger.info("Loading the local list of nanopubs...");
        load();
        return true;
    }

    static void load() {
        if (!loadUrisFile.exists()) {
            logger.info("No local nanopub URI file found.");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(loadUrisFile))) {
                String line = reader.readLine();
                while (line != null) {
                    NanopubLoader.load(line);
                    line = reader.readLine();
                }
            } catch (IOException ex) {
                logger.info("Loading nanopubs failed.", ex);
            }
        }
        if (!loadNanopubsFile.exists()) {
            logger.info("No local nanopub file found.");
        } else {
            try {
                MultiNanopubRdfHandler.process(RDFFormat.TRIG, loadNanopubsFile, np -> NanopubLoader.load(np, -1));
            } catch (IOException | MalformedNanopubException ex) {
                logger.info("Loading nanopubs failed.", ex);
            }
        }
    }

    static int getWaitSeconds() {
        return Utils.getEnvInt("INIT_WAIT_SECONDS", DEFAULT_WAIT_SECONDS);
    }

}
