package com.knowledgepixels.query;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import net.trustyuri.TrustyUriUtils;
import net.trustyuri.rdf.RdfHasher;

public class Utils {

	private Utils() {}  // no instances allowed

	private static ValueFactory vf = SimpleValueFactory.getInstance();

	public static final IRI IS_HASH_OF = vf.createIRI("http://purl.org/nanopub/admin/isHashOf");
	public static final IRI HASH_PREFIX = vf.createIRI("http://purl.org/nanopub/admin/hash/");

	public static Map<String,Value> hashToObjMap;

	private static Map<String,Value> getHashToObjectMap() {
		if (hashToObjMap == null) {
			hashToObjMap = new HashMap<>();
			RepositoryConnection conn = QueryApplication.get().getRepositoryConnection("main");
			TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }");
			query.setBinding("g", NanopubLoader.ADMIN_GRAPH);
			query.setBinding("p", IS_HASH_OF);
			TupleQueryResult r = query.evaluate();
			while (r.hasNext()) {
				BindingSet b = r.next();
				String hash = b.getBinding("s").getValue().stringValue();
				hash = StringUtils.replace(hash, HASH_PREFIX.toString(), "");
				hashToObjMap.put(hash, b.getBinding("o").getValue());
			}
			conn.close();
		}
		return hashToObjMap;
	}

	public static Value getObjectForHash(String hash) {
		return getHashToObjectMap().get(hash);
	}

	public static String createHash(Object obj) {
		String s = obj.toString();

		MessageDigest md = RdfHasher.getDigest();
		md.update(s.getBytes());
		String hash = TrustyUriUtils.getBase64(md.digest());

		Value objV = getValue(obj);
		RepositoryConnection conn = QueryApplication.get().getRepositoryConnection("main");
		conn.add(vf.createStatement(vf.createIRI(HASH_PREFIX + hash), IS_HASH_OF, objV, NanopubLoader.ADMIN_GRAPH));
		conn.close();

		getHashToObjectMap().put(hash, objV);
		return hash;
	}

	private static Value getValue(Object obj) {
		if (obj instanceof Value) {
			return (Value) obj;
		} else {
			return vf.createLiteral(obj.toString());
		}
	}

	public static String getShortPubkeyName(String pubkey) {
		return pubkey.replaceFirst("^(.).{39}(.{5}).*$", "$1..$2..");
	}

}
