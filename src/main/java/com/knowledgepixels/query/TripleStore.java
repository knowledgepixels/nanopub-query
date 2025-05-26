package com.knowledgepixels.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

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

public class TripleStore {

	public static final String ADMIN_REPO = "admin";

	private static ValueFactory vf = SimpleValueFactory.getInstance();

	public static final IRI HAS_REPO_INIT_ID = vf.createIRI("http://purl.org/nanopub/admin/hasRepoInitId");
	public static final IRI HAS_NANOPUB_COUNT = vf.createIRI("http://purl.org/nanopub/admin/hasNanopubCount");
	public static final IRI HAS_NANOPUB_CHECKSUM = vf.createIRI("http://purl.org/nanopub/admin/hasNanopubChecksum");
	public static final IRI HAS_LOAD_NUMBER = vf.createIRI("http://purl.org/nanopub/admin/hasLoadNumber");
	public static final IRI HAS_LOAD_CHECKSUM = vf.createIRI("http://purl.org/nanopub/admin/hasLoadChecksum");
	public static final IRI HAS_LOAD_TIMESTAMP = vf.createIRI("http://purl.org/nanopub/admin/hasLoadTimestamp");
	public static final IRI HAS_STATUS = vf.createIRI("http://purl.org/nanopub/admin/hasStatus");
	public static final IRI HAS_REGISTRY_LOAD_COUNTER = vf.createIRI("http://purl.org/nanopub/admin/hasRegistryLoadCounter");
	public static final IRI THIS_REPO_ID = vf.createIRI("http://purl.org/nanopub/admin/thisRepo");
	public static final IRI HAS_COVERAGE_ITEM = vf.createIRI("http://purl.org/nanopub/admin/hasCoverageItem");
	public static final IRI HAS_COVERAGE_HASH = vf.createIRI("http://purl.org/nanopub/admin/hasCoverageHash");
	public static final IRI HAS_COVERAGE_FILTER = vf.createIRI("http://purl.org/nanopub/admin/hasCoverageFilter");

	private final Map<String, Repository> repositories = new LinkedHashMap<>();

	private String endpointBase = null;
	private String endpointType = null;

	volatile boolean terminated = false;

	public void terminate() {
		this.terminated = true;
	}

	private static TripleStore tripleStoreInstance;

