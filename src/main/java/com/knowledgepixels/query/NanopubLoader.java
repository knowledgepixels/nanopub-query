package com.knowledgepixels.query;

import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;

import net.trustyuri.TrustyUriUtils;

public class NanopubLoader {

	private NanopubLoader() {}  // no instances allowed

	public static void load(String nanopubUri) {
		Nanopub np = GetNanopub.get(nanopubUri);
		System.err.println("NP: " + np.getUri());
		// TODO
	}

	public static void process(RepositoryConnection conn, Nanopub np) throws RDF4JException {
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

//			statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_ASSERTION_URI, np.getAssertionUri(), ADMIN_HEADS_GRAPH));
//			statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PROVENANCE_URI, np.getProvenanceUri(), ADMIN_HEADS_GRAPH));
//			statements.add(vf.createStatement(np.getUri(), Nanopub.HAS_PUBINFO_URI, np.getPubinfoUri(), ADMIN_HEADS_GRAPH));

		if (hasValidSignature(el)) {
			statements.add(vf.createStatement(np.getUri(), HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, vf.createLiteral(el.getPublicKeyString()), ADMIN_GRAPH));
		}
		Calendar timestamp = null;
		try {
			timestamp = SimpleTimestampPattern.getCreationTime(np);
		} catch (IllegalArgumentException ex) {
			System.err.println("Illegal date/time for nanopublication " + np.getUri());
		}
		if (timestamp != null) {
			statements.add(vf.createStatement(np.getUri(), CREATION_DAY, vf.createIRI(NPA_DATE_PREFIX + getDayString(timestamp)), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), CREATION_MONTH, vf.createIRI(NPA_DATE_PREFIX + getMonthString(timestamp)), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), CREATION_YEAR, vf.createIRI(NPA_DATE_PREFIX + getYearString(timestamp)), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(timestamp.getTime()), ADMIN_GRAPH));
		} else {
			statements.add(vf.createStatement(np.getUri(), CREATION_DAY, vf.createIRI(NPA_DATE_PREFIX + "NONE"), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), CREATION_MONTH, vf.createIRI(NPA_DATE_PREFIX + "NONE"), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), CREATION_YEAR, vf.createIRI(NPA_DATE_PREFIX + "NONE"), ADMIN_GRAPH));
			statements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(""), ADMIN_GRAPH));
		}
		while (statements.size() > 1000) {
			conn.add(statements.subList(0, 1000));
			statements = statements.subList(1000, statements.size());
		}
		conn.add(statements);
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

	private static String getDayString(Calendar c) {
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		df.setTimeZone(timeZone);
		return df.format(c.getTime());
	}

	private static String getMonthString(Calendar c) {
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM");
		df.setTimeZone(timeZone);
		return df.format(c.getTime());
	}

	private static String getYearString(Calendar c) {
		SimpleDateFormat df = new SimpleDateFormat("yyyy");
		df.setTimeZone(timeZone);
		return df.format(c.getTime());
	}

	private static IRI getBaseTrustyUri(Value v) {
		if (!(v instanceof IRI)) return null;
		String s = v.stringValue();
		if (!s.matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}([^A-Za-z0-9\\\\-_].{0,43})?")) {
			return null;
		}
		return vf.createIRI(s.replaceFirst("^(.*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43})([^A-Za-z0-9\\\\-_].{0,43})?$", "$1"));
	}

	private static TimeZone timeZone = TimeZone.getTimeZone("UTC");
	private static ValueFactory vf = SimpleValueFactory.getInstance();

	public static final String NPA_DATE_PREFIX = "http://purl.org/nanopub/admin/date/";
	public static final IRI ADMIN_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/graph");
	public static final IRI ADMIN_NETWORK_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/networkGraph");
	public static final IRI ADMIN_HEADS_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/headsGraph");
	public static final IRI HAS_HEAD_GRAPH = vf.createIRI("http://purl.org/nanopub/admin/hasHeadGraph");
	public static final IRI CREATION_DAY = vf.createIRI("http://purl.org/nanopub/admin/creationDay");
	public static final IRI CREATION_MONTH = vf.createIRI("http://purl.org/nanopub/admin/creationMonth");
	public static final IRI CREATION_YEAR = vf.createIRI("http://purl.org/nanopub/admin/creationYear");
	public static final IRI NOTE = vf.createIRI("http://purl.org/nanopub/admin/note");
	public static final IRI HAS_SUB_IRI = vf.createIRI("http://purl.org/nanopub/admin/hasSubIri");
	public static final IRI REFERS_TO_NANOPUB = vf.createIRI("http://purl.org/nanopub/admin/refersToNanopub");
	public static final IRI HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY = vf.createIRI("http://purl.org/nanopub/admin/hasValidSignatureForPublicKey");

}
