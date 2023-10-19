package com.knowledgepixels.query;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.nanopub.NanopubUtils;

import virtuoso.rdf4j.driver.VirtuosoRepository;

public class TripleStoreThread extends Thread {

	public static final String ADMIN_REPO = "admin";

	private static ValueFactory vf = SimpleValueFactory.getInstance();

	public static final IRI HAS_REPO_INIT_ID = vf.createIRI("http://purl.org/nanopub/admin/hasRepoInitId");
	public static final IRI HAS_NANOPUB_COUNT = vf.createIRI("http://purl.org/nanopub/admin/hasNanpubCount");
	public static final IRI HAS_NANOPUB_CHECKSUM = vf.createIRI("http://purl.org/nanopub/admin/hasNanopubChecksum");
	public static final IRI HAS_LOAD_NUMBER = vf.createIRI("http://purl.org/nanopub/admin/hasLoadNumber");
	public static final IRI HAS_LOAD_CHECKSUM = vf.createIRI("http://purl.org/nanopub/admin/hasLoadChecksum");
	public static final IRI HAS_LOAD_TIMESTAMP = vf.createIRI("http://purl.org/nanopub/admin/hasLoadTimestamp");
	public static final IRI THIS_REPO_ID = vf.createIRI("http://purl.org/nanopub/admin/thisRepo");

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
			Repository repository = null;
			if (endpointType == null || endpointType.equals("rdf4j")) {
				repository = new HTTPRepository(endpointBase + name);
			} else if (endpointType.equals("virtuoso")) {
				repository = new VirtuosoRepository(endpointBase + name, username, password);
			} else {
				throw new RuntimeException("Unknown repository type: " + endpointType);
			}
			repositories.put(name, repository);
			createRepo(name);
			RepositoryConnection conn = getRepoConnection(name);
			conn.close();
		}
		return repositories.get(name);
	}

	public RepositoryConnection getRepoConnection(String name) {
		Repository repo = getRepository(name);
		if (repo == null) return null;
		return repo.getConnection();
	}

	private void createRepo(String repoName) {
		if (!repoName.equals(ADMIN_REPO)) {
			getRepository(ADMIN_REPO);  // make sure admin repo is loaded first
		}
		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
			//System.err.println("Trying to creating repo " + name);

			// TODO new syntax somehow doesn't work for the Lucene case:

//			String createRegularRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
//					+ "@prefix config: <tag:rdf4j.org,2023:config/>.\n"
//					+ "[] a config:Repository ;\n"
//					+ "    config:rep.id \"" + name + "\" ;\n"
//					+ "    rdfs:label \"" + name + " native store\" ;\n"
//					+ "    config:rep.impl [\n"
//					+ "        config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "        config:sail.impl [\n"
//					+ "            config:sail.type \"openrdf:NativeStore\" ;\n"
//					+ "            config:sail.iterationCacheSyncThreshold \"10000\";\n"
//					+ "            config:native.tripleIndexes \"spoc,posc,ospc,opsc,psoc,sopc,spoc,cpos,cosp,cops,cpso,csop\" ;\n"
//					+ "            config:sail.defaultQueryEvaluationMode \"STANDARD\"\n"
//					+ "        ]\n"
//					+ "    ].";
//			String createTextRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
//					+ "@prefix config: <tag:rdf4j.org,2023:config/>.\n"
//					+ "[] a config:Repository ;\n"
//					+ "    config:rep.id \"" + name + "\" ;\n"
//					+ "    rdfs:label \"" + name + " native store\" ;\n"
//					+ "    config:rep.impl [\n"
//					+ "        config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "        config:sail.impl [\n"
//					+ "            config:sail.type \"openrdf:LuceneSail\" ;\n"
//					+ "            config:sail.lucene.indexDir \"index/\" ;\n"
//					+ "            config:delegate [\n"
//					+ "                config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "                config:sail.impl [\n"
//					+ "                    config:sail.type \"openrdf:NativeStore\" ;\n"
//					+ "                    config:sail.iterationCacheSyncThreshold \"10000\";\n"
//					+ "                    config:native.tripleIndexes \"spoc,posc,ospc,opsc,psoc,sopc,spoc,cpos,cosp,cops,cpso,csop\" ;\n"
//					+ "                    config:sail.defaultQueryEvaluationMode \"STANDARD\"\n"
//					+ "                ]\n"
//					+ "            ]\n"
//					+ "        ]\n"
//					+ "    ].";

			String indexTypes = "spoc,posc,ospc,cspo,cpos,cosp";
			if (repoName.startsWith("meta") || repoName.startsWith("text")) {
				indexTypes = "spoc,posc,ospc";
			}

			String createRegularRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
					+ "@prefix rep: <http://www.openrdf.org/config/repository#>.\n"
					+ "@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n"
					+ "@prefix sail: <http://www.openrdf.org/config/sail#>.\n"
					+ "@prefix sail-luc: <http://www.openrdf.org/config/sail/lucene#>.\n"
					+ "@prefix ns: <http://www.openrdf.org/config/sail/native#>.\n"
					+ "@prefix sb: <http://www.openrdf.org/config/sail/base#>.\n"
					+ "\n"
					+ "[] a rep:Repository ;\n"
					+ "    rep:repositoryID \"" + repoName + "\" ;\n"
					+ "    rdfs:label \"" + repoName + " native store\" ;\n"
					+ "    rep:repositoryImpl [\n"
					+ "        rep:repositoryType \"openrdf:SailRepository\" ;\n"
					+ "        sr:sailImpl [\n"
					+ "            sail:sailType \"openrdf:NativeStore\" ;\n"
					+ "            sail:iterationCacheSyncThreshold \"10000\";\n"
					+ "            ns:tripleIndexes \"" + indexTypes + "\" ;\n"
					+ "            sb:defaultQueryEvaluationMode \"STANDARD\"\n"
					+ "        ]\n"
					+ "    ].\n";
			String createTextRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
					+ "@prefix rep: <http://www.openrdf.org/config/repository#>.\n"
					+ "@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n"
					+ "@prefix sail: <http://www.openrdf.org/config/sail#>.\n"
					+ "@prefix sail-luc: <http://www.openrdf.org/config/sail/lucene#>.\n"
					+ "@prefix ns: <http://www.openrdf.org/config/sail/native#>.\n"
					+ "@prefix sb: <http://www.openrdf.org/config/sail/base#>.\n"
					+ "\n"
					+ "[] a rep:Repository ;\n"
					+ "    rep:repositoryID \"" + repoName + "\" ;\n"
					+ "    rdfs:label \"" + repoName + " store\" ;\n"
					+ "    rep:repositoryImpl [\n"
					+ "        rep:repositoryType \"openrdf:SailRepository\" ;\n"
					+ "        sr:sailImpl [\n"
					+ "            sail:sailType \"openrdf:LuceneSail\" ;\n"
					+ "            sail-luc:indexDir \"index/\" ;\n"
					+ "            sail:delegate ["
					+ "              sail:sailType \"openrdf:NativeStore\" ;\n"
					+ "              sail:iterationCacheSyncThreshold \"10000\";\n"
					+ "              ns:tripleIndexes \"" + indexTypes + "\" ;\n"
					+ "              sb:defaultQueryEvaluationMode \"STANDARD\"\n"
					+ "            ]\n"
					+ "        ]\n"
					+ "    ].";

			String createRepoQueryString = createRegularRepoQueryString;
			if (repoName.startsWith("text")) {
				createRepoQueryString = createTextRepoQueryString;
			}

			HttpUriRequest createRepoRequest = RequestBuilder.put()
					.setUri("http://rdf4j:8080/rdf4j-server/repositories/" + repoName)
					.addHeader("Content-Type", "text/turtle")
					.setEntity(new StringEntity(createRepoQueryString))
					.build();

			HttpResponse response = httpclient.execute(createRepoRequest);
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode == 409) {
				//System.err.println("Already exists.");
				getRepository(repoName).init();
			} else if (statusCode >= 200 && statusCode < 300) {
				//System.err.println("Successfully created.");
				initNewRepo(repoName);
			} else {
				System.err.println("Status code: " + response.getStatusLine().getStatusCode());
				System.err.println(response.getStatusLine().getReasonPhrase());
				System.err.println("Response: " + new BasicResponseHandler().handleResponse(response));
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

	private void initNewRepo(String repoName) {
		String repoInitId = new Random().nextLong() + "";
		getRepository(repoName).init();
		RepositoryConnection conn = getRepoConnection(repoName);
		conn.begin(IsolationLevels.SERIALIZABLE);
		conn.add(THIS_REPO_ID, HAS_REPO_INIT_ID, vf.createLiteral(repoInitId), NanopubLoader.ADMIN_GRAPH);
		conn.add(THIS_REPO_ID, HAS_NANOPUB_COUNT, vf.createLiteral(0l), NanopubLoader.ADMIN_GRAPH);
		conn.add(THIS_REPO_ID, HAS_NANOPUB_CHECKSUM, vf.createLiteral(NanopubUtils.INIT_CHECKSUM), NanopubLoader.ADMIN_GRAPH);
		conn.commit();
		conn.close();
	}

}
