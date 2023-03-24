package com.knowledgepixels.query;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;

import virtuoso.rdf4j.driver.VirtuosoRepository;

public class TripleStoreThread extends Thread {

	private Repository repository;
	private String endpoint = null;
	private String endpointType = null;
	private String username = null;
	private String password = null;

	volatile boolean terminated = false;

	public void terminate() {
		this.terminated = true;
	}

	public TripleStoreThread() throws IOException {
		Map<String,String> env = EnvironmentUtils.getProcEnvironment();
		endpoint = env.get("ENDPOINT");
		System.err.println("Endpoint: " + endpoint);
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
		shutdownRepository();
	}

	private Repository getRepository() {
		if (repository == null) {
			if (endpointType == null || endpointType.equals("rdf4j")) {
				repository = new HTTPRepository(endpoint);
			} else if (endpointType.equals("virtuoso")) {
				repository = new VirtuosoRepository(endpoint, username, password);
			} else {
				throw new RuntimeException("Unknown repository type: " + endpointType);
			}
			repository.init();
		}
		return repository;
	}

	public RepositoryConnection getRepositoryConnection() {
		Repository repo = getRepository();
		if (repo == null) return null;
		return repo.getConnection();
	}

	private void shutdownRepository() {
		Repository repo = getRepository();
		if(repo != null && repo.isInitialized()) {
			repo.shutDown();
		}
	}

}
