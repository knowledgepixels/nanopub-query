package com.knowledgepixels.query;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import virtuoso.rdf4j.driver.VirtuosoRepository;

public class TripleStoreThread extends Thread {

	private VirtuosoRepository virtuosoRepository;
	private String endpoint = null;
	private String username = null;
	private String password = null;

	volatile boolean terminated = false;

	public void terminate() {
		this.terminated = true;
	}

	public TripleStoreThread() throws IOException {
		Map<String,String> env = EnvironmentUtils.getProcEnvironment();
		endpoint = env.get("ENDPOINT");
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

	private VirtuosoRepository getRepository() {
		if (virtuosoRepository == null || !virtuosoRepository.isInitialized()) {
			virtuosoRepository = new VirtuosoRepository(endpoint, username, password);
			virtuosoRepository.init();
		}
		return virtuosoRepository;
	}

	public RepositoryConnection getRepositoryConnection() {
		VirtuosoRepository repo = getRepository();
		if (repo == null) return null;
		return repo.getConnection();
	}

	private void shutdownRepository() {
		VirtuosoRepository repo = getRepository();
		if(repo != null && repo.isInitialized()) {
			repo.shutDown();
		}
	}

}