	public static TripleStore get() {
		if (tripleStoreInstance == null) {
			try {
				tripleStoreInstance = new TripleStore();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		return tripleStoreInstance;
	}

	private TripleStore() throws IOException {
		Map<String,String> env = EnvironmentUtils.getProcEnvironment();
		endpointBase = env.get("ENDPOINT_BASE");
		System.err.println("Endpoint base: " + endpointBase);
		endpointType = env.get("ENDPOINT_TYPE");

		getRepository("empty");  // Make sure empty repo exists
	}

	private final CloseableHttpClient httpclient = HttpClients.createDefault();

	private Repository getRepository(String name) {
		synchronized (this) {
			while (repositories.size() > 100) {
				Entry<String, Repository> e = repositories.entrySet().iterator().next();
				repositories.remove(e.getKey());
				System.err.println("Shutting down repo: " + e.getKey());
				e.getValue().shutDown();
				System.err.println("Shutdown complete");
			}
			if (repositories.containsKey(name)) {
				// Move to the end of the list:
				Repository repo = repositories.remove(name);
				repositories.put(name, repo);
			} else {
				Repository repository = null;
				if (endpointType == null || endpointType.equals("rdf4j")) {
					HTTPRepository hr = new HTTPRepository(endpointBase + "repositories/" + name);
					hr.setHttpClient(httpclient);
					repository = hr;
//			} else if (endpointType.equals("virtuoso")) {
//				repository = new VirtuosoRepository(endpointBase + name, username, password);
				} else {
					throw new RuntimeException("Unknown repository type: " + endpointType);
				}
				repositories.put(name, repository);
				createRepo(name);
				getRepoConnection(name).close();
			}
			return repositories.get(name);
		}
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
					+ "@prefix lmdb: <http://rdf4j.org/config/sail/lmdb#>.\n"
					+ "@prefix sb: <http://www.openrdf.org/config/sail/base#>.\n"
					+ "\n"
					+ "[] a rep:Repository ;\n"
					+ "    rep:repositoryID \"" + repoName + "\" ;\n"
					+ "    rdfs:label \"" + repoName + " LMDB store\" ;\n"
					+ "    rep:repositoryImpl [\n"
					+ "        rep:repositoryType \"openrdf:SailRepository\" ;\n"
					+ "        sr:sailImpl [\n"
					+ "            sail:sailType \"rdf4j:LmdbStore\" ;\n"
					+ "            sail:iterationCacheSyncThreshold \"10000\";\n"
					+ "            lmdb:tripleIndexes \"" + indexTypes + "\" ;\n"
					+ "            sb:defaultQueryEvaluationMode \"STANDARD\"\n"
					+ "        ]\n"
					+ "    ].\n";

			// TODO Index npa:hasFilterLiteral predicate too (see https://groups.google.com/g/rdf4j-users/c/epF4Af1jXGU):
			String createTextRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
					+ "@prefix rep: <http://www.openrdf.org/config/repository#>.\n"
					+ "@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n"
					+ "@prefix sail: <http://www.openrdf.org/config/sail#>.\n"
					+ "@prefix sail-luc: <http://www.openrdf.org/config/sail/lucene#>.\n"
					+ "@prefix lmdb: <http://rdf4j.org/config/sail/lmdb#>.\n"
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
					+ "              sail:sailType \"rdf4j:LmdbStore\" ;\n"
					+ "              sail:iterationCacheSyncThreshold \"10000\";\n"
					+ "              lmdb:tripleIndexes \"" + indexTypes + "\" ;\n"
					+ "              sb:defaultQueryEvaluationMode \"STANDARD\"\n"
					+ "            ]\n"
					+ "        ]\n"
					+ "    ].";

			String createRepoQueryString = createRegularRepoQueryString;
			if (repoName.startsWith("text")) {
				createRepoQueryString = createTextRepoQueryString;
			}

			HttpUriRequest createRepoRequest = RequestBuilder.put()
					.setUri(endpointBase + "repositories/" + repoName)
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

	public void shutdownRepositories() {
		for (Repository repo : repositories.values()) {
			if (repo != null && repo.isInitialized()) {
				repo.shutDown();
			}
		}
	}

	public RepositoryConnection getAdminRepoConnection() {
		return get().getRepoConnection(ADMIN_REPO);
	}

	public Set<String> getRepositoryNames() {
		Map<String,Boolean> repositoryNames = null;
		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
			HttpResponse resp = httpclient.execute(RequestBuilder.get()
					.setUri(endpointBase + "/repositories")
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

	private void initNewRepo(String repoName) {
		String repoInitId = new Random().nextLong() + "";
		getRepository(repoName).init();
		if (!repoName.equals("empty")) {
			RepositoryConnection conn = getRepoConnection(repoName);
			try (conn) {
				// Full isolation, just in case.
				conn.begin(IsolationLevels.SERIALIZABLE);
				conn.add(THIS_REPO_ID, HAS_REPO_INIT_ID, vf.createLiteral(repoInitId), NanopubLoader.ADMIN_GRAPH);
				conn.add(THIS_REPO_ID, HAS_NANOPUB_COUNT, vf.createLiteral(0l), NanopubLoader.ADMIN_GRAPH);
				conn.add(THIS_REPO_ID, HAS_NANOPUB_CHECKSUM, vf.createLiteral(NanopubUtils.INIT_CHECKSUM), NanopubLoader.ADMIN_GRAPH);
				if (repoName.startsWith("pubkey_") || repoName.startsWith("type_")) {
					String h = repoName.replaceFirst("^[^_]+_", "");
					conn.add(THIS_REPO_ID, HAS_COVERAGE_ITEM, Utils.getObjectForHash(h), NanopubLoader.ADMIN_GRAPH);
					conn.add(THIS_REPO_ID, HAS_COVERAGE_HASH, vf.createLiteral(h), NanopubLoader.ADMIN_GRAPH);
					conn.add(THIS_REPO_ID, HAS_COVERAGE_FILTER, vf.createLiteral("_" + repoName), NanopubLoader.ADMIN_GRAPH);
				}
				conn.commit();
			}
		}
	}

}
