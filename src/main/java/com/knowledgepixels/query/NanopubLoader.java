package com.knowledgepixels.query;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.setting.IntroNanopub;

import net.trustyuri.TrustyUriUtils;

public class NanopubLoader {

	private NanopubLoader() {}  // no instances allowed

	private static int loadCount = 0;

	public static void load(String nanopubUri) {
		Nanopub np = GetNanopub.get(nanopubUri);
		load(np);
	}

	public static void load(Nanopub np) throws RDF4JException {
		System.err.println("Loading: " + ++loadCount + " " + np.getUri());

		// TODO: Check for null characters ("\0"), which can cause problems in Virtuoso.

		List<Statement> statements = new ArrayList<>();
		String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
		if (!np.getHeadUri().toString().contains(ac) || !np.getAssertionUri().toString().contains(ac) ||
				!np.getProvenanceUri().toString().contains(ac) || !np.getPubinfoUri().toString().contains(ac)) {
			loadNoteToRepo(np.getUri(), "could not load nanopub as not all graphs contained the artifact code");
			return;
		}

		NanopubSignatureElement el = null;
		try {
			el = SignatureUtils.getSignatureElement(np);
		} catch (MalformedCryptoElementException ex) {
			loadNoteToRepo(np.getUri(), "Signature error");
		}
		if (!hasValidSignature(el)) {
			return;
		}

		Set<IRI> subIris = new HashSet<>();
		Set<IRI> otherNps = new HashSet<>();
		Set<IRI> invalidated = new HashSet<>();
		Set<IRI> retracted = new HashSet<>();
		Set<IRI> superseded = new HashSet<>();
		for (Statement st : NanopubUtils.getStatements(np)) {
			statements.add(st);

			if (st.getSubject().equals(np.getUri()))

			if (st.getPredicate().toString().contains(ac)) {
				subIris.add(st.getPredicate());
			} else {
				IRI b = getBaseTrustyUri(st.getPredicate());
				if (b != null) otherNps.add(b);
			}
			if (st.getPredicate().equals(RETRACTS) && st.getObject() instanceof IRI) {
				retracted.add((IRI) st.getObject());
			}
			if (st.getPredicate().equals(INVALIDATES) && st.getObject() instanceof IRI) {
				retracted.add((IRI) st.getObject());
			}
			if (st.getSubject().equals(np.getUri()) && st.getObject() instanceof IRI) {
				if (st.getPredicate().equals(SUPERSEDES)) {
					superseded.add((IRI) st.getObject());
				}
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
		for (IRI i : invalidated) {
			statements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
		}
		for (IRI i : retracted) {
			statements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), RETRACTS, i, ADMIN_GRAPH));
		}
		for (IRI i : superseded) {
			statements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), SUPERSEDES, i, ADMIN_GRAPH));
		}

		statements.add(vf.createStatement(np.getUri(), HAS_HEAD_GRAPH, np.getHeadUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getHeadUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_ASSERTION_URI, np.getAssertionUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getAssertionUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PROVENANCE_URI, np.getProvenanceUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getProvenanceUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PUBINFO_URI, np.getPubinfoUri(), ADMIN_GRAPH));
		statements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getPubinfoUri(), ADMIN_GRAPH));

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
			loadNoteToRepo(np.getUri(), "Illegal date/time");
		}
		if (timestamp != null) {
			statements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(timestamp.getTime()), ADMIN_GRAPH));
		}

		for (IRI typeIri : NanopubUtils.getTypes(np)) {
			statements.add(vf.createStatement(np.getUri(), vf.createIRI("http://purl.org/nanopub/x/hasNanopubType"), typeIri, ADMIN_GRAPH));
		}
		String label = NanopubUtils.getLabel(np);
		if (label == null) label = "";
		statements.add(vf.createStatement(np.getUri(), RDFS.LABEL, vf.createLiteral(label), ADMIN_GRAPH));

		loadNanopubToRepo(np.getUri(), statements, "full");
		if (hasValidSignature(el)) {
			loadNanopubToRepo(np.getUri(), statements, "pubkey_" + Utils.createHash(el.getPublicKeyString()));
		}
		for (IRI typeIri : NanopubUtils.getTypes(np)) {
			loadNanopubToRepo(np.getUri(), statements, "type_" + Utils.createHash(typeIri));
		}
		for (IRI creatorIri : SimpleCreatorPattern.getCreators(np)) {
			loadNanopubToRepo(np.getUri(), statements, "user_" + Utils.createHash(creatorIri));
		}
	}

	public static void loadNanopubToRepo(IRI npId, List<Statement> statements, String repoName) {
		boolean success = false;
		while (!success) {
			try {
				RepositoryConnection conn = QueryApplication.get().getRepoConnection(repoName);
				conn.begin(IsolationLevels.SERIALIZABLE);
				if (Utils.getObjectForPattern(conn, ADMIN_GRAPH, npId, TripleStoreThread.HAS_LOAD_NUMBER) != null) {
					System.err.println("Already loaded: " + npId);
				} else {
					long count = Long.parseLong(Utils.getObjectForPattern(conn, ADMIN_GRAPH, TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_COUNT).stringValue());
					String checksum = Utils.getObjectForPattern(conn, ADMIN_GRAPH, TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_CHECKSUM).stringValue();
					String newChecksum = updateXorChecksum(npId, checksum);
					conn.remove(TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_COUNT, null, ADMIN_GRAPH);
					conn.remove(TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_CHECKSUM, null, ADMIN_GRAPH);
					conn.add(TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_COUNT, vf.createLiteral(count + 1), ADMIN_GRAPH);
					conn.add(TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_CHECKSUM, vf.createLiteral(newChecksum), ADMIN_GRAPH);
					conn.add(npId, TripleStoreThread.HAS_LOAD_NUMBER, vf.createLiteral(count), ADMIN_GRAPH);
					conn.add(npId, TripleStoreThread.HAS_LOAD_CHECKSUM, vf.createLiteral(newChecksum), ADMIN_GRAPH);
					while (statements.size() > 1000) {
						conn.add(statements.subList(0, 1000));
						statements = statements.subList(1000, statements.size());
					}
					conn.add(statements);
				}
				conn.commit();
				conn.close();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
			}
		}
	}

	public static byte[] getBase64Bytes(String trustyHashString) {
		String hashBase64 = trustyHashString.replace('-', '+').replace('_', '/') + "=";
		return DatatypeConverter.parseBase64Binary(hashBase64);
	}

	public static String updateXorChecksum(IRI nanopubId, String checksum) {
		byte[] checksumBytes = getBase64Bytes(checksum);
		byte[] addBytes = getBase64Bytes(TrustyUriUtils.getArtifactCode(nanopubId.stringValue()).substring(2));
		for (int i = 0 ; i < 32 ; i++) {
			checksumBytes[i] = (byte) (checksumBytes[i] ^ addBytes[i]);
		}
		return TrustyUriUtils.getBase64(checksumBytes);
	}

	public static void loadNoteToRepo(Resource subj, String note) {
		boolean success = false;
		while (!success) {
			try {
				RepositoryConnection conn = QueryApplication.get().getAdminRepoConnection();
				List<Statement> statements = new ArrayList<>();
				statements.add(vf.createStatement(subj, NOTE, vf.createLiteral(note), ADMIN_GRAPH));
				conn.add(statements);
				conn.close();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
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
	public static final IRI HAS_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/hasGraph");
	public static final IRI NOTE = vf.createIRI("http://purl.org/nanopub/admin/note");
	public static final IRI HAS_SUB_IRI = vf.createIRI("http://purl.org/nanopub/admin/hasSubIri");
	public static final IRI REFERS_TO_NANOPUB = vf.createIRI("http://purl.org/nanopub/admin/refersToNanopub");
	public static final IRI HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY = vf.createIRI("http://purl.org/nanopub/admin/hasValidSignatureForPublicKey");
	public static final IRI HAS_ARTIFACT_CODE = vf.createIRI("http://purl.org/nanopub/admin/artifactCode");
	public static final IRI IS_INTRO_OF = vf.createIRI("http://purl.org/nanopub/admin/isIntroductionOf");
	public static final IRI DECLARES_KEY = vf.createIRI("http://purl.org/nanopub/admin/declaresPubkey");
	public static final IRI SUPERSEDES = vf.createIRI("http://purl.org/nanopub/x/supersedes");
	public static final IRI RETRACTS = vf.createIRI("http://purl.org/nanopub/x/retracts");
	public static final IRI INVALIDATES = vf.createIRI("http://purl.org/nanopub/x/invalidates");

}
