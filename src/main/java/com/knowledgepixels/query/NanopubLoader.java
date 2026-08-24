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
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.SimpleCreatorPattern;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.server.NanopubServerUtils;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.vocabulary.NP;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.nanopub.vocabulary.PAV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Utility class for loading nanopublications into the database.
 */
public class NanopubLoader {

    private static HttpClient httpClient;
    private static final ThreadPoolExecutor loadingPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

    /**
     * One write lock per repo, held for the whole read-modify-write of that repo's
     * nanopub count / XOR checksum chain.
     *
     * <p>This replaces {@code IsolationLevels.SERIALIZABLE} as the mechanism protecting the
     * chain, and it is a strictly local substitution: this process is the only writer of
     * those triples, so in-process mutual exclusion gives exactly what the serializable
     * transaction was buying. The chain invariant is unchanged.
     *
     * <p>Why bother: in RDF4J 5.3.x, {@code SailSourceBranch.derivedFromSerializable} sets a
     * branch-level {@code serializable} sink the first time <em>any</em> transaction on that
     * branch asks for a SERIALIZABLE-compatible level, and clears it only when the branch
     * closes. While it is set, every dataset opened on that branch — including ordinary read
     * queries at any isolation — is wrapped in an {@code ObservingSailDataset}, which on close
     * runs {@code compressChanges -> prepare -> sinkObserved -> Changeset.observeAll} while
     * holding the branch semaphore. Hashing observed {@code SimpleStatementPattern}s there is
     * expensive ({@code LmdbIRI.hashCode} goes to a synchronized {@code ValueStore} map), so
     * one writer can stall every reader of the repo.
     *
     * <p>Measured on kpxl 2026-08-05: a thread dump showed 171 threads parked on a single
     * {@code ReentrantLock} in {@code derivedFromSerializable} for the {@code full} repo, one
     * thread inside {@code observeAll}, and the whole servlet container unable to answer even
     * a 404 for a nonexistent repo. Dropping to SNAPSHOT leaves {@code serializable} null, so
     * no {@code ObservingSailDataset} is created, {@code observed} stays null and
     * {@code sinkObserved} early-returns.
     *
     * <p>Keyed by repo name, so writers to different repos never contend. Calls are never
     * nested across repos — the invalidator paths loop and call one repo at a time — so there
     * is no lock-ordering hazard.
     *
     * <p>SERIALIZABLE is avoided codebase-wide, not just on this path: besides the
     * branch-tainting above, the LMDB native-heap corruption behind the rdf4j crash loop
     * (rdf4j #5970) is reported upstream to occur only under SERIALIZABLE transactions.
     * Every write path in this process is single-writer (in-process locks, synchronized
     * singletons, or single scheduled threads), so SNAPSHOT (for rewrites of existing
     * state) or READ_COMMITTED (for append-only writes) is always sufficient.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> repoWriteLocks = new ConcurrentHashMap<>();

    static ReentrantLock repoWriteLock(String repoName) {
        return repoWriteLocks.computeIfAbsent(repoName, k -> new ReentrantLock());
    }

    /**
     * Cached count of nanopubs ever loaded into the {@code meta} repo. Maintained
     * for {@link MainVerticle}'s {@code Nanopub-Query-Loaded-Nanopub-Count}
     * response header. Mirrors the persisted {@code npa:hasNanopubCount} triple
     * that {@link #loadNanopubToRepo} maintains; invalidations don't decrement
     * (they're recorded as separate {@code npx:invalidates} markers), so this
     * is a cumulative count including superseded/retracted nanopubs — matching
     * the registry-side {@code Nanopub-Registry-Nanopub-Count} semantics.
     *
     * <p>The {@code meta} repo (not {@code full}) is the source because the meta
     * task is submitted only after all other per-nanopub tasks succeed (see
     * {@link #executeLoading}), making it the authoritative "fully completed
     * loads" indicator. The {@code full} repo is feature-flagged and may be
     * disabled, in which case it cannot be the source. Populated lazily on first
     * read; bumped post-commit on each fresh meta load.
     */
    static volatile Long loadedNanopubCount = null;

    /**
     * Cached checksum of nanopubs ever loaded into the {@code meta} repo.
     * Maintained for {@link MainVerticle}'s
     * {@code Nanopub-Query-Loaded-Nanopub-Checksum} response header. Mirrors the
     * persisted {@code npa:hasNanopubChecksum} triple that {@link #loadNanopubToRepo}
     * maintains — an order-independent XOR over the trusty URIs of all loaded
     * nanopubs, Base64-encoded. Sourced from {@code meta} for the same reasons
     * as {@link #loadedNanopubCount}; the two fields are bumped together so the
     * count and checksum always describe the same point in the load sequence.
     */
    static volatile String loadedNanopubChecksum = null;

    /**
     * Retry budget for the five (with #71 merged: six) structurally identical
     * retry loops in this file. Previously the shape was flat {@code 10 s × 30} —
     * five minutes of constant hammering at RDF4J that did not help a slow server.
     * The new shape is bounded exponential backoff with ±50 % jitter:
     * {@code base = 1, 2, 4, 8, 16, 32, 60, 60 s} for attempts 1…8, each perturbed
     * by up to half its base value. Jitter prevents the 4-thread loadingPool from
     * retrying in lock-step after a shared RDF4J failure (GC pause / overload spike).
     * Worst-case wall time per failing task drops from ~35 min (post-change-1
     * timeouts × 30 flat retries) to ~11 min (8 retries × 60 s timeout + backoff
     * sleeps). This is the figure that sets the circuit-breaker trip time in
     * {@link JellyNanopubLoader}.
     */
    private static final int MAX_RETRIES = 8;
    private static final long[] BACKOFF_BASE_MS =
            {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L};

