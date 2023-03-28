package com.knowledgepixels.query;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
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

	public void createRepository(String repoName) {
		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
			HttpUriRequest request = RequestBuilder.put()
					.setUri("http://rdf4j:8080/rdf4j-server/repositories/" + repoName)
					.addHeader("Content-Type", "text/turtle")
					.setEntity(new StringEntity(
							"@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n" +
							"@prefix rep: <http://www.openrdf.org/config/repository#>.\n" +
							"@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n" +
							"@prefix sail: <http://www.openrdf.org/config/sail#>.\n" +
							"@prefix ms: <http://www.openrdf.org/config/sail/memory#>.\n" +
							"[] a rep:Repository ;\n" +
							"  rep:repositoryID \"" + repoName + "\" ;\n" +
							"  rdfs:label \"" + repoName + " memory store\" ;\n" +
							"  rep:repositoryImpl [\n" +
							"    rep:repositoryType \"openrdf:SailRepository\" ;\n" +
							"    sr:sailImpl [\n" +
							"      sail:sailType \"openrdf:MemoryStore\" ;\n" +
							"      ms:persist true ;\n" +
							"      ms:syncDelay 120\n" +
							"    ]\n" +
							"  ]."
						))
					.build();

			System.out.println("Executing PUT request... ");
			HttpResponse response = httpclient.execute(request);

			System.out.println("Status code: " + response.getStatusLine().getStatusCode());

			String responseString = new BasicResponseHandler().handleResponse(response);

			System.out.println("Response: " + responseString);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

}