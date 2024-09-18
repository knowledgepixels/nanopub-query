package com.knowledgepixels.query;

import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.nanopub.Nanopub;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.services.ApiAccess;

import io.vertx.core.MultiMap;
import net.trustyuri.TrustyUriUtils;


public class GrlcSpecPage {

	public static final String MOUNT_PATH = "/grlc-spec";

	public static final ValueFactory vf = SimpleValueFactory.getInstance();
	public static final IRI HAS_SPARQL = vf.createIRI("https://w3id.org/kpxl/grlc/sparql");
	public static final IRI HAS_ENDPOINT = vf.createIRI("https://w3id.org/kpxl/grlc/endpoint");

	public static final String nanopubQueryUrl = Utils.getEnvString("NANOPUB_QUERY_URL", "http://localhost:9393/");

	private Nanopub np;
	private String requestUrlBase;
	private String artifactCode, queryPart;
	private String queryName;
	private String label;
	private String desc;
	private String license;
	private String queryContent;
	private String endpoint;

	public GrlcSpecPage(String requestUrl, MultiMap parameters) {
		requestUrl = requestUrl.replaceFirst("\\?.*$", "");
		if (!requestUrl.matches(".*/RA[A-Za-z0-9\\-_]{43}/(.*)?")) return;
		artifactCode = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$2");
		queryPart = requestUrl.replaceFirst("^(.*/)(RA[A-Za-z0-9\\-_]{43}/)(.*)?$", "$3");
		requestUrlBase = requestUrl.replaceFirst("^/(.*/)(RA[A-Za-z0-9\\-_]{43})/(.*)?$", "$1");

		// TODO Get the nanopub from the local store:
		np = GetNanopub.get(artifactCode);
		if (parameters.get("api-version") != null && "latest".equals(parameters.get("api-version").toString())) {
			// TODO Get the latest version from the local store:
			np = GetNanopub.get(ApiAccess.getLatestVersionId(np.getUri().stringValue()));
			artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
		}
		for (Statement st : np.getAssertion()) {
			if (!st.getSubject().stringValue().startsWith(np.getUri().stringValue())) continue;
			String qn = st.getSubject().stringValue().replaceFirst("^.*[#/](.*)$", "$1");
			if (queryName != null && !qn.equals(queryName)) {
				np = null;
				break;
			}
			queryName = qn;
			if (st.getPredicate().equals(RDFS.LABEL)) {
				label = st.getObject().stringValue();
			} else if (st.getPredicate().equals(DCTERMS.DESCRIPTION)) {
				desc = st.getObject().stringValue();
			} else if (st.getPredicate().equals(DCTERMS.LICENSE) && st.getObject() instanceof IRI) {
				license = st.getObject().stringValue();
			} else if (st.getPredicate().equals(HAS_SPARQL)) {
				queryContent = st.getObject().stringValue().replace("https://w3id.org/np/l/nanopub-query-1.1/", nanopubQueryUrl);
			} else if (st.getPredicate().equals(HAS_ENDPOINT) && st.getObject() instanceof IRI) {
				endpoint = st.getObject().stringValue();
				if (endpoint.startsWith("https://w3id.org/np/l/nanopub-query-1.1/")) {
					endpoint = endpoint.replace("https://w3id.org/np/l/nanopub-query-1.1/", nanopubQueryUrl);
				}
			}
		}
	}

	public String getSpec() {
		if (np == null) return null;
		String s = "";
		if (queryPart.isEmpty()) {
			if (label == null) {
				s += "title: \"untitled query\"\n";
			} else {
				s += "title: \"" + escape(label) + "\"\n";
			}
			s += "description: \"" + escape(desc) + "\"\n";
			String userName = "";
			Set<IRI> creators = SimpleCreatorPattern.getCreators(np);
			for (IRI userIri : creators) {
				userName += ", " + userIri;
			}
			if (!userName.isEmpty()) userName = userName.substring(2);
			String url = "";
			if (!creators.isEmpty()) url = creators.iterator().next().stringValue();
			s += "contact:\n";
			s += "  name: \"" + escape(userName) + "\"\n";
			s += "  url: " + url + "\n";
			if (license != null) {
				s += "licence: " + license + "\n";
			}
			s += "queries:\n";
			s += "  - " + nanopubQueryUrl + requestUrlBase + artifactCode + "/" + queryName + ".rq";
		} else if (queryPart.equals(queryName + ".rq")) {
			if (label != null) {
				s += "#+ summary: \"" + escape(label) + "\"\n";
			}
			if (desc != null) {
				s += "#+ description: \"" + escape(desc) + "\"\n";
			}
			if (license != null) {
				s += "#+ licence: " + license + "\n";
			}
			if (endpoint != null) {
				s += "#+ endpoint: " + endpoint + "\n";
			}
			s += "\n";
			s += queryContent;
		} else {
			return null;
		}
		return s;
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
	}

}
