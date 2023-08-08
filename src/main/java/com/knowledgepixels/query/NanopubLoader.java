package com.knowledgepixels.query;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.setting.IntroNanopub;

import net.trustyuri.TrustyUriUtils;
import net.trustyuri.rdf.RdfHasher;

public class NanopubLoader {

	private NanopubLoader() {}  // no instances allowed

	private static int loadCount = 0;

	public static void load(String nanopubUri) {
		Nanopub np = GetNanopub.get(nanopubUri);
		load(np);
	}

	public static void load(Nanopub np) throws RDF4JException {
		System.err.println("Loading: " + ++loadCount + " " + np.getUri());

		RepositoryConnection conn = QueryApplication.get().getRepositoryConnection("main");

		// TODO: Check for null characters ("\0"), which can cause problems in Virtuoso.

		List<Statement> statements = new ArrayList<>();
		String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
		if (!np.getHeadUri().toString().contains(ac) || !np.getAssertionUri().toString().contains(ac) ||
				!np.getProvenanceUri().toString().contains(ac) || !np.getPubinfoUri().toString().contains(ac)) {
			conn.add(vf.createStatement(np.getUri(), NOTE, vf.createLiteral("could not load nanopub as not all graphs contained the artifact code"), ADMIN_GRAPH));
			return;
		}

		NanopubSignatureElement el = null;
		try {
			el = SignatureUtils.getSignatureElement(np);
		} catch (MalformedCryptoElementException ex) {
			System.err.println("Signature error for " + np.getUri());
			ex.printStackTrace();
		}
		if (!hasValidSignature(el)) {
			return;
		}

		MessageDigest md = RdfHasher.getDigest();
		md.update(el.getPublicKeyString().getBytes());
		String pubkeyHash = TrustyUriUtils.getBase64(md.digest());
		loadToRepo(np, "pubkey_" + getBase64Hash(el.getPublicKeyString()));
		for (IRI typeIri : NanopubUtils.getTypes(np)) {
			loadToRepo(np, "type_" + getBase64Hash(typeIri.stringValue()));
		}

//		for (IRI s : SimpleCreatorPattern.getCreators(np)) {
//			if (s.stringValue().startsWith("https://orcid.org")) {
//				String repoName = "user_" + s.stringValue().replaceFirst("^.*/([0-9\\-X]*)$", "$1");
//				System.err.println("Loading to repo: " + repoName);
//				loadToRepo(np, repoName);
//			}
//		}

		Set<IRI> subIris = new HashSet<>();
		Set<IRI> otherNps = new HashSet<>();
		for (Statement st : NanopubUtils.getStatements(np)) {
			statements.add(st);
			if (st.getPredicate().toString().contains(ac)) {
				subIris.add(st.getPredicate());
			} else {
				IRI b = getBaseTrustyUri(st.getPredicate());
				if (b != null) otherNps.add(b);
			}
			if (st.getSubject().equals(np.getUri()) && st.getObject() instanceof IRI) {
				if (st.getObject().toString().matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}")) {
					statements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), ADMIN_NETWORK_GRAPH));
					continue;
				}
			}
			if (st.getSubject().toString().contains(ac)) {
				subIris.add((IRI) st.getSubject());
			} else {
				IRI b = getBaseTrustyUri(st.getSubject());
				if (b != null) otherNps.add(b);
			}
			if (st.getObject() instanceof IRI) {
				if (st.getObject().toString().contains(ac)) {
			 		subIris.add((IRI) st.getObject());
				} else {
					IRI b = getBaseTrustyUri(st.getObject());
					if (b != null) otherNps.add(b);
				}
			}
		}
		subIris.remove(np.getUri());
		subIris.remove(np.getAssertionUri());
		subIris.remove(np.getProvenanceUri());
		subIris.remove(np.getPubinfoUri());
		for (IRI i : subIris) {
			statements.add(vf.createStatement(np.getUri(), HAS_SUB_IRI, i, ADMIN_GRAPH));
		}
		for (IRI i : otherNps) {
			statements.add(vf.createStatement(np.getUri(), REFERS_TO_NANOPUB, i, ADMIN_NETWORK_GRAPH));
		}

		statements.add(vf.createStatement(np.getUri(), HAS_HEAD_GRAPH, np.getHeadUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_ASSERTION_URI, np.getAssertionUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PROVENANCE_URI, np.getProvenanceUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PUBINFO_URI, np.getPubinfoUri(), ADMIN_GRAPH));

		String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
		statements.add(vf.createStatement(np.getUri(), HAS_ARTIFACT_CODE, vf.createLiteral(artifactCode), ADMIN_GRAPH));

		if (hasValidSignature(el)) {
			statements.add(vf.createStatement(np.getUri(), HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, vf.createLiteral(el.getPublicKeyString()), ADMIN_GRAPH));

			if (isIntroNanopub(np)) {
				IntroNanopub introNp = new IntroNanopub(np);
				statements.add(vf.createStatement(np.getUri(), IS_INTRO_OF, introNp.getUser(), ADMIN_GRAPH));
				for (KeyDeclaration kc : introNp.getKeyDeclarations()) {
					statements.add(vf.createStatement(np.getUri(), DECLARES_KEY, vf.createLiteral(kc.getPublicKeyString()), ADMIN_GRAPH));
				}
			}
		}
		Calendar timestamp = null;
		try {
			timestamp = SimpleTimestampPattern.getCreationTime(np);
		} catch (IllegalArgumentException ex) {
			System.err.println("Illegal date/time for nanopublication " + np.getUri());
		}
		if (timestamp != null) {
			statements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(timestamp.getTime()), ADMIN_GRAPH));
		} else {
			statements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(""), ADMIN_GRAPH));
		}
		while (statements.size() > 1000) {
			conn.add(statements.subList(0, 1000));
			statements = statements.subList(1000, statements.size());
		}
		conn.add(statements);
		conn.close();
	}

	public static void loadToRepo(Nanopub np, String repoName) {
		System.err.println("Loading to repo: " + repoName);
		boolean success = false;
		int count = 0;
		while (!success && count < 5) {
			count++;
			try {
				RepositoryConnection conn = QueryApplication.get().getRepositoryConnection(repoName);
				conn.add(NanopubUtils.getStatements(np));
				conn.close();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				System.err.println("Retrying...");
			}
		}
	}

	private static boolean hasValidSignature(NanopubSignatureElement el) {
		try {
			if (el != null && SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
				return true;
			}
		} catch (GeneralSecurityException ex) {
			System.err.println("Error for signature element " + el.getUri());
			ex.printStackTrace();
		}
		return false;
	}

	private static IRI getBaseTrustyUri(Value v) {
		if (!(v instanceof IRI)) return null;
		String s = v.stringValue();
		if (!s.matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}([^A-Za-z0-9\\\\-_].{0,43})?")) {
			return null;
		}
		return vf.createIRI(s.replaceFirst("^(.*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43})([^A-Za-z0-9\\\\-_].{0,43})?$", "$1"));
	}

	// TODO: Move this to nanopub library:
	private static boolean isIntroNanopub(Nanopub np) {
		for (Statement st : np.getAssertion()) {
			if (st.getPredicate().equals(KeyDeclaration.DECLARED_BY)) return true;
		}
		return false;
	}

	private static ValueFactory vf = SimpleValueFactory.getInstance();

	public static final IRI ADMIN_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/graph");
	public static final IRI ADMIN_NETWORK_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/networkGraph");
	public static final IRI HAS_HEAD_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/hasHeadGraph");
	public static final IRI NOTE = vf.createIRI("http://purl.org/nanopub/admin/note");
	public static final IRI HAS_SUB_IRI = vf.createIRI("http://purl.org/nanopub/admin/hasSubIri");
	public static final IRI REFERS_TO_NANOPUB = vf.createIRI("http://purl.org/nanopub/admin/refersToNanopub");
	public static final IRI HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY = vf.createIRI("http://purl.org/nanopub/admin/hasValidSignatureForPublicKey");
	public static final IRI HAS_ARTIFACT_CODE = vf.createIRI("http://purl.org/nanopub/admin/artifactCode");
	public static final IRI IS_INTRO_OF = vf.createIRI("http://purl.org/nanopub/admin/isIntroductionOf");
	public static final IRI DECLARES_KEY = vf.createIRI("http://purl.org/nanopub/admin/declaresPubkey");

	public static String getBase64Hash(String s) {
		MessageDigest md = RdfHasher.getDigest();
		md.update(s.getBytes());
		return TrustyUriUtils.getBase64(md.digest());
	}

}
