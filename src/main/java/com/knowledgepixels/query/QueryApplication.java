package com.knowledgepixels.query;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
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

	private static int waitSeconds = 60;

	static {
		try {
			Map<String,String> env = EnvironmentUtils.getProcEnvironment();
			String s = env.get("INIT_WAIT_SECONDS");
			if (s != null && !s.isEmpty()) {
				waitSeconds = Integer.parseInt(s);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private boolean initialized = false;
	private TripleStoreThread tripleStoreThread;

	private QueryApplication() {}

	public synchronized void init() {
		if (initialized) return;
		initialized = true;

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
