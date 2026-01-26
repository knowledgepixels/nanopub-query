package com.knowledgepixels.query;

import net.trustyuri.TrustyUriUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.*;
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
import org.nanopub.vocabulary.NP;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.nanopub.vocabulary.PAV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * Utility class for loading nanopublications into the database.
 */
public class NanopubLoader {

    private static HttpClient httpClient;
    private static final ThreadPoolExecutor loadingPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
    private Nanopub np;
    private NanopubSignatureElement el = null;
    private List<Statement> metaStatements = new ArrayList<>();
    private List<Statement> nanopubStatements = new ArrayList<>();
    private List<Statement> literalStatements = new ArrayList<>();
    private List<Statement> invalidateStatements = new ArrayList<>();
    private List<Statement> textStatements, allStatements;
    private Calendar timestamp = null;
    private Statement pubkeyStatement, pubkeyStatementX;
    private List<String> notes = new ArrayList<>();
    private boolean aborted = false;
    private static final Logger log = LoggerFactory.getLogger(NanopubLoader.class);


    NanopubLoader(Nanopub np, long counter) {
        this.np = np;
        if (counter >= 0) {
            log.info("Loading {}: {}", counter, np.getUri());
        } else {
            log.info("Loading: {}", np.getUri());
        }

        // TODO Ensure proper synchronization and DB rollbacks

        // TODO Check for null characters ("\0"), which can cause problems in Virtuoso.

        String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
        if (!np.getHeadUri().toString().contains(ac) || !np.getAssertionUri().toString().contains(ac) || !np.getProvenanceUri().toString().contains(ac) || !np.getPubinfoUri().toString().contains(ac)) {
            notes.add("could not load nanopub as not all graphs contained the artifact code");
            aborted = true;
            return;
        }

        try {
            el = SignatureUtils.getSignatureElement(np);
        } catch (MalformedCryptoElementException ex) {
            notes.add("Signature error");
        }
        if (!hasValidSignature(el)) {
            aborted = true;
            return;
        }

        pubkeyStatement = vf.createStatement(np.getUri(), NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, vf.createLiteral(el.getPublicKeyString()), NPA.GRAPH);
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasValidSignatureForPublicKey, FULL_PUBKEY, npa:graph, meta, full pubkey if signature is valid
        metaStatements.add(pubkeyStatement);
        pubkeyStatementX = vf.createStatement(np.getUri(), NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, vf.createLiteral(Utils.createHash(el.getPublicKeyString())), NPA.GRAPH);
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasValidSignatureForPublicKeyHash, PUBKEY_HASH, npa:graph, meta, hex-encoded SHA256 hash if signature is valid
        metaStatements.add(pubkeyStatementX);

        if (el.getSigners().size() == 1) {  // > 1 is deprecated
            metaStatements.add(vf.createStatement(np.getUri(), NPX.SIGNED_BY, el.getSigners().iterator().next(), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:signedBy, SIGNER, npa:graph, meta, ID of signer
        }

        Set<IRI> subIris = new HashSet<>();
        Set<IRI> otherNps = new HashSet<>();
        Set<IRI> invalidated = new HashSet<>();
        Set<IRI> retracted = new HashSet<>();
        Set<IRI> superseded = new HashSet<>();
        String combinedLiterals = "";
        for (Statement st : NanopubUtils.getStatements(np)) {
            nanopubStatements.add(st);

            if (st.getPredicate().toString().contains(ac)) {
                subIris.add(st.getPredicate());
            } else {
                IRI b = getBaseTrustyUri(st.getPredicate());
                if (b != null) otherNps.add(b);
            }
            if (st.getPredicate().equals(NPX.RETRACTS) && st.getObject() instanceof IRI) {
                retracted.add((IRI) st.getObject());
            }
            if (st.getPredicate().equals(NPX.INVALIDATES) && st.getObject() instanceof IRI) {
                invalidated.add((IRI) st.getObject());
            }
            if (st.getSubject().equals(np.getUri()) && st.getObject() instanceof IRI) {
                if (st.getPredicate().equals(NPX.SUPERSEDES)) {
                    superseded.add((IRI) st.getObject());
                }
                if (st.getObject().toString().matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}")) {
                    metaStatements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), NPA.NETWORK_GRAPH));
                    // @ADMIN-TRIPLE-TABLE@ NANOPUB1, RELATION, NANOPUB2, npa:networkGraph, meta, any inter-nanopub relation found in NANOPUB1
                }
                if (st.getContext().equals(np.getPubinfoUri())) {
                    if (st.getPredicate().equals(NPX.INTRODUCES) || st.getPredicate().equals(NPX.DESCRIBES) || st.getPredicate().equals(NPX.EMBEDS)) {
                        metaStatements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), NPA.GRAPH));
                        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:introduces, THING, npa:graph, meta, when such a triple is present in pubinfo of NANOPUB
                        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:describes, THING, npa:graph, meta, when such a triple is present in pubinfo of NANOPUB
                        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:embeds, THING, npa:graph, meta, when such a triple is present in pubinfo of NANOPUB
                    }
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
                combinedLiterals += st.getObject().stringValue().replaceAll("\\s+", " ") + "\n";
