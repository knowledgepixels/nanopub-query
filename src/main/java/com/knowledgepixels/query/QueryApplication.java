package com.knowledgepixels.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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

		// Ensure empty repo exists:
		getRepoConnection("empty");

		System.err.println("Loading the local list of nanopubs...");
		LocalNanopubLoader.load();
	}

	public TripleStoreThread getTripleStoreThread() {
		return tripleStoreThread;
	}

	public RepositoryConnection getRepoConnection(String name) {
		if (tripleStoreThread == null) return null;
		return tripleStoreThread.getRepoConnection(name);
	}

	public RepositoryConnection getAdminRepoConnection() {
		return getRepoConnection(TripleStoreThread.ADMIN_REPO);
	}

	public Set<String> getRepositoryNames() {
		Map<String,Boolean> repositoryNames = null;
		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
			HttpResponse resp = httpclient.execute(RequestBuilder.get()
					.setUri("http://rdf4j:8080/rdf4j-server/repositories")
					.addHeader("Content-Type", "text/csv")
					.build());
			BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getEntity().getContent()));
			int code = resp.getStatusLine().getStatusCode();
			if (code < 200 || code >= 300) return null;
			repositoryNames = new HashMap<>();
			int lineCount = 0;
			while (true) {
				String line = reader.readLine();
				if (line == null) break;
				if (lineCount > 0) {
					String repoName = line.split(",")[1];
					repositoryNames.put(repoName, true);
				}
				lineCount = lineCount + 1;
			}
		} catch (IOException ex) {
			ex.printStackTrace();
			return null;
		}
		return repositoryNames.keySet();
	}

}