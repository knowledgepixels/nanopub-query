package com.knowledgepixels.query;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.MultiNanopubRdfHandler;
import org.nanopub.MultiNanopubRdfHandler.NanopubHandler;
import org.nanopub.Nanopub;

/**
 * Local loader left here in case it's needed for testing or when the Jelly loader breaks.
 */
public class LocalNanopubLoader {

    private LocalNanopubLoader() {}  // no instances allowed

    public final static File loadUrisFile = new File("load/nanopub-uris.txt");
    public final static File loadNanopubsFile = new File("load/nanopubs.trig.gz");
    public final static File autofetchNanopubsFile = new File("load/nanopubs-autofetch.txt");

    private static int waitSeconds = Utils.getEnvInt("INIT_WAIT_SECONDS", 120);

    /**
     * Load nanopubs from local files.
     * @return true if local nanopubs were found and loaded, false otherwise
     */
    public static boolean init() {
        if (!(loadNanopubsFile.exists() || loadNanopubsFile.exists() || autofetchNanopubsFile.exists())) {
            System.err.println("No local nanopub files for loading found. Moving on to loading " +
                    "via Jelly...");
            return false;
        }
        System.err.println("Waiting " + waitSeconds + " seconds to make sure the triple store is up...");
        try {
            for (int w = 0 ; w < waitSeconds ; w++) {
                System.err.println("Waited " + w + " seconds...");
                Thread.sleep(1000);
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        System.err.println("Loading the local list of nanopubs...");
        load();
        return true;
    }

    private static void load() {
        if (!autofetchNanopubsFile.exists()) {
            System.err.println("No local autofetch nanopub URI file found.");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(autofetchNanopubsFile))) {
                String line = reader.readLine();
                while (line != null) {
                    NanopubLoader.load(line);
                    line = reader.readLine();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (!loadUrisFile.exists()) {
            System.err.println("No local nanopub URI file found.");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(loadUrisFile))) {
                String line = reader.readLine();
                while (line != null) {
                    NanopubLoader.load(line);
                    line = reader.readLine();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (!loadNanopubsFile.exists()) {
            System.err.println("No local nanopub file found.");
        } else {
            try {
                MultiNanopubRdfHandler.process(RDFFormat.TRIG, loadNanopubsFile, new NanopubHandler() {
                    @Override
                    public void handleNanopub(Nanopub np) {
                        NanopubLoader.load(np);
                    }
                });
            } catch (IOException | MalformedNanopubException ex) {
                ex.printStackTrace();
            }
        }
    }
}