//				if (st.getSubject().equals(np.getUri()) && !st.getSubject().equals(HAS_FILTER_LITERAL)) {
//					literalStatements.add(vf.createStatement(np.getUri(), st.getPredicate(), st.getObject(), LITERAL_GRAPH));
//				} else {
//					literalStatements.add(vf.createStatement(np.getUri(), HAS_LITERAL, st.getObject(), LITERAL_GRAPH));
//				}
            }
        }
        subIris.remove(np.getUri());
        subIris.remove(np.getAssertionUri());
        subIris.remove(np.getProvenanceUri());
        subIris.remove(np.getPubinfoUri());
        for (IRI i : subIris) {
            metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_SUB_IRI, i, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasSubIri, SUB_IRI, npa:graph, meta, for any IRI minted in the namespace of the NANOPUB
        }
        for (IRI i : otherNps) {
            metaStatements.add(vf.createStatement(np.getUri(), NPA.REFERS_TO_NANOPUB, i, NPA.NETWORK_GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB1, npa:refersToNanopub, NANOPUB2, npa:networkGraph, meta, generic inter-nanopub relation
        }
        for (IRI i : invalidated) {
            invalidateStatements.add(vf.createStatement(np.getUri(), NPX.INVALIDATES, i, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:invalidates, INVALIDATED_NANOPUB, npa:graph, meta, if the NANOPUB retracts or supersedes another nanopub
        }
        for (IRI i : retracted) {
            invalidateStatements.add(vf.createStatement(np.getUri(), NPX.INVALIDATES, i, NPA.GRAPH));
            metaStatements.add(vf.createStatement(np.getUri(), NPX.RETRACTS, i, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:retracts, RETRACTED_NANOPUB, npa:graph, meta, if the NANOPUB retracts another nanopub
        }
        for (IRI i : superseded) {
            invalidateStatements.add(vf.createStatement(np.getUri(), NPX.INVALIDATES, i, NPA.GRAPH));
            metaStatements.add(vf.createStatement(np.getUri(), NPX.SUPERSEDES, i, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:supersedes, SUPERSEDED_NANOPUB, npa:graph, meta, if the NANOPUB supersedes another nanopub
        }

        metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_HEAD_GRAPH, np.getHeadUri(), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasHeadGraph, HEAD_GRAPH, npa:graph, meta, direct link to the head graph of the NANOPUB
        metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_GRAPH, np.getHeadUri(), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasGraph, GRAPH, npa:graph, meta, generic link to all four graphs of the given NANOPUB
        metaStatements.add(vf.createStatement(np.getUri(), NP.HAS_ASSERTION, np.getAssertionUri(), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, np:hasAssertion, ASSERTION_GRAPH, npa:graph, meta, direct link to the assertion graph of the NANOPUB
        metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_GRAPH, np.getAssertionUri(), NPA.GRAPH));
        metaStatements.add(vf.createStatement(np.getUri(), NP.HAS_PROVENANCE, np.getProvenanceUri(), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, np:hasProvenance, PROVENANCE_GRAPH, npa:graph, meta, direct link to the provenance graph of the NANOPUB
        metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_GRAPH, np.getProvenanceUri(), NPA.GRAPH));
        metaStatements.add(vf.createStatement(np.getUri(), NP.HAS_PUBINFO, np.getPubinfoUri(), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, np:hasPublicationInfo, PUBINFO_GRAPH, npa:graph, meta, direct link to the pubinfo graph of the NANOPUB
        metaStatements.add(vf.createStatement(np.getUri(), NPA.HAS_GRAPH, np.getPubinfoUri(), NPA.GRAPH));

        String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        metaStatements.add(vf.createStatement(np.getUri(), NPA.ARTIFACT_CODE, vf.createLiteral(artifactCode), NPA.GRAPH));
        // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:artifactCode, ARTIFACT_CODE, npa:graph, meta, artifact code starting with 'RA...'

        if (isIntroNanopub(np)) {
            IntroNanopub introNp = new IntroNanopub(np);
            metaStatements.add(vf.createStatement(np.getUri(), NPA.IS_INTRODUCTION_OF, introNp.getUser(), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:isIntroductionOf, AGENT, npa:graph, meta, linking intro nanopub to the agent it is introducing
            for (KeyDeclaration kc : introNp.getKeyDeclarations()) {
                metaStatements.add(vf.createStatement(np.getUri(), NPA.DECLARES_PUBKEY, vf.createLiteral(kc.getPublicKeyString()), NPA.GRAPH));
                // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:declaresPubkey, FULL_PUBKEY, npa:graph, meta, full pubkey declared by the given intro NANOPUB
            }
        }

        try {
            timestamp = SimpleTimestampPattern.getCreationTime(np);
        } catch (IllegalArgumentException ex) {
            notes.add("Illegal date/time");
        }
        if (timestamp != null) {
            metaStatements.add(vf.createStatement(np.getUri(), DCTERMS.CREATED, vf.createLiteral(timestamp.getTime()), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, dct:created, CREATION_DATE, npa:graph, meta, normalized creation timestamp
        }

        String literalFilter = "_pubkey_" + Utils.createHash(el.getPublicKeyString());
        for (IRI typeIri : NanopubUtils.getTypes(np)) {
            metaStatements.add(vf.createStatement(np.getUri(), NPX.HAS_NANOPUB_TYPE, typeIri, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npx:hasNanopubType, NANOPUB_TYPE, npa:graph, meta, type of NANOPUB
            literalFilter += " _type_" + Utils.createHash(typeIri);
        }
        String label = NanopubUtils.getLabel(np);
        if (label != null) {
            metaStatements.add(vf.createStatement(np.getUri(), RDFS.LABEL, vf.createLiteral(label), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, rdfs:label, LABEL, npa:graph, meta, label of NANOPUB
        }
        String description = NanopubUtils.getDescription(np);
        if (description != null) {
            metaStatements.add(vf.createStatement(np.getUri(), DCTERMS.DESCRIPTION, vf.createLiteral(description), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, dct:description, LABEL, npa:graph, meta, description of NANOPUB
        }
        for (IRI creatorIri : SimpleCreatorPattern.getCreators(np)) {
            metaStatements.add(vf.createStatement(np.getUri(), DCTERMS.CREATOR, creatorIri, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, dct:creator, CREATOR, npa:graph, meta, creator of NANOPUB (can be several)
        }
        for (IRI authorIri : SimpleCreatorPattern.getAuthors(np)) {
            metaStatements.add(vf.createStatement(np.getUri(), PAV.AUTHORED_BY, authorIri, NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, pav:authoredBy, AUTHOR, npa:graph, meta, author of NANOPUB (can be several)
        }

        if (!combinedLiterals.isEmpty()) {
            literalStatements.add(vf.createStatement(np.getUri(), NPA.HAS_FILTER_LITERAL, vf.createLiteral(literalFilter + "\n" + combinedLiterals), NPA.GRAPH));
            // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasFilterLiteral, FILTER_LITERAL, npa:graph, literal, auxiliary literal for filtering by type and pubkey in text repo
        }

        // Any statements that express that the currently processed nanopub is already invalidated:
        List<Statement> invalidatingStatements = getInvalidatingStatements(np.getUri());

        metaStatements.addAll(invalidateStatements);

        allStatements = new ArrayList<>(nanopubStatements);
        allStatements.addAll(metaStatements);
        allStatements.addAll(invalidatingStatements);

        textStatements = new ArrayList<>(literalStatements);
        textStatements.addAll(metaStatements);
        textStatements.addAll(invalidatingStatements);
    }

    /**
     * Get the HTTP client used for fetching nanopublications.
     *
     * @return the HTTP client
     */
    static HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClientBuilder.create().setDefaultRequestConfig(Utils.getHttpRequestConfig()).build();
        }
        return httpClient;
    }

    /**
     * Load the given nanopublication into the database.
     *
     * @param nanopubUri Nanopublication identifier (URI)
     */
    public static void load(String nanopubUri) {
        if (isNanopubLoaded(nanopubUri)) {
            log.info("Already loaded: {}", nanopubUri);
        } else {
            Nanopub np = GetNanopub.get(nanopubUri, getHttpClient());
            load(np, -1);
        }
    }

    /**
     * Load a nanopub into the database.
     *
     * @param np      the nanopub to load
     * @param counter the load counter, only used for logging (or -1 if not known)
     * @throws RDF4JException if the loading fails
     */
    public static void load(Nanopub np, long counter) throws RDF4JException {
        NanopubLoader loader = new NanopubLoader(np, counter);
        loader.executeLoading();
    }

    @GeneratedFlagForDependentElements
    private void executeLoading() {
        var runningTasks = new ArrayList<Future<?>>();
        Consumer<Runnable> runTask = t -> runningTasks.add(loadingPool.submit(t));

        for (String note : notes) {
            loadNoteToRepo(np.getUri(), note);
        }

        if (!aborted) {
            // Submit all tasks except the "meta" task
            if (timestamp != null) {
                if (new Date().getTime() - timestamp.getTimeInMillis() < THIRTY_DAYS) {
                    runTask.accept(() -> loadNanopubToLatest(allStatements));
                }
            }

            runTask.accept(() -> loadNanopubToRepo(np.getUri(), textStatements, "text"));
            runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "full"));
            // Note: "meta" task is deferred until all other tasks complete successfully

            runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "pubkey_" + Utils.createHash(el.getPublicKeyString())));
            //		loadNanopubToRepo(np.getUri(), textStatements, "text-pubkey_" + Utils.createHash(el.getPublicKeyString()));
            for (IRI typeIri : NanopubUtils.getTypes(np)) {
                // Exclude locally minted IRIs:
                if (typeIri.stringValue().startsWith(np.getUri().stringValue())) continue;
                if (!typeIri.stringValue().matches("https?://.*")) continue;
                runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "type_" + Utils.createHash(typeIri)));
                //			loadNanopubToRepo(np.getUri(), textStatements, "text-type_" + Utils.createHash(typeIri));
            }
            //		for (IRI creatorIri : SimpleCreatorPattern.getCreators(np)) {
            //			// Exclude locally minted IRIs:
            //			if (creatorIri.stringValue().startsWith(np.getUri().stringValue())) continue;
            //			if (!creatorIri.stringValue().matches("https?://.*")) continue;
            //			loadNanopubToRepo(np.getUri(), allStatements, "user_" + Utils.createHash(creatorIri));
            //			loadNanopubToRepo(np.getUri(), textStatements, "text-user_" + Utils.createHash(creatorIri));
            //		}
            //		for (IRI authorIri : SimpleCreatorPattern.getAuthors(np)) {
            //			// Exclude locally minted IRIs:
            //			if (authorIri.stringValue().startsWith(np.getUri().stringValue())) continue;
            //			if (!authorIri.stringValue().matches("https?://.*")) continue;
            //			loadNanopubToRepo(np.getUri(), allStatements, "user_" + Utils.createHash(authorIri));
            //			loadNanopubToRepo(np.getUri(), textStatements, "text-user_" + Utils.createHash(authorIri));
            //		}

            for (Statement st : invalidateStatements) {
                runTask.accept(() -> loadInvalidateStatements(np, el.getPublicKeyString(), st, pubkeyStatement, pubkeyStatementX));
            }

            // Wait for all non-meta tasks to complete successfully before submitting the meta task
            for (var task : runningTasks) {
                try {
                    task.get();
                } catch (ExecutionException | InterruptedException ex) {
                    throw new RuntimeException("Error in nanopub loading thread", ex.getCause());
                }
            }

            // Now submit and wait for the "meta" task after all other tasks have completed successfully
            Future<?> metaTask = loadingPool.submit(() -> loadNanopubToRepo(np.getUri(), metaStatements, "meta"));
            try {
                metaTask.get();
            } catch (ExecutionException | InterruptedException ex) {
                throw new RuntimeException("Error in nanopub loading thread (meta task)", ex.getCause());
            }
        }
    }

    private static Long lastUpdateOfLatestRepo = null;
    private static long THIRTY_DAYS = 1000L * 60 * 60 * 24 * 30;
    private static long ONE_HOUR = 1000L * 60 * 60;

    @GeneratedFlagForDependentElements
    private static void loadNanopubToLatest(List<Statement> statements) {
        boolean success = false;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getRepoConnection("last30d");
            try (conn) {
                // Read committed, because deleting old nanopubs is idempotent. Inserts do not collide
                // with deletes, because we are not inserting old nanopubs.
                conn.begin(IsolationLevels.READ_COMMITTED);
                conn.add(statements);
                if (lastUpdateOfLatestRepo == null || new Date().getTime() - lastUpdateOfLatestRepo > ONE_HOUR) {
                    log.trace("Remove old nanopubs...");
                    Literal thirtyDaysAgo = vf.createLiteral(new Date(new Date().getTime() - THIRTY_DAYS));
                    TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + NPA.GRAPH + "> { " + "?np <" + DCTERMS.CREATED + "> ?date . " + "filter ( ?date < ?thirtydaysago ) " + "} }");
                    q.setBinding("thirtydaysago", thirtyDaysAgo);
                    try (TupleQueryResult r = q.evaluate()) {
                        while (r.hasNext()) {
                            BindingSet b = r.next();
                            IRI oldNpId = (IRI) b.getBinding("np").getValue();
                            log.trace("Remove old nanopub: {}", oldNpId);
                            for (Value v : Utils.getObjectsForPattern(conn, NPA.GRAPH, oldNpId, NPA.HAS_GRAPH)) {
                                // Remove all four nanopub graphs:
                                conn.remove((Resource) null, (IRI) null, (Value) null, (IRI) v);
                            }
                            // Remove nanopubs in admin graphs:
                            conn.remove(oldNpId, null, null, NPA.GRAPH);
                            conn.remove(oldNpId, null, null, NPA.NETWORK_GRAPH);
                        }
                    }
                    lastUpdateOfLatestRepo = new Date().getTime();
                }
                conn.commit();
                success = true;
            } catch (Exception ex) {
                log.info("Could not get environment variable", ex);
                if (conn.isActive()) conn.rollback();
            }
            if (!success) {
                log.info("Retrying in 10 second...");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException x) {
                }
            }
        }
    }

    @GeneratedFlagForDependentElements
    private static void loadNanopubToRepo(IRI npId, List<Statement> statements, String repoName) {
        boolean success = false;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName);
            try (conn) {
                // Serializable, because write skew would cause the chain of hashes to be broken.
                // The inserts must be done serially.
                conn.begin(IsolationLevels.SERIALIZABLE);
                var repoStatus = fetchRepoStatus(conn, npId);
                if (repoStatus.isLoaded) {
                    log.info("Already loaded: {}", npId);
                } else {
                    String newChecksum = NanopubUtils.updateXorChecksum(npId, repoStatus.checksum);
                    conn.remove(NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, null, NPA.GRAPH);
                    conn.remove(NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM, null, NPA.GRAPH);
                    conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, vf.createLiteral(repoStatus.count + 1), NPA.GRAPH);
                    // @ADMIN-TRIPLE-TABLE@ REPO, npa:hasNanopubCount, NANOPUB_COUNT, npa:graph, admin, number of nanopubs loaded
                    conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM, vf.createLiteral(newChecksum), NPA.GRAPH);
                    // @ADMIN-TRIPLE-TABLE@ REPO, npa:hasNanopubChecksum, NANOPUB_CHECKSUM, npa:graph, admin, checksum of all loaded nanopubs (order-independent XOR checksum on trusty URIs in Base64 notation)
                    conn.add(npId, NPA.HAS_LOAD_NUMBER, vf.createLiteral(repoStatus.count), NPA.GRAPH);
                    // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasLoadNumber, LOAD_NUMBER, npa:graph, admin, the sequential number at which this NANOPUB was loaded
                    conn.add(npId, NPA.HAS_LOAD_CHECKSUM, vf.createLiteral(newChecksum), NPA.GRAPH);
                    // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasLoadChecksum, LOAD_CHECKSUM, npa:graph, admin, the checksum of all loaded nanopubs after loading the given NANOPUB
                    conn.add(npId, NPA.HAS_LOAD_TIMESTAMP, vf.createLiteral(new Date()), NPA.GRAPH);
                    // @ADMIN-TRIPLE-TABLE@ NANOPUB, npa:hasLoadTimestamp, LOAD_TIMESTAMP, npa:graph, admin, the time point at which this NANOPUB was loaded
                    conn.add(statements);
                }
                conn.commit();
                success = true;
            } catch (Exception ex) {
                log.info("Could no load nanopub to repo. ", ex);
                if (conn.isActive()) conn.rollback();
            }
            if (!success) {
                log.info("Retrying in 10 second...");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException x) {
                }
            }
        }
    }

    private record RepoStatus(boolean isLoaded, long count, String checksum) {
    }

    /**
     * To execute before loading a nanopub: check if the nanopub is already loaded and what is the
     * current load counter and checksum. This effectively batches three queries into one.
     * This method must be called from within a transaction.
     *
     * @param conn repo connection
     * @param npId nanopub ID
     * @return the current status
     */
    @GeneratedFlagForDependentElements
    private static RepoStatus fetchRepoStatus(RepositoryConnection conn, IRI npId) {
        var result = conn.prepareTupleQuery(QueryLanguage.SPARQL, REPO_STATUS_QUERY_TEMPLATE.formatted(npId)).evaluate();
        try (result) {
            if (!result.hasNext()) {
                // This may happen if the repo was created, but is completely empty.
                return new RepoStatus(false, 0, NanopubUtils.INIT_CHECKSUM);
            }
            var row = result.next();
            return new RepoStatus(row.hasBinding("loadNumber"), Long.parseLong(row.getBinding("count").getValue().stringValue()), row.getBinding("checksum").getValue().stringValue());
        }
    }

    @GeneratedFlagForDependentElements
    private static void loadInvalidateStatements(Nanopub thisNp, String thisPubkey, Statement invalidateStatement, Statement pubkeyStatement, Statement pubkeyStatementX) {
        boolean success = false;
        while (!success) {
            List<RepositoryConnection> connections = new ArrayList<>();
            RepositoryConnection metaConn = TripleStore.get().getRepoConnection("meta");
            try {
                IRI invalidatedNpId = (IRI) invalidateStatement.getObject();
                // Basic isolation because here we only read append-only data.
                metaConn.begin(IsolationLevels.READ_COMMITTED);

                Value pubkeyValue = Utils.getObjectForPattern(metaConn, NPA.GRAPH, invalidatedNpId, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY);
                if (pubkeyValue != null) {
                    String pubkey = pubkeyValue.stringValue();

                    if (!pubkey.equals(thisPubkey)) {
                        //log.info("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for pubkey " + pubkey);
                        connections.add(loadStatements("pubkey_" + Utils.createHash(pubkey), invalidateStatement, pubkeyStatement, pubkeyStatementX));
//						connections.add(loadStatements("text-pubkey_" + Utils.createHash(pubkey), invalidateStatement, pubkeyStatement));
                    }

                    for (Value v : Utils.getObjectsForPattern(metaConn, NPA.GRAPH, invalidatedNpId, NPX.HAS_NANOPUB_TYPE)) {
                        IRI typeIri = (IRI) v;
                        // TODO Avoid calling getTypes and getCreators multiple times:
                        if (!NanopubUtils.getTypes(thisNp).contains(typeIri)) {
                            //log.info("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for type " + typeIri);
                            connections.add(loadStatements("type_" + Utils.createHash(typeIri), invalidateStatement, pubkeyStatement, pubkeyStatementX));
//							connections.add(loadStatements("text-type_" + Utils.createHash(typeIri), invalidateStatement, pubkeyStatement));
                        }
                    }

//					for (Value v : Utils.getObjectsForPattern(metaConn, NPA.GRAPH, invalidatedNpId, DCTERMS.CREATOR)) {
//						IRI creatorIri = (IRI) v;
//						if (!SimpleCreatorPattern.getCreators(thisNp).contains(creatorIri)) {
//							//log.info("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for user " + creatorIri);
//							connections.add(loadStatements("user_" + Utils.createHash(creatorIri), invalidateStatement, pubkeyStatement));
//							connections.add(loadStatements("text-user_" + Utils.createHash(creatorIri), invalidateStatement, pubkeyStatement));
//						}
//					}
                }

                metaConn.commit();
                // TODO handle case that some commits succeed and some fail
                for (RepositoryConnection c : connections) c.commit();
                success = true;
            } catch (Exception ex) {
                log.info("Could no load invalidate statements. ", ex);
                if (metaConn.isActive()) metaConn.rollback();
                for (RepositoryConnection c : connections) {
                    if (c.isActive()) c.rollback();
                }
            } finally {
                metaConn.close();
                for (RepositoryConnection c : connections) c.close();
            }
            if (!success) {
                log.info("Retrying in 10 second...");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException x) {
                }
            }
        }
    }

    @GeneratedFlagForDependentElements
    private static RepositoryConnection loadStatements(String repoName, Statement... statements) {
        RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName);
        // Basic isolation: we only append new statements
        conn.begin(IsolationLevels.READ_COMMITTED);
        for (Statement st : statements) {
            conn.add(st);
        }
        return conn;
    }

    @GeneratedFlagForDependentElements
    static List<Statement> getInvalidatingStatements(IRI npId) {
        List<Statement> invalidatingStatements = new ArrayList<>();
        boolean success = false;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getRepoConnection("meta");
            try (conn) {
                // Basic isolation because here we only read append-only data.
                conn.begin(IsolationLevels.READ_COMMITTED);

                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + NPA.GRAPH + "> { " + "?np <" + NPX.INVALIDATES + "> <" + npId + "> ; <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . " + "} }").evaluate();
                try (r) {
                    while (r.hasNext()) {
                        BindingSet b = r.next();
                        invalidatingStatements.add(vf.createStatement((IRI) b.getBinding("np").getValue(), NPX.INVALIDATES, npId, NPA.GRAPH));
                        invalidatingStatements.add(vf.createStatement((IRI) b.getBinding("np").getValue(), NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, b.getBinding("pubkey").getValue(), NPA.GRAPH));
                    }
                }
                conn.commit();
                success = true;
            } catch (Exception ex) {
                log.info("Could no load invalidating statements. ", ex);
                if (conn.isActive()) conn.rollback();
            }
            if (!success) {
                log.info("Retrying in 10 second...");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException x) {
                }
            }
        }
        return invalidatingStatements;
    }

    @GeneratedFlagForDependentElements
    private static void loadNoteToRepo(Resource subj, String note) {
        boolean success = false;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getAdminRepoConnection();
            try (conn) {
                List<Statement> statements = new ArrayList<>();
                statements.add(vf.createStatement(subj, NPA.NOTE, vf.createLiteral(note), NPA.GRAPH));
                conn.add(statements);
                success = true;
            } catch (Exception ex) {
                log.info("Could no load note to repo. ", ex);
            }
            if (!success) {
                log.info("Retrying in 10 second...");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException x) {
                }
            }
        }
    }

    static boolean hasValidSignature(NanopubSignatureElement el) {
        try {
            if (el != null && SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
                return true;
            }
        } catch (GeneralSecurityException ex) {
            log.info("Error for signature element {}", el.getUri());
            log.info("Error", ex);
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
            if (st.getPredicate().equals(NPX.DECLARED_BY)) return true;
        }
        return false;
    }

    /**
     * Check if a nanopub is already loaded in the admin graph.
     *
     * @param npId the nanopub ID
     * @return true if the nanopub is loaded, false otherwise
     */
    @GeneratedFlagForDependentElements
    static boolean isNanopubLoaded(String npId) {
        boolean loaded = false;
        RepositoryConnection conn = TripleStore.get().getRepoConnection("meta");
        try (conn) {
            if (Utils.getObjectForPattern(conn, NPA.GRAPH, vf.createIRI(npId), NPA.HAS_LOAD_NUMBER) != null) {
                loaded = true;
            }
        } catch (Exception ex) {
            log.info("Could no load nanopub. ", ex);
        }
        return loaded;
    }

    private static ValueFactory vf = SimpleValueFactory.getInstance();

    // TODO remove the constants and use the ones from the nanopub library instead

    /**
     * Template for the query that fetches the status of a repository.
     */
    // Template for .fetchRepoStatus
    private static final String REPO_STATUS_QUERY_TEMPLATE = """
            SELECT * { graph <%s> {
              OPTIONAL { <%s> <%s> ?loadNumber . }
              <%s> <%s> ?count ;
                   <%s> ?checksum .
            } }
            """.formatted(NPA.GRAPH, "%s", NPA.HAS_LOAD_NUMBER, NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, NPA.HAS_NANOPUB_CHECKSUM);
}
