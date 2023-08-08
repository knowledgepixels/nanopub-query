package com.knowledgepixels.query;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;

import virtuoso.rdf4j.driver.VirtuosoRepository;

public class TripleStoreThread extends Thread {

	private Map<String,Repository> repositories = new HashMap<>();
	private String endpointBase = null;
	private String endpointType = null;
	private String username = null;
	private String password = null;

	volatile boolean terminated = false;

	public void terminate() {
		this.terminated = true;
	}

	public TripleStoreThread() throws IOException {
		Map<String,String> env = EnvironmentUtils.getProcEnvironment();
		endpointBase = env.get("ENDPOINT_BASE");
		System.err.println("Endpoint base: " + endpointBase);
		endpointType = env.get("ENDPOINT_TYPE");
		username = env.get("USERNAME");
		password = env.get("PASSWORD");
	}

	@Override
	public void run() {
		while(!terminated) {
			try {
				sleep(1000);
			} catch (InterruptedException ex) {
				ex.printStackTrace();
			}
		}
		shutdownRepositories();
	}

	private Repository getRepository(String name) {
		if (!repositories.containsKey(name)) {
			createRepository(name);
			Repository repository = null;
			if (endpointType == null || endpointType.equals("rdf4j")) {
				repository = new HTTPRepository(endpointBase + name);
			} else if (endpointType.equals("virtuoso")) {
				repository = new VirtuosoRepository(endpointBase + name, username, password);
			} else {
				throw new RuntimeException("Unknown repository type: " + endpointType);
			}
			repository.init();
			repositories.put(name, repository);
		}
		return repositories.get(name);
	}

	public RepositoryConnection getRepositoryConnection(String name) {
		Repository repo = getRepository(name);
		if (repo == null) return null;
		return repo.getConnection();
	}

	private void createRepository(String name) {
		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
			System.err.println("Trying to creating repo " + name);
			HttpUriRequest createRepoRequest = RequestBuilder.put()
					.setUri("http://rdf4j:8080/rdf4j-server/repositories/" + name)
					.addHeader("Content-Type", "text/turtle")
					.setEntity(new StringEntity(
							"@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n" +
							"@prefix rep: <http://www.openrdf.org/config/repository#>.\n" +
							"@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n" +
							"@prefix sail: <http://www.openrdf.org/config/sail#>.\n" +
							"@prefix ms: <http://www.openrdf.org/config/sail/memory#>.\n" +
							"[] a rep:Repository ;\n" +
							"  rep:repositoryID \"" + name + "\" ;\n" +
							"  rdfs:label \"" + name + " memory store\" ;\n" +
							"  rep:repositoryImpl [\n" +
							"    rep:repositoryType \"openrdf:SailRepository\" ;\n" +
							"    sr:sailImpl [\n" +
							"      sail:sailType \"openrdf:NativeStore\" \n" +
							"    ]\n" +
							"  ]."
						))
					.build();

			HttpResponse response = httpclient.execute(createRepoRequest);
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode == 409) {
				System.err.println("Already exists.");
			} else if (statusCode == 204) {
				System.err.println("Successfully created.");
			} else {
				System.err.println("Status code: " + response.getStatusLine().getStatusCode());
				String responseString = new BasicResponseHandler().handleResponse(response);
				System.err.println("Response: " + responseString);
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	private void shutdownRepositories() {
		for (Repository repo : repositories.values()) {
			if (repo != null && repo.isInitialized()) {
				repo.shutDown();
			}
		}
	}

}
