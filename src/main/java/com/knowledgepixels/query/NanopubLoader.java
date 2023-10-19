package com.knowledgepixels.query;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
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

		List<Statement> metaStatements = new ArrayList<>();
		List<Statement> nanopubStatements = new ArrayList<>();
		List<Statement> literalStatements = new ArrayList<>();
		List<Statement> invalidateStatements = new ArrayList<>();

		Statement pubkeyStatement = vf.createStatement(np.getUri(), HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, vf.createLiteral(el.getPublicKeyString()), ADMIN_GRAPH);
		metaStatements.add(pubkeyStatement);

		Set<IRI> subIris = new HashSet<>();
		Set<IRI> otherNps = new HashSet<>();
		Set<IRI> invalidated = new HashSet<>();
		Set<IRI> retracted = new HashSet<>();
		Set<IRI> superseded = new HashSet<>();
		for (Statement st : NanopubUtils.getStatements(np)) {
			nanopubStatements.add(st);

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
				invalidated.add((IRI) st.getObject());
			}
			if (st.getSubject().equals(np.getUri()) && st.getObject() instanceof IRI) {
				if (st.getPredicate().equals(SUPERSEDES)) {
					superseded.add((IRI) st.getObject());
				}
				if (st.getObject().toString().matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}")) {
					metaStatements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), ADMIN_NETWORK_GRAPH));
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
			} else {
				if (st.getSubject().equals(np.getUri())) {
					literalStatements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), ADMIN_GRAPH));
				} else {
					literalStatements.add(vf.createStatement(np.getUri(), HAS_LITERAL, st.getObject(), ADMIN_GRAPH));
				}
			}
		}
		subIris.remove(np.getUri());
		subIris.remove(np.getAssertionUri());
		subIris.remove(np.getProvenanceUri());
		subIris.remove(np.getPubinfoUri());
		for (IRI i : subIris) {
			metaStatements.add(vf.createStatement(np.getUri(), HAS_SUB_IRI, i, ADMIN_GRAPH));
		}
		for (IRI i : otherNps) {
			metaStatements.add(vf.createStatement(np.getUri(), REFERS_TO_NANOPUB, i, ADMIN_NETWORK_GRAPH));
		}
		for (IRI i : invalidated) {
			invalidateStatements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
		}
		for (IRI i : retracted) {
			invalidateStatements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
			metaStatements.add(vf.createStatement(np.getUri(), RETRACTS, i, ADMIN_GRAPH));
		}
		for (IRI i : superseded) {
			invalidateStatements.add(vf.createStatement(np.getUri(), INVALIDATES, i, ADMIN_GRAPH));
			metaStatements.add(vf.createStatement(np.getUri(), SUPERSEDES, i, ADMIN_GRAPH));
		}

		metaStatements.add(vf.createStatement(np.getUri(), HAS_HEAD_GRAPH, np.getHeadUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getHeadUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), Nanopub.HAS_ASSERTION_URI, np.getAssertionUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getAssertionUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PROVENANCE_URI, np.getProvenanceUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getProvenanceUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PUBINFO_URI, np.getPubinfoUri(), ADMIN_GRAPH));
		metaStatements.add(vf.createStatement(np.getUri(), HAS_GRAPH, np.getPubinfoUri(), ADMIN_GRAPH));

		String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
		metaStatements.add(vf.createStatement(np.getUri(), HAS_ARTIFACT_CODE, vf.createLiteral(artifactCode), ADMIN_GRAPH));

		if (isIntroNanopub(np)) {
			IntroNanopub introNp = new IntroNanopub(np);
			metaStatements.add(vf.createStatement(np.getUri(), IS_INTRO_OF, introNp.getUser(), ADMIN_GRAPH));
			for (KeyDeclaration kc : introNp.getKeyDeclarations()) {
				metaStatements.add(vf.createStatement(np.getUri(), DECLARES_KEY, vf.createLiteral(kc.getPublicKeyString()), ADMIN_GRAPH));
			}
		}
	
		Calendar timestamp = null;
		try {
			timestamp = SimpleTimestampPattern.getCreationTime(np);
		} catch (IllegalArgumentException ex) {
			loadNoteToRepo(np.getUri(), "Illegal date/time");
		}
		if (timestamp != null) {
			metaStatements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(timestamp.getTime()), ADMIN_GRAPH));
		}

		for (IRI typeIri : NanopubUtils.getTypes(np)) {
			metaStatements.add(vf.createStatement(np.getUri(), HAS_NANOPUB_TYPE, typeIri, ADMIN_GRAPH));
		}
		String label = NanopubUtils.getLabel(np);
		if (label != null) {
			metaStatements.add(vf.createStatement(np.getUri(), RDFS.LABEL, vf.createLiteral(label), ADMIN_GRAPH));
		}
		for (IRI creatorIri : SimpleCreatorPattern.getCreators(np)) {
			metaStatements.add(vf.createStatement(np.getUri(), DCTERMS.CREATOR, creatorIri, ADMIN_GRAPH));
		}
		for (IRI authorIri : SimpleCreatorPattern.getAuthors(np)) {
			metaStatements.add(vf.createStatement(np.getUri(), SimpleCreatorPattern.PAV_AUTHOREDBY, authorIri, ADMIN_GRAPH));
		}

		// Any statements that express that the currently processed nanopub is already invalidated:
		List<Statement> invalidatingStatements = getInvalidatingStatements(np.getUri());

		metaStatements.addAll(invalidateStatements);

		List<Statement> allStatements = new ArrayList<>(nanopubStatements);
		allStatements.addAll(metaStatements);
		allStatements.addAll(invalidatingStatements);

		List<Statement> textStatements = new ArrayList<>(literalStatements);
		textStatements.addAll(metaStatements);
		textStatements.addAll(invalidatingStatements);

		if (timestamp != null) {
			if (new Date().getTime() - timestamp.getTimeInMillis() < THIRTY_DAYS) {
				loadNanopubToLatest(allStatements);
			}
		}

		loadNanopubToRepo(np.getUri(), metaStatements, "meta");
		loadNanopubToRepo(np.getUri(), allStatements, "full");
		loadNanopubToRepo(np.getUri(), textStatements, "text");
		loadNanopubToRepo(np.getUri(), allStatements, "pubkey_" + Utils.createHash(el.getPublicKeyString()));
		for (IRI typeIri : NanopubUtils.getTypes(np)) {
			// Exclude locally minted IRIs:
			if (typeIri.stringValue().startsWith(np.getUri().stringValue())) continue;
			if (!typeIri.stringValue().matches("https?://.*")) continue;
			loadNanopubToRepo(np.getUri(), allStatements, "type_" + Utils.createHash(typeIri));
		}
		for (IRI creatorIri : SimpleCreatorPattern.getCreators(np)) {
			// Exclude locally minted IRIs:
			if (creatorIri.stringValue().startsWith(np.getUri().stringValue())) continue;
			if (!creatorIri.stringValue().matches("https?://.*")) continue;
			loadNanopubToRepo(np.getUri(), allStatements, "user_" + Utils.createHash(creatorIri));
		}
		for (IRI authorIri : SimpleCreatorPattern.getAuthors(np)) {
			// Exclude locally minted IRIs:
			if (authorIri.stringValue().startsWith(np.getUri().stringValue())) continue;
			if (!authorIri.stringValue().matches("https?://.*")) continue;
			loadNanopubToRepo(np.getUri(), allStatements, "user_" + Utils.createHash(authorIri));
		}

		for (Statement st : invalidateStatements) {
			loadInvalidateStatements(np, el.getPublicKeyString(), st, pubkeyStatement);
		}
	}

	private static Long lastUpdateOfLatestRepo = null;
	private static long THIRTY_DAYS = 1000l * 60 * 60 * 24 * 30;
	private static long ONE_HOUR = 1000l * 60 * 60;

	public static void loadNanopubToLatest(List<Statement> statements) {
		boolean success = false;
		while (!success) {
			RepositoryConnection conn = QueryApplication.get().getRepoConnection("last30d");
			try {
				conn.begin(IsolationLevels.SERIALIZABLE);
				while (statements.size() > 1000) {
					conn.add(statements.subList(0, 1000));
					statements = statements.subList(1000, statements.size());
				}
				conn.add(statements);
				if (lastUpdateOfLatestRepo == null || new Date().getTime() - lastUpdateOfLatestRepo > ONE_HOUR) {
					//System.err.println("Remove old nanopubs...");
					Literal thirtyDaysAgo = vf.createLiteral(new Date(new Date().getTime() - THIRTY_DAYS));
					TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + ADMIN_GRAPH + "> { "
							+ "?np <" + DCTERMS.CREATED + "> ?date . "
							+ "filter ( ?date < ?thirtydaysago ) "
						+ "} }");
					q.setBinding("thirtydaysago",  thirtyDaysAgo);
					TupleQueryResult r = q.evaluate();
					while (r.hasNext()) {
						BindingSet b = r.next();
						IRI oldNpId = (IRI) b.getBinding("np").getValue();
						//System.err.println("Remove old nanopub: " + oldNpId);
						for (Value v : Utils.getObjectsForPattern(conn, ADMIN_GRAPH, oldNpId, HAS_GRAPH)) {
							// Remove all four nanopub graphs:
							conn.remove((Resource) null, (IRI) null, (Value) null, (IRI) v);
						}
						// Remove nanopubs in admin graphs:
						conn.remove(oldNpId, null, null, ADMIN_GRAPH);
						conn.remove(oldNpId, null, null, ADMIN_NETWORK_GRAPH);
					}
					lastUpdateOfLatestRepo = new Date().getTime();
				}
				conn.commit();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				conn.rollback();
			} finally {
				conn.close();
			}
			if (!success) {
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
			}
		}
	}

	public static void loadNanopubToRepo(IRI npId, List<Statement> statements, String repoName) {
		boolean success = false;
		while (!success) {
			RepositoryConnection conn = QueryApplication.get().getRepoConnection(repoName);
			try {
				conn.begin(IsolationLevels.SERIALIZABLE);
				if (Utils.getObjectForPattern(conn, ADMIN_GRAPH, npId, TripleStoreThread.HAS_LOAD_NUMBER) != null) {
					System.err.println("Already loaded: " + npId);
				} else {
					long count = Long.parseLong(Utils.getObjectForPattern(conn, ADMIN_GRAPH, TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_COUNT).stringValue());
					String checksum = Utils.getObjectForPattern(conn, ADMIN_GRAPH, TripleStoreThread.THIS_REPO_ID, TripleStoreThread.HAS_NANOPUB_CHECKSUM).stringValue();
					String newChecksum = NanopubUtils.updateXorChecksum(npId, checksum);
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
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				conn.rollback();
			} finally {
				conn.close();
			}
			if (!success) {
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
			}
		}
	}

	public static void loadInvalidateStatements(Nanopub thisNp, String thisPubkey, Statement invalidateStatement, Statement pubkeyStatement) {
		boolean success = false;
		while (!success) {
			List<RepositoryConnection> connections = new ArrayList<>();
			RepositoryConnection metaConn = QueryApplication.get().getRepoConnection("meta");
			try {
				IRI invalidatedNpId = (IRI) invalidateStatement.getObject();
				metaConn.begin(IsolationLevels.SERIALIZABLE);

				Value pubkeyValue = Utils.getObjectForPattern(metaConn, ADMIN_GRAPH, invalidatedNpId, HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY);
				if (pubkeyValue != null) {
					String pubkey = pubkeyValue.stringValue();

					if (!pubkey.equals(thisPubkey)) {
						//System.err.println("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for pubkey " + pubkey);
						RepositoryConnection pubkeyConn = QueryApplication.get().getRepoConnection("pubkey_" + Utils.createHash(pubkey));
						pubkeyConn.begin(IsolationLevels.SERIALIZABLE);
						connections.add(pubkeyConn);
						pubkeyConn.add(invalidateStatement);
						pubkeyConn.add(pubkeyStatement);
					}
	
					for (Value v : Utils.getObjectsForPattern(metaConn, ADMIN_GRAPH, invalidatedNpId, HAS_NANOPUB_TYPE)) {
						IRI typeIri = (IRI) v;
						// TODO Avoid calling getTypes and getCreators multiple times:
						if (!NanopubUtils.getTypes(thisNp).contains(typeIri)) {
							//System.err.println("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for type " + typeIri);
							RepositoryConnection typeConn = QueryApplication.get().getRepoConnection("type_" + Utils.createHash(typeIri));
							typeConn.begin(IsolationLevels.SERIALIZABLE);
							connections.add(typeConn);
							typeConn.add(invalidateStatement);
							typeConn.add(pubkeyStatement);
						}
					}
	
					for (Value v : Utils.getObjectsForPattern(metaConn, ADMIN_GRAPH, invalidatedNpId, DCTERMS.CREATOR)) {
						IRI creatorIri = (IRI) v;
						if (!SimpleCreatorPattern.getCreators(thisNp).contains(creatorIri)) {
							//System.err.println("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for user " + creatorIri);
							RepositoryConnection userConn = QueryApplication.get().getRepoConnection("user_" + Utils.createHash(creatorIri));
							userConn.begin(IsolationLevels.SERIALIZABLE);
							connections.add(userConn);
							userConn.add(invalidateStatement);
							userConn.add(pubkeyStatement);
						}
					}
				}

				metaConn.commit();
				// TODO handle case that some commits succeed and some fail
				for (RepositoryConnection c : connections) c.commit();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				metaConn.rollback();
				for (RepositoryConnection c : connections) c.rollback();
			} finally {
				metaConn.close();
				for (RepositoryConnection c : connections) c.close();
			}
			if (!success) {
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
			}
		}
	}

	public static List<Statement> getInvalidatingStatements(IRI npId) {
		List<Statement> invalidatingStatements = new ArrayList<>();
		boolean success = false;
		while (!success) {
			RepositoryConnection conn = QueryApplication.get().getRepoConnection("meta");
			try {
				conn.begin(IsolationLevels.SERIALIZABLE);

				TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + ADMIN_GRAPH + "> { "
						+ "?np <" + INVALIDATES + "> <" + npId + "> ; <" + HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . "
						+ "} }").evaluate();
				while (r.hasNext()) {
					BindingSet b = r.next();
					invalidatingStatements.add(vf.createStatement((IRI) b.getBinding("np").getValue(), INVALIDATES, npId, ADMIN_GRAPH));
					invalidatingStatements.add(vf.createStatement((IRI) b.getBinding("np").getValue(), HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, b.getBinding("pubkey").getValue(), ADMIN_GRAPH));
				}

				conn.commit();
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				conn.rollback();
			} finally {
				conn.close();
			}
			if (!success) {
				System.err.println("Retrying in 10 second...");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException x) {}
			}
		}
		return invalidatingStatements;
	}

	public static void loadNoteToRepo(Resource subj, String note) {
		boolean success = false;
		while (!success) {
			RepositoryConnection conn = QueryApplication.get().getAdminRepoConnection();
			try {
				List<Statement> statements = new ArrayList<>();
				statements.add(vf.createStatement(subj, NOTE, vf.createLiteral(note), ADMIN_GRAPH));
				conn.add(statements);
				success = true;
			} catch (Exception ex) {
				ex.printStackTrace();
			} finally {
				conn.close();
			}
			if (!success) {
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
	public static final IRI HAS_NANOPUB_TYPE = vf.createIRI("http://purl.org/nanopub/x/hasNanopubType");
	public static final IRI HAS_LITERAL = vf.createIRI("http://purl.org/nanopub/admin/hasLiteral");

}