    /**
     * Returns the sleep delay in ms for the given 1-indexed retry attempt. Delay
     * is {@link #BACKOFF_BASE_MS}{@code [attempt-1]} perturbed by ±50 % uniform
     * jitter, clamped to be non-negative.
     *
     * @param attempt 1-indexed retry attempt number
     * @return the computed sleep delay in ms
     */
    static long computeBackoffMillis(int attempt) {
        long base = BACKOFF_BASE_MS[Math.min(attempt - 1, BACKOFF_BASE_MS.length - 1)];
        long jitter = ThreadLocalRandom.current().nextLong(base + 1) - base / 2;
        return Math.max(0L, base + jitter);
    }

    private Nanopub np;
    private NanopubSignatureElement el = null;
    private List<Statement> metaStatements = new ArrayList<>();
    private List<Statement> nanopubStatements = new ArrayList<>();
    private List<Statement> literalStatements = new ArrayList<>();
    private List<Statement> invalidateStatements = new ArrayList<>();
    private List<Statement> textStatements, allStatements, invalidatingStatements;
    private List<Statement> spaceExtractionStatements = new ArrayList<>();
    private Calendar timestamp = null;
    private Statement pubkeyStatement, pubkeyStatementX;
    private List<String> notes = new ArrayList<>();
    private boolean aborted = false;
    private static final Logger logger = LoggerFactory.getLogger(NanopubLoader.class);


    NanopubLoader(Nanopub np, long counter) {
        this.np = np;
        if (counter >= 0) {
            logger.info("Loading nanopub #{}: <{}>", counter, np.getUri());
        } else {
            logger.info("Loading nanopub: <{}>", np.getUri());
        }

        // TODO Ensure proper synchronization and DB rollbacks

        // TODO Check for null characters ("\0"), which can cause problems in Virtuoso.

        String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
        if (!np.getHeadUri().toString().contains(ac) || !np.getAssertionUri().toString().contains(ac) || !np.getProvenanceUri().toString().contains(ac) || !np.getPubinfoUri().toString().contains(ac)) {
            notes.add("could not load nanopub as not all graphs contained the artifact code");
            aborted = true;
            return;
        }

        // Checked before the signature: protection is about not distributing the
        // content at all, so it applies to invalidly-signed nanopubs too.
        if (NanopubServerUtils.isProtectedNanopub(np) && !FeatureFlags.localInstance()) {
            notes.add("could not load nanopub as it is protected (npx:ProtectedNanopub) and this is not a local instance");
            aborted = true;
            return;
        }

        try {
            el = SignatureUtils.getSignatureElement(np);
        } catch (MalformedCryptoElementException ex) {
            notes.add("Signature error");
        }
        if (!hasValidSignature(el)) {
            // Audit trail for the silent-false path: without this, an aborted nanopub
            // is invisible in the admin repo and only detectable as a gap between the
            // stream counter and the loaded count. See issue around RDF4J-instability
            // load gaps (full vs registry, meta vs full) where signature validation
            // returned false but no GeneralSecurityException was thrown.
            if (notes.isEmpty()) {
                notes.add("Invalid signature");
            }
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
                if (b != null) {
                    otherNps.add(b);
                }
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
                if (b != null) {
                    otherNps.add(b);
                }
            }
            if (st.getObject() instanceof IRI) {
                if (st.getObject().toString().contains(ac)) {
                    subIris.add((IRI) st.getObject());
                } else {
                    IRI b = getBaseTrustyUri(st.getObject());
                    if (b != null) {
                        otherNps.add(b);
                    }
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
        invalidatingStatements = getInvalidatingStatements(np.getUri());

        metaStatements.addAll(invalidateStatements);

        allStatements = new ArrayList<>(nanopubStatements);
        allStatements.addAll(metaStatements);
        allStatements.addAll(invalidatingStatements);

        textStatements = new ArrayList<>(literalStatements);
        textStatements.addAll(metaStatements);
        textStatements.addAll(invalidatingStatements);

        if (FeatureFlags.spacesEnabled()) {
            IRI signedBy = (el.getSigners().size() == 1) ? el.getSigners().iterator().next() : null;
            String pubkeyHash = Utils.createHash(el.getPublicKeyString());
            Date createdAt = (timestamp != null) ? timestamp.getTime() : null;
            SpacesExtractor.Context ctx = new SpacesExtractor.Context(ac, signedBy, pubkeyHash, createdAt);
            spaceExtractionStatements = SpacesExtractor.extract(np, ctx);
        }
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
            logger.info("Skipping already-loaded nanopub: <{}>", nanopubUri);
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

    boolean isAborted() {
        return aborted;
    }

    List<String> getNotes() {
        return notes;
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
                    if (FeatureFlags.last30dRepoEnabled()) {
                        runTask.accept(() -> loadNanopubToLatest(np.getUri(), allStatements));
                    }
                }
            }

            if (FeatureFlags.textRepoEnabled()) {
                runTask.accept(() -> loadNanopubToRepo(np.getUri(), textStatements, "text"));
            }
            if (FeatureFlags.fullRepoEnabled()) {
                runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "full"));
            }
            // Note: "meta" task is deferred until all other tasks complete successfully

            runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "pubkey_" + Utils.createHash(el.getPublicKeyString())));
            //		loadNanopubToRepo(np.getUri(), textStatements, "text-pubkey_" + Utils.createHash(el.getPublicKeyString()));
            for (IRI typeIri : NanopubUtils.getTypes(np)) {
                if (!isShardedType(typeIri, np.getUri())) {
                    continue;
                }
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

            // Write to the spaces repo only when the nanopub carries its own space-relevant
            // extractions. Invalidators of space-relevant nanopubs are propagated to spaces
            // symmetrically below (forward path in loadInvalidateStatements, reverse path in
            // the invalidatorPubkeys block) — mirrors the per-type-repo propagation added in
            // PR #103 (commit 09eeb32). The materialiser's invalidation joins
            // (?invNp npx:invalidates ?np + ?invNp npa:hasLoadNumber ?ln in the spaces repo's
            // npa:graph) stay populated because the invalidators that matter are exactly the
            // ones we propagate.
            boolean thisNpIsSpaceRelevant = FeatureFlags.spacesEnabled() && !spaceExtractionStatements.isEmpty();
            if (thisNpIsSpaceRelevant) {
                runTask.accept(() -> loadToSpacesRepo(np.getUri(), allStatements, spaceExtractionStatements));
            }

            for (Statement st : invalidateStatements) {
                runTask.accept(() -> loadInvalidateStatements(np, el.getPublicKeyString(), st, pubkeyStatement, pubkeyStatementX, allStatements));
            }

            // Reverse-order symmetry: when retractors were loaded before this nanopub,
            // getInvalidatingStatements (in the constructor) captured their
            // `npx:invalidates` markers into invalidatingStatements. Mirror what
            // loadInvalidateStatements does in the forward case — load each retractor's
            // full content into this nanopub's per-type repos (those types the
            // retractor doesn't itself carry), sourced from the retractor's per-pubkey
            // repo (the one shard guaranteed to be populated for every successfully
            // loaded nanopub). When this nanopub is space-relevant, additionally load
            // each retractor into the spaces repo so the materialiser's invalidation
            // join sees them regardless of load order.
            Map<IRI, String> invalidatorPubkeys = collectInvalidatorPubkeys(invalidatingStatements);
            if (!invalidatorPubkeys.isEmpty()) {
                Set<IRI> thisNpTypes = NanopubUtils.getTypes(np);
                for (Map.Entry<IRI, String> e : invalidatorPubkeys.entrySet()) {
                    IRI invIri = e.getKey();
                    String invPubkey = e.getValue();
                    runTask.accept(() -> loadInvalidatorIntoTypeRepos(invIri, invPubkey, np.getUri(), thisNpTypes));
                    if (thisNpIsSpaceRelevant) {
                        runTask.accept(() -> loadInvalidatorIntoSpacesRepo(invIri, invPubkey, np.getUri()));
                    }
                }
            }

            // Wait for all non-meta tasks to complete successfully before submitting the meta task.
            // On failure, cancel the remaining futures so orphaned tasks don't keep running in the
            // shared loadingPool and race with the next batch retry (which re-submits the same
            // nanopub against the same repos).
            for (var task : runningTasks) {
                try {
                    task.get();
                } catch (ExecutionException | InterruptedException ex) {
                    for (var t : runningTasks) {
                        if (!t.isDone()) {
                            t.cancel(true);
                        }
                    }
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
    private static void loadNanopubToLatest(IRI npId, List<Statement> statements) {
        boolean success = false;
        int retries = 0;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getRepoConnection("last30d");
            try (conn) {
                // Read committed, because deleting old nanopubs is idempotent. Inserts do not collide
                // with deletes, because we are not inserting old nanopubs.
                conn.begin(IsolationLevels.READ_COMMITTED);
                conn.add(statements);
                if (lastUpdateOfLatestRepo == null || new Date().getTime() - lastUpdateOfLatestRepo > ONE_HOUR) {
                    logger.debug("Pruning nanopubs older than 30 days from last30d repo...");
                    Literal thirtyDaysAgo = vf.createLiteral(new Date(new Date().getTime() - THIRTY_DAYS));
                    TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + NPA.GRAPH + "> { " + "?np <" + DCTERMS.CREATED + "> ?date . " + "filter ( ?date < ?thirtydaysago ) " + "} }");
                    q.setBinding("thirtydaysago", thirtyDaysAgo);
                    try (TupleQueryResult r = q.evaluate()) {
                        while (r.hasNext()) {
                            BindingSet b = r.next();
                            IRI oldNpId = (IRI) b.getBinding("np").getValue();
                            logger.debug("Pruning expired nanopub from last30d repo: <{}>", oldNpId);
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
                logger.warn("Failed to load nanopub <{}> to last30d repo: {}", npId, ex.getMessage(), ex);
                if (conn.isActive()) {
                    conn.rollback();
                }
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to load nanopub " + npId + " to last30d repo after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying load of <{}> to last30d repo in {} ms (attempt {}/{})...", npId, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Whether a nanopub type gets its own {@code type_} shard for the given
     * nanopub: locally minted types (IRIs in the nanopub's own namespace) and
     * non-http(s) types are not sharded on.
     *
     * <p>Single definition of the rule, because it is applied from four places
     * that have to agree: the per-type fan-out in {@link #executeLoading}, both
     * directions of the retractor propagation ({@link #loadInvalidateStatements}
     * and {@link #loadInvalidatorIntoTypeRepos}), and
     * {@link ShardReconciler#expectedShardRepos}. Where they disagreed, a
     * retractor could be written into a type repo the regular loader would never
     * create, or be assumed already present in one it had in fact skipped.
     *
     * @param typeIri the nanopub type
     * @param npId    the nanopub carrying that type
     * @return true if the type maps to a shard repo for that nanopub
     */
    static boolean isShardedType(IRI typeIri, IRI npId) {
        if (typeIri.stringValue().startsWith(npId.stringValue())) {
            return false;
        }
        return typeIri.stringValue().matches("https?://.*");
    }

    @GeneratedFlagForDependentElements
    private static void loadNanopubToRepo(IRI npId, List<Statement> statements, String repoName) {
        boolean success = false;
        int retries = 0;
        while (!success) {
            // The count/checksum chain must not suffer write skew, so this read-modify-write
            // is serialised — by repoWriteLock rather than by a SERIALIZABLE transaction. This
            // process is the only writer of those triples, so the guarantee is the same; see
            // repoWriteLocks for why the isolation level is the expensive way to buy it.
            // Held across the whole transaction, released before any retry back-off.
            ReentrantLock repoLock = repoWriteLock(repoName);
            repoLock.lock();
            try {
                RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName);
                long newCountForCache = -1;
                String newChecksumForCache = null;
                try (conn) {
                    conn.begin(IsolationLevels.SNAPSHOT);
                    var repoStatus = fetchRepoStatus(conn, npId, repoName);
                    if (repoStatus.isLoaded) {
                        // INFO, not DEBUG: this skip decides that a shard write is unnecessary
                        // based on a single store read. When the backend misbehaves (issue #139:
                        // a shard "successfully" written yet not durable), this line is the only
                        // trace distinguishing a false skip from a lost commit.
                        logger.info("Skipping already-loaded nanopub <{}> in repo '{}'", npId, repoName);
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
                        if ("meta".equals(repoName)) {
                            newCountForCache = repoStatus.count + 1;
                            newChecksumForCache = newChecksum;
                        }
                    }
                    conn.commit();
                    if (newCountForCache >= 0) {
                        loadedNanopubCount = newCountForCache;
                    }
                    if (newChecksumForCache != null) {
                        loadedNanopubChecksum = newChecksumForCache;
                    }
                    success = true;
                } catch (Exception ex) {
                    logger.warn("Failed to load nanopub <{}> to repo '{}': {}", npId, repoName, ex.getMessage(), ex);
                    if (conn.isActive()) {
                        conn.rollback();
                    }
                }
            } finally {
                repoLock.unlock();
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to load nanopub " + npId + " to repo " + repoName + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying load of <{}> to repo '{}' in {} ms (attempt {}/{})...", npId, repoName, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Writes the raw nanopub statements (all four graphs) into the {@code spaces}
     * repo alongside the pre-computed extraction statements (which target
     * {@code npa:spacesGraph}). Stamps the load-number on the nanopub IRI and bumps
     * {@code npa:thisRepo npa:currentLoadCounter} in {@code npa:graph}, all within
     * one serializable transaction.
     *
     * <p>Idempotent: if the nanopub already has a {@code npa:hasLoadNumber} stamp in
     * {@code npa:graph} of the {@code spaces} repo, this is a no-op.
     *
     * @param npId            nanopub IRI
     * @param nanopubTriples  raw nanopub statements (all four graphs + meta)
     * @param spaceExtraction summary triples destined for {@code npa:spacesGraph}
     */
    @GeneratedFlagForDependentElements
    private static void loadToSpacesRepo(IRI npId, List<Statement> nanopubTriples,
                                         List<Statement> spaceExtraction) {
        boolean success = false;
        int retries = 0;
        while (!success) {
            // Same substitution as loadNanopubToRepo: the spaces load counter is a
            // read-modify-write, serialised by repoWriteLock instead of by the isolation
            // level. The spaces branch is also written by AuthorityResolver, whose own
            // writers serialise via its synchronized methods at SNAPSHOT/READ_COMMITTED.
            ReentrantLock repoLock = repoWriteLock("spaces");
            repoLock.lock();
            try {
                RepositoryConnection conn = TripleStore.get().getRepoConnection("spaces");
                try (conn) {
                    conn.begin(IsolationLevels.SNAPSHOT);
                    // Idempotency: skip if this nanopub is already stamped in this repo.
                    if (Utils.getObjectForPattern(conn, NPA.GRAPH, npId, NPA.HAS_LOAD_NUMBER) != null) {
                        // INFO for the same reason as the loadNanopubToRepo skip (issue #139).
                        logger.info("Skipping already-loaded nanopub <{}> in spaces repo", npId);
                        conn.commit();
                        success = true;
                        continue;
                    }
                    long newCounter = fetchSpacesLoadCounter(conn) + 1;
                    conn.remove(NPA.THIS_REPO,
                            com.knowledgepixels.query.vocabulary.SpacesVocab.CURRENT_LOAD_COUNTER,
                            null, NPA.GRAPH);
                    conn.add(NPA.THIS_REPO,
                            com.knowledgepixels.query.vocabulary.SpacesVocab.CURRENT_LOAD_COUNTER,
                            vf.createLiteral(newCounter), NPA.GRAPH);
                    conn.add(npId, NPA.HAS_LOAD_NUMBER, vf.createLiteral(newCounter), NPA.GRAPH);
                    conn.add(nanopubTriples);
                    conn.add(spaceExtraction);
                    conn.commit();
                    success = true;
                } catch (Exception ex) {
                    logger.warn("Failed to load nanopub <{}> to spaces repo: {}", npId, ex.getMessage(), ex);
                    if (conn.isActive()) {
                        conn.rollback();
                    }
                }
            } finally {
                repoLock.unlock();
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to load nanopub " + npId + " to spaces repo after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying load of <{}> to spaces repo in {} ms (attempt {}/{})...", npId, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Returns the cumulative count of nanopubs ever loaded into the {@code meta}
     * repo, or {@code null} if the value cannot be determined (e.g. the store
     * hasn't been initialised yet). Reads the persisted {@code npa:hasNanopubCount}
     * triple on first call and caches it in {@link #loadedNanopubCount};
     * subsequent fresh loads update the cache in-place.
     *
     * <p><b>Blocks</b> on a cold cache — use {@link #getCachedLoadedNanopubCount()}
     * from the event loop.
     */
    public static Long getLoadedNanopubCount() {
        Long v = loadedNanopubCount;
        if (v != null) {
            return v;
        }
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection("meta")) {
            Value val = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT);
            if (val != null) {
                v = Long.parseLong(val.stringValue());
                loadedNanopubCount = v;
                return v;
            }
        } catch (NumberFormatException ex) {
            logger.warn("Malformed npa:hasNanopubCount literal in meta repo (value not parseable as long): {}", ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.warn("Could not read npa:hasNanopubCount from meta repo", ex);
        }
        return null;
    }

    /**
     * The cached loaded-nanopub count, without the store read that
     * {@link #getLoadedNanopubCount()} falls back to, or {@code null} if nothing has
     * populated it yet.
     *
     * <p>For callers that must not block — specifically
     * {@link MainVerticle#applyGlobalHeaders}, which runs on the Vert.x event loop
     * for every inbound request. The lazy fallback does blocking HTTP, and on a cold
     * cache (i.e. after every restart) that stalled the event loop until the store
     * answered; Vert.x's BlockedThreadChecker fired repeatedly on 2026-07-31. Worse,
     * with the store unreachable it would have blocked every request for the full
     * 10 s connect timeout, turning an RDF4J outage into a total outage of the HTTP
     * layer — including the status headers used to diagnose it.
     *
     * <p>Kept warm off the event loop by {@link #primeHeaderCaches()}.
     *
     * @return the cached count, or null if not yet known
     */
    public static Long getCachedLoadedNanopubCount() {
        return loadedNanopubCount;
    }

    /**
     * The cached loaded-nanopub checksum, without the store read that
     * {@link #getLoadedNanopubChecksum()} falls back to, or {@code null} if nothing
     * has populated it yet. Same event-loop rationale as
     * {@link #getCachedLoadedNanopubCount()}.
     *
     * @return the cached checksum, or null if not yet known
     */
    public static String getCachedLoadedNanopubChecksum() {
        return loadedNanopubChecksum;
    }

    /**
     * Populates the caches read by {@link #getCachedLoadedNanopubCount()} and
     * {@link #getCachedLoadedNanopubChecksum()}, going to the store if they are cold.
     *
     * <p>Both values are otherwise only refreshed when a nanopub is actually loaded,
     * so an instance that starts up already caught up would never populate them and
     * would serve those headers empty forever. Something off the event loop has to
     * prime them; {@link MetricsCollector#updateMetrics()} does, on its own executor.
     *
     * <p><b>Blocks.</b> Never call from the Vert.x event loop.
     */
    public static void primeHeaderCaches() {
        getLoadedNanopubCount();
        getLoadedNanopubChecksum();
    }

    /**
     * Returns the order-independent XOR checksum (Base64-encoded) of trusty URIs
     * of all nanopubs ever loaded into the {@code meta} repo, or {@code null} if
     * the value cannot be determined (e.g. the store hasn't been initialised
     * yet). Reads the persisted {@code npa:hasNanopubChecksum} triple on first
     * call and caches it in {@link #loadedNanopubChecksum}; subsequent fresh
     * loads update the cache in-place alongside {@link #loadedNanopubCount}.
     *
     * <p><b>Blocks</b> on a cold cache — use {@link #getCachedLoadedNanopubChecksum()}
     * from the event loop.
     */
    public static String getLoadedNanopubChecksum() {
        String v = loadedNanopubChecksum;
        if (v != null) {
            return v;
        }
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection("meta")) {
            Value val = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM);
            if (val != null) {
                v = val.stringValue();
                loadedNanopubChecksum = v;
                return v;
            }
        } catch (Exception ex) {
            logger.warn("Could not read npa:hasNanopubChecksum from meta repo", ex);
        }
        return null;
    }

    private static long fetchSpacesLoadCounter(RepositoryConnection conn) {
        Value v = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO,
                com.knowledgepixels.query.vocabulary.SpacesVocab.CURRENT_LOAD_COUNTER);
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(v.stringValue());
        } catch (NumberFormatException ex) {
            logger.warn("Malformed npa:currentLoadCounter literal in spaces repo (value not parseable as long): \"{}\"", v.stringValue());
            return 0;
        }
    }

    record RepoStatus(boolean isLoaded, long count, String checksum) {
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
    static RepoStatus fetchRepoStatus(RepositoryConnection conn, IRI npId, String repoName) {
        var result = conn.prepareTupleQuery(QueryLanguage.SPARQL, REPO_STATUS_QUERY_TEMPLATE.formatted(npId)).evaluate();
        try (result) {
            if (!result.hasNext()) {
                // Every repo this loader writes is seeded with count=0 + INIT_CHECKSUM in
                // initNewRepo's creation transaction, so an empty result can only mean a
                // degraded read (a misbehaving store returning no rows instead of failing,
                // issue #142) or a store that lost its admin triples. Treating it as "fresh
                // repo" would commit count=1 over the existing chain — observed on the kpxl
                // full repo 2026-08-12 (count reset from 86860 to ~0 while all data was
                // still present). Throw instead: the caller's retry loop absorbs transients,
                // and a persistent failure surfaces to the operator rather than corrupting
                // the chain.
                throw new RuntimeException("Repo '" + repoName + "' returned no count/checksum chain; "
                        + "refusing to treat a possibly-degraded read as a fresh repo");
            }
            var row = result.next();
            return new RepoStatus(row.hasBinding("loadNumber"), Long.parseLong(row.getBinding("count").getValue().stringValue()), row.getBinding("checksum").getValue().stringValue());
        }
    }

    @GeneratedFlagForDependentElements
    private static void loadInvalidateStatements(Nanopub thisNp, String thisPubkey, Statement invalidateStatement, Statement pubkeyStatement, Statement pubkeyStatementX, List<Statement> thisAllStatements) {
        boolean success = false;
        int retries = 0;
        List<IRI> typesToLoadFullInto = new ArrayList<>();
        boolean targetIsSpaceRelevant = false;
        while (!success) {
            typesToLoadFullInto.clear();
            targetIsSpaceRelevant = false;
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
                        //logger.info("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for pubkey " + pubkey);
                        connections.add(loadStatements("pubkey_" + Utils.createHash(pubkey), invalidateStatement, pubkeyStatement, pubkeyStatementX));
//						connections.add(loadStatements("text-pubkey_" + Utils.createHash(pubkey), invalidateStatement, pubkeyStatement));
                    }

                    Set<IRI> thisNpTypes = NanopubUtils.getTypes(thisNp);
                    for (Value v : Utils.getObjectsForPattern(metaConn, NPA.GRAPH, invalidatedNpId, NPX.HAS_NANOPUB_TYPE)) {
                        if (v instanceof IRI typeIri) {
                            // Only types the invalidated nanopub is actually sharded on, and
                            // only those this nanopub's own fan-out doesn't already cover —
                            // "carries the type" isn't enough, the type has to be one this
                            // nanopub is itself sharded on (mirrors loadInvalidatorIntoTypeRepos).
                            boolean coveredByOwnFanOut = thisNpTypes.contains(typeIri)
                                    && isShardedType(typeIri, thisNp.getUri());
                            if (isShardedType(typeIri, invalidatedNpId) && !coveredByOwnFanOut) {
                                // Defer until after the meta-read commits — full load goes
                                // through loadNanopubToRepo, which has its own transaction
                                // and retry loop (see post-loop block below).
                                typesToLoadFullInto.add(typeIri);
                            }
                            if (SpacesExtractor.TRIGGER_TYPES.contains(typeIri)) {
                                // Target carries a space-relevant type — propagate the
                                // retractor into the spaces repo too (deferred, same
                                // reason as above).
                                targetIsSpaceRelevant = true;
                            }
                        }
                    }

//					for (Value v : Utils.getObjectsForPattern(metaConn, NPA.GRAPH, invalidatedNpId, DCTERMS.CREATOR)) {
//						IRI creatorIri = (IRI) v;
//						if (!SimpleCreatorPattern.getCreators(thisNp).contains(creatorIri)) {
//							//logger.info("Adding invalidation expressed in " + thisNp.getUri() + " also to repo for user " + creatorIri);
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
                logger.warn("Failed to load invalidation statements from <{}> to target repos: {}", thisNp.getUri(), ex.getMessage(), ex);
                if (metaConn.isActive()) {
                    metaConn.rollback();
                }
                for (RepositoryConnection c : connections) {
                    if (c.isActive()) {
                        c.rollback();
                    }
                }
            } finally {
                metaConn.close();
                for (RepositoryConnection c : connections) c.close();
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to load invalidate statements for " + thisNp.getUri() + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying invalidation-statement load for <{}> in {} ms (attempt {}/{})...", thisNp.getUri(), delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // Mirror the Registries' behaviour: index a retraction under the types of the
        // nanopub it invalidates, even when the retractor itself doesn't carry those
        // types. Load the full retracting nanopub (not just the npx:invalidates marker)
        // so a query against a type repo can fetch the retractor's own assertion /
        // provenance / pubinfo, not only the join handle.
        // loadNanopubToRepo is idempotent (early-exit on npa:hasLoadNumber) and runs
        // its own locked transaction + retry loop, so it's safe to call here.
        for (IRI typeIri : typesToLoadFullInto) {
            loadNanopubToRepo(thisNp.getUri(), thisAllStatements, "type_" + Utils.createHash(typeIri));
        }
        // Same rationale for the spaces repo: when the invalidated nanopub is itself
        // space-relevant, the retractor needs to land in the spaces repo so the
        // materialiser's invalidation join (?invNp npx:invalidates ?np +
        // ?invNp npa:hasLoadNumber ?ln in npa:graph) finds it. spaceExtractionStatements
        // is empty here — the retractor is not space-relevant by itself, otherwise it
        // would have already been loaded to spaces by the regular spaces-load task.
        if (targetIsSpaceRelevant && FeatureFlags.spacesEnabled()) {
            loadToSpacesRepo(thisNp.getUri(), thisAllStatements, Collections.emptyList());
        }
    }

    /**
     * Extracts a map from invalidator IRI to that invalidator's pubkey literal
     * out of an {@code invalidatingStatements} list as produced by
     * {@link #getInvalidatingStatements}. The list interleaves
     * {@code (?inv, npx:invalidates, ?np)},
     * {@code (?inv, npa:hasValidSignatureForPublicKey, ?pubkey)} and
     * {@code (?inv, npa:hasValidSignatureForPublicKeyHash, ?hash)} triples per
     * invalidator; this helper picks out only the full-pubkey triples.
     */
    private static Map<IRI, String> collectInvalidatorPubkeys(List<Statement> invalidatingStatements) {
        Map<IRI, String> result = new LinkedHashMap<>();
        for (Statement st : invalidatingStatements) {
            if (st.getPredicate().equals(NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY)
                && st.getSubject() instanceof IRI invIri) {
                result.put(invIri, st.getObject().stringValue());
            }
        }
        return result;
    }

    /**
     * Reverse-order counterpart of the forward-order full-content propagation in
     * {@link #loadInvalidateStatements}: when this nanopub had already-loaded
     * retractors at the time of its own load (captured by
     * {@link #getInvalidatingStatements}), load each retractor's full content
     * into this nanopub's per-type repos — restricted to those types the
     * retractor doesn't itself carry (the retractor's own load already populated
     * the type repos it covers).
     *
     * <p>Source is the retractor's per-pubkey repo, which is the one shard
     * unconditionally populated for every successfully-loaded nanopub. The
     * retractor's types are read from the meta repo so we can skip type repos
     * the retractor's own regular load already populated.
     */
    @GeneratedFlagForDependentElements
    private static void loadInvalidatorIntoTypeRepos(IRI invIri, String invPubkey, IRI thisNpId, Set<IRI> thisNpTypes) {
        Set<IRI> invTypes = readInvalidatorTypesFromMeta(invIri, thisNpId);

        List<IRI> typesToLoadInto = new ArrayList<>();
        for (IRI typeIri : thisNpTypes) {
            // Match the regular per-type load loop's shard eligibility.
            if (!isShardedType(typeIri, thisNpId)) {
                continue;
            }
            // Skip only the type repos the invalidator's own fan-out really populated:
            // carrying the type is not enough if the invalidator itself isn't sharded on it.
            if (invTypes.contains(typeIri) && isShardedType(typeIri, invIri)) {
                continue;
            }
            typesToLoadInto.add(typeIri);
        }
        if (typesToLoadInto.isEmpty()) {
            return;
        }

        List<Statement> invContent = fetchNanopubAllStatementsFromPubkeyRepo(invIri, invPubkey);
        for (IRI typeIri : typesToLoadInto) {
            loadNanopubToRepo(invIri, invContent, "type_" + Utils.createHash(typeIri));
        }
    }

    /**
     * Reverse-order counterpart for the spaces repo: when a space-relevant nanopub
     * is loaded and {@link #getInvalidatingStatements} captured already-loaded
     * retractors of it, load each retractor's full content into the spaces repo so
     * the materialiser's invalidation join (?invNp npx:invalidates ?np +
     * ?invNp npa:hasLoadNumber ?ln in npa:graph) finds it regardless of the
     * load order between target and retractor.
     *
     * <p>Source is the retractor's per-pubkey repo (the one shard unconditionally
     * populated for every successfully-loaded nanopub). Passes an empty
     * {@code spaceExtraction} list to {@link #loadToSpacesRepo}: by construction
     * the retractor would already be in the spaces repo by its regular spaces-load
     * task if it carried space-relevant extractions of its own.
     * {@link #loadToSpacesRepo} is idempotent on {@code npa:hasLoadNumber}, so
     * the double-load case is a no-op.
     */
    @GeneratedFlagForDependentElements
    private static void loadInvalidatorIntoSpacesRepo(IRI invIri, String invPubkey, IRI thisNpId) {
        List<Statement> invContent = fetchNanopubAllStatementsFromPubkeyRepo(invIri, invPubkey);
        loadToSpacesRepo(invIri, invContent, Collections.emptyList());
    }

    @GeneratedFlagForDependentElements
    private static Set<IRI> readInvalidatorTypesFromMeta(IRI invIri, IRI thisNpId) {
        Set<IRI> invTypes = new HashSet<>();
        boolean success = false;
        int retries = 0;
        while (!success) {
            invTypes.clear();
            RepositoryConnection metaConn = TripleStore.get().getRepoConnection("meta");
            try (metaConn) {
                metaConn.begin(IsolationLevels.READ_COMMITTED);
                for (Value v : Utils.getObjectsForPattern(metaConn, NPA.GRAPH, invIri, NPX.HAS_NANOPUB_TYPE)) {
                    if (v instanceof IRI ti) {
                        invTypes.add(ti);
                    }
                }
                metaConn.commit();
                success = true;
            } catch (Exception ex) {
                logger.warn("Failed to read types for invalidator <{}> (needed for target <{}>): {}", invIri, thisNpId, ex.getMessage(), ex);
                if (metaConn.isActive()) {
                    metaConn.rollback();
                }
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to read invalidator types for " + invIri + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying type-read for invalidator <{}> in {} ms (attempt {}/{})...", invIri, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return invTypes;
    }

    /**
     * Reads a nanopub's content back from its per-pubkey repo as a list of
     * statements that mirrors what its original load produced into
     * {@code allStatements}, minus the per-repo bookkeeping triples
     * ({@code npa:hasLoadNumber}, {@code npa:hasLoadChecksum},
     * {@code npa:hasLoadTimestamp}). {@link #loadNanopubToRepo} stamps those
     * fresh on every destination repo, so they must be filtered out of the
     * source set.
     *
     * <p>Fetched content:
     * <ul>
     *   <li>All triples in the nanopub's four named graphs, discovered via
     *       {@code <npId> npa:hasGraph ?g} in {@code npa:graph}.</li>
     *   <li>{@code (<npId>, ?p, ?o)} in {@code npa:graph}, excluding the per-repo
     *       bookkeeping predicates above.</li>
     *   <li>{@code (?inv, npx:invalidates, <npId>)} in {@code npa:graph}, plus
     *       the matching {@code npa:hasValidSignatureForPublicKey[Hash]} triples
     *       of each {@code ?inv}, so propagation carries the nanopub's full
     *       invalidator history (which doesn't affect query results — see the
     *       one-hop filter in {@code Utils#defaultQuery} — but keeps repos
     *       consistent).</li>
     *   <li>{@code (<npId>, ?p, ?o)} in {@code npa:networkGraph}.</li>
     * </ul>
     */
    @GeneratedFlagForDependentElements
    private static List<Statement> fetchNanopubAllStatementsFromPubkeyRepo(IRI npId, String pubkey) {
        String repoName = "pubkey_" + Utils.createHash(pubkey);
        boolean success = false;
        int retries = 0;
        List<Statement> result = new ArrayList<>();
        while (!success) {
            result.clear();
            RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName);
            try (conn) {
                // Append-only data + idempotent re-load downstream: READ_COMMITTED suffices.
                conn.begin(IsolationLevels.READ_COMMITTED);

                List<IRI> npGraphs = new ArrayList<>();
                try (RepositoryResult<Statement> r = conn.getStatements(npId, NPA.HAS_GRAPH, null, NPA.GRAPH)) {
                    while (r.hasNext()) {
                        Value o = r.next().getObject();
                        if (o instanceof IRI iri) {
                            npGraphs.add(iri);
                        }
                    }
                }

                for (IRI g : npGraphs) {
                    try (RepositoryResult<Statement> r = conn.getStatements(null, null, null, g)) {
                        while (r.hasNext()) result.add(r.next());
                    }
                }

                try (RepositoryResult<Statement> r = conn.getStatements(npId, null, null, NPA.GRAPH)) {
                    while (r.hasNext()) {
                        Statement st = r.next();
                        IRI p = st.getPredicate();
                        if (p.equals(NPA.HAS_LOAD_NUMBER)
                            || p.equals(NPA.HAS_LOAD_CHECKSUM)
                            || p.equals(NPA.HAS_LOAD_TIMESTAMP)) {
                            continue;
                        }
                        result.add(st);
                    }
                }

                Set<IRI> invalidators = new HashSet<>();
                try (RepositoryResult<Statement> r = conn.getStatements(null, NPX.INVALIDATES, npId, NPA.GRAPH)) {
                    while (r.hasNext()) {
                        Statement st = r.next();
                        result.add(st);
                        if (st.getSubject() instanceof IRI invIri) {
                            invalidators.add(invIri);
                        }
                    }
                }
                for (IRI invIri : invalidators) {
                    try (RepositoryResult<Statement> r = conn.getStatements(invIri, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, null, NPA.GRAPH)) {
                        while (r.hasNext()) result.add(r.next());
                    }
                    try (RepositoryResult<Statement> r = conn.getStatements(invIri, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, null, NPA.GRAPH)) {
                        while (r.hasNext()) result.add(r.next());
                    }
                }

                try (RepositoryResult<Statement> r = conn.getStatements(npId, null, null, NPA.NETWORK_GRAPH)) {
                    while (r.hasNext()) result.add(r.next());
                }

                conn.commit();
                success = true;
            } catch (Exception ex) {
                logger.warn("Failed to fetch content of nanopub <{}> from repo '{}': {}", npId, repoName, ex.getMessage(), ex);
                if (conn.isActive()) {
                    conn.rollback();
                }
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to fetch nanopub content from " + repoName + " for " + npId + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying content-fetch of <{}> from repo '{}' in {} ms (attempt {}/{})...", npId, repoName, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return result;
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

    /**
     * Reads back, from the {@code meta} repo, the markers saying that {@code npId}
     * is already invalidated by some earlier-loaded nanopub. Per invalidator the
     * returned list holds three triples, all in {@code npa:graph}:
     * {@code npx:invalidates}, {@code npa:hasValidSignatureForPublicKey} and
     * {@code npa:hasValidSignatureForPublicKeyHash}.
     *
     * <p>The hash triple is derived locally with {@link Utils#createHash} rather
     * than read from {@code meta}: the loader computes it the same way when it
     * first writes the pair, so the two can't disagree, and deriving keeps this
     * working against a {@code meta} repo whose older entries predate the hash
     * triple (added 2024-05).
     *
     * <p>It must be here at all because the caller mirrors this list into every
     * shard the nanopub is written to, and the pubkey-hash is what the canonical
     * retraction filter joins on ("invalidator and target share a signing key" —
     * see {@code AuthorityResolver#samePublisherClause}). Emitting only the full
     * pubkey — as this method did until issue #14 — made an invalidator's hash
     * present in a shard when the invalidator happened to be loaded <em>after</em>
     * its target (the forward path in {@link #loadInvalidateStatements} writes
     * both) and absent when it was loaded before, so whether a retraction took
     * effect in a type repo depended on load order.
     */
    @GeneratedFlagForDependentElements
    static List<Statement> getInvalidatingStatements(IRI npId) {
        List<Statement> invalidatingStatements = new ArrayList<>();
        boolean success = false;
        int retries = 0;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getRepoConnection("meta");
            try (conn) {
                // Basic isolation because here we only read append-only data.
                conn.begin(IsolationLevels.READ_COMMITTED);

                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + NPA.GRAPH + "> { " + "?np <" + NPX.INVALIDATES + "> <" + npId + "> ; <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> ?pubkey . " + "} }").evaluate();
                try (r) {
                    while (r.hasNext()) {
                        BindingSet b = r.next();
                        IRI invIri = (IRI) b.getBinding("np").getValue();
                        Value pubkey = b.getBinding("pubkey").getValue();
                        invalidatingStatements.add(vf.createStatement(invIri, NPX.INVALIDATES, npId, NPA.GRAPH));
                        invalidatingStatements.add(vf.createStatement(invIri, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, pubkey, NPA.GRAPH));
                        invalidatingStatements.add(vf.createStatement(invIri, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, vf.createLiteral(Utils.createHash(pubkey.stringValue())), NPA.GRAPH));
                    }
                }
                conn.commit();
                success = true;
            } catch (Exception ex) {
                logger.warn("Failed to query existing invalidators of <{}> from meta repo: {}", npId, ex.getMessage(), ex);
                if (conn.isActive()) {
                    conn.rollback();
                }
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to get invalidating statements for " + npId + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying invalidator-query for <{}> in {} ms (attempt {}/{})...", npId, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return invalidatingStatements;
    }

    @GeneratedFlagForDependentElements
    private static void loadNoteToRepo(Resource subj, String note) {
        boolean success = false;
        int retries = 0;
        while (!success) {
            RepositoryConnection conn = TripleStore.get().getAdminRepoConnection();
            try (conn) {
                List<Statement> statements = new ArrayList<>();
                statements.add(vf.createStatement(subj, NPA.NOTE, vf.createLiteral(note), NPA.GRAPH));
                conn.add(statements);
                success = true;
            } catch (Exception ex) {
                logger.warn("Failed to write note \"{}\" to admin repo for <{}>: {}", note, subj, ex.getMessage(), ex);
            }
            if (!success) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to load note to repo for " + subj + " after " + MAX_RETRIES + " retries");
                }
                long delay = computeBackoffMillis(retries);
                logger.info("Retrying note-write for <{}> in {} ms (attempt {}/{})...", subj, delay, retries, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static boolean hasValidSignature(NanopubSignatureElement el) {
        if (el == null) {
            logger.warn("Signature validation skipped: signature element is null (nanopub has no signature)");
            return false;
        }
        try {
            if (SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
                return true;
            }
            logger.warn("Signature invalid for <{}> (pubkey: {})",
                    el.getUri(), el.getPublicKeyString() != null ? el.getPublicKeyString() : "none");
        } catch (GeneralSecurityException ex) {
            logger.warn("Signature verification threw a security exception for <{}>: {}", el.getUri(), ex.getMessage(), ex);
        }
        return false;
    }

    private static IRI getBaseTrustyUri(Value v) {
        if (!(v instanceof IRI)) {
            return null;
        }
        String s = v.stringValue();
        if (!s.matches(".*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43}([^A-Za-z0-9\\\\-_].{0,43})?")) {
            return null;
        }
        return vf.createIRI(s.replaceFirst("^(.*[^A-Za-z0-9\\-_]RA[A-Za-z0-9\\-_]{43})([^A-Za-z0-9\\\\-_].{0,43})?$", "$1"));
    }

    // TODO: Move this to nanopub library:
    private static boolean isIntroNanopub(Nanopub np) {
        for (Statement st : np.getAssertion()) {
            if (st.getPredicate().equals(NPX.DECLARED_BY)) {
                return true;
            }
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
            logger.warn("Could not check load status of <{}>: {}", npId, ex.getMessage(), ex);
        }
        return loaded;
    }

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

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
