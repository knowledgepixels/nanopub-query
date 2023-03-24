package com.knowledgepixels.query;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.eclipse.rdf4j.repository.RepositoryConnection;

public class LocalListLoader {

	private LocalListLoader() {}  // no instances allowed

	public final static File loadFile = new File("load/nanopub-uris.txt");

	public static void load() {
		if (!loadFile.exists()) {
			System.err.println("No local load file found.");
			return;
		}

		RepositoryConnection conn = QueryApplication.get().getRepositoryConnection();
		if (conn == null) {
			throw new RuntimeException("No repository connection found.");
		}

		try {
			BufferedReader reader = new BufferedReader(new FileReader(loadFile));
			String line = reader.readLine();
			while (line != null) {
				NanopubLoader.load(conn, line);
				line = reader.readLine();
			}
			reader.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

}
