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

public class LocalNanopubLoader {

	private LocalNanopubLoader() {}  // no instances allowed

	public final static File loadUrisFile = new File("load/nanopub-uris.txt");
	public final static File loadNanopubsFile = new File("load/nanopubs.trig.gz");

	public static void load() {
		if (!loadUrisFile.exists()) {
			System.err.println("No local nanopub URI file found.");
		} else {
			try {
				BufferedReader reader = new BufferedReader(new FileReader(loadUrisFile));
				String line = reader.readLine();
				while (line != null) {
					NanopubLoader.load(line);
					line = reader.readLine();
				}
				reader.close();
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
