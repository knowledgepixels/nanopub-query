package com.knowledgepixels.query;

import java.io.IOException;

import org.eclipse.rdf4j.repository.RepositoryConnection;

public class QueryApplication {

	private static QueryApplication instance;

	public static QueryApplication get() {
		if (instance == null) instance = new QueryApplication();
		return instance;
	}

	public static void triggerInit() {
		if (get().initialized == true) return;
		new Thread() {

			@Override
			public void run() {
				get().init();
			}

		}.start();
	}

	private static final int WAIT_SECONDS = 5;

	private boolean initialized = false;
	private TripleStoreThread tripleStoreThread;

	private QueryApplication() {}

	public synchronized void init() {
		if (initialized) return;
		initialized = true;

		System.err.println("Waiting " + WAIT_SECONDS + " seconds to make sure the triple store is up...");
		try {
			for (int waitSeconds = 0 ; waitSeconds < WAIT_SECONDS ; waitSeconds++) {
				System.err.println("Waited " + waitSeconds + " seconds...");
				Thread.sleep(1000);
			}
		} catch (InterruptedException ex) {
			ex.printStackTrace();
		}

		System.err.println("Starting the triple store thread...");
		try {
			tripleStoreThread = new TripleStoreThread();
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					tripleStoreThread.terminate();
					try {
						tripleStoreThread.join();
					} catch (InterruptedException ex) {
						ex.printStackTrace();
					}
				}

			});
			tripleStoreThread.start();
		} catch (IOException ex) {
			ex.printStackTrace();
		}

		System.err.println("Loading the local list of nanopubs...");
		LocalListLoader.load();
	}

	public TripleStoreThread getTripleStoreThread() {
		return tripleStoreThread;
	}

	public RepositoryConnection getRepositoryConnection() {
		if (tripleStoreThread == null) return null;
		return tripleStoreThread.getRepositoryConnection();
	}

}
