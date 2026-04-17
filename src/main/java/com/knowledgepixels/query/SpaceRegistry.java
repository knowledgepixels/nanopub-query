package com.knowledgepixels.query;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory registry of known spaces. A <b>space ref</b> uniquely identifies a space
 * as the concatenation of its root nanopub's artifact code and the hash of its Space
 * IRI: {@code <rootNanopubId>_<SPACEIRIHASH>}. Each space gets its own named graph
 * {@code npas:<spaceRef>} in the shared {@code spaces} repo (see
 * {@link com.knowledgepixels.query.vocabulary.NPAS}).
 *
 * <p>This skeleton only tracks what is needed to recognize space refs during nanopub
 * detection. Role-property tracking, per-source-nanopub reverse index, and admin-repo
 * persistence are added in later steps when they have a consumer. See
 * {@code doc/plan-space-repositories.md} for the full roadmap.
 */
public class SpaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpaceRegistry.class);

    private static SpaceRegistry instance;

    /**
     * Returns the singleton instance.
     *
     * @return the singleton instance
     */
    public static SpaceRegistry get() {
        if (instance == null) {
            instance = new SpaceRegistry();
        }
        return instance;
    }

    private final Set<String> knownSpaceRefs = new LinkedHashSet<>();
    private final Map<IRI, Set<String>> spaceIriToSpaceRefs = new HashMap<>();
    private final Map<String, Set<RoleProperty>> roleProperties = new HashMap<>();
    private final Map<IRI, Set<String>> sourceNanopubsToSpaceRefs = new HashMap<>();

    private SpaceRegistry() {
    }

    /**
     * Result of a {@link #registerSpace(String, IRI)} call: the resolved space ref
     * and whether the call newly added it to the registry.
     *
     * @param spaceRef the space ref of the form {@code <rootNanopubId>_<SPACEIRIHASH>}
     * @param wasNew   {@code true} if this call added a new space, {@code false} if
     *                 it was already known
     */
    public record Registration(String spaceRef, boolean wasNew) { }

    /**
     * Registers a space defined by the given root nanopub and Space IRI. Idempotent:
     * repeated calls with the same arguments yield the same space ref and do not
     * duplicate state.
     *
     * @param rootNanopubId artifact code (e.g. {@code RA...}) of the root nanopub, as
     *                      resolved by the caller via {@code gen:hasRootDefinition}
     * @param spaceIri      the Space IRI declared in the root nanopub's assertion
     * @return the registration result: the space ref and whether it was newly added
     */
    public Registration registerSpace(String rootNanopubId, IRI spaceIri) {
        String spaceRef = rootNanopubId + "_" + Utils.createHash(spaceIri);
        boolean added = knownSpaceRefs.add(spaceRef);
        spaceIriToSpaceRefs.computeIfAbsent(spaceIri, k -> new LinkedHashSet<>()).add(spaceRef);
        if (added) {
            log.info("Registered space ref: {}", spaceRef);
        }
        return new Registration(spaceRef, added);
    }

    /**
     * Checks whether the given space ref has been registered.
     *
     * @param spaceRef the space ref to check
     * @return {@code true} if known
     */
    public boolean isKnownSpace(String spaceRef) {
        return knownSpaceRefs.contains(spaceRef);
    }

    /**
     * Recovers the root nanopub artifact code from a space ref.
     *
     * @param spaceRef a space ref of the form {@code <rootNanopubId>_<SPACEIRIHASH>}
     * @return the root nanopub artifact code (the substring before the first underscore)
     * @throws IllegalArgumentException if the argument is not a valid space ref
     */
    public String getRootNanopubId(String spaceRef) {
        int sep = spaceRef.indexOf('_');
        if (sep < 0) {
            throw new IllegalArgumentException("Not a valid space ref: " + spaceRef);
        }
        return spaceRef.substring(0, sep);
    }

    /**
     * Returns an unmodifiable view of all known space refs.
     *
     * @return all known space refs
     */
    public Set<String> getKnownSpaceRefs() {
        return Collections.unmodifiableSet(knownSpaceRefs);
    }

    /**
     * Returns all known space refs whose Space IRI equals the given IRI. Multiple
     * space refs can share a Space IRI when distinct root nanopubs both declare it.
     *
     * @param spaceIri the Space IRI to look up
     * @return an unmodifiable set of matching space refs (empty if none are registered)
     */
    public Set<String> findSpaceRefsBySpaceIri(IRI spaceIri) {
        Set<String> refs = spaceIriToSpaceRefs.get(spaceIri);
        return refs == null ? Collections.emptySet() : Collections.unmodifiableSet(refs);
    }

    /**
     * Registers a role property learned for the given space (typically from a
     * role-definition nanopub). Idempotent: re-registering the same property is a
     * no-op.
     *
     * @param spaceRef     the space the property belongs to
     * @param roleProperty the property to register
     * @return {@code true} if newly added, {@code false} if already known
     * @throws IllegalArgumentException if the space ref is not registered
     */
    public boolean registerRoleProperty(String spaceRef, RoleProperty roleProperty) {
        if (!knownSpaceRefs.contains(spaceRef)) {
            throw new IllegalArgumentException("Unknown space ref: " + spaceRef);
        }
        return roleProperties.computeIfAbsent(spaceRef, k -> new LinkedHashSet<>()).add(roleProperty);
    }

    /**
     * Returns the role properties learned for the given space.
     *
     * @param spaceRef the space ref to look up
     * @return an unmodifiable set of registered role properties (empty if the space
     *         has none, or if the space ref isn't registered)
     */
    public Set<RoleProperty> getRoleProperties(String spaceRef) {
        Set<RoleProperty> props = roleProperties.get(spaceRef);
        return props == null ? Collections.emptySet() : Collections.unmodifiableSet(props);
    }

    /**
     * Records that the given source nanopub contributed extracted triples to the
     * given space. Used at invalidation time to find which spaces need re-materializing
     * when a source nanopub is invalidated.
     *
     * @param sourceNanopubUri the URI of the source nanopub
     * @param spaceRef         the space ref the nanopub contributed to
     */
    public void recordSourceNanopub(IRI sourceNanopubUri, String spaceRef) {
        sourceNanopubsToSpaceRefs.computeIfAbsent(sourceNanopubUri, k -> new LinkedHashSet<>()).add(spaceRef);
    }

    /**
     * Returns the set of space refs the given source nanopub contributed to.
     *
     * @param sourceNanopubUri the URI of the source nanopub
     * @return an unmodifiable set (empty if the nanopub contributed to no spaces)
     */
    public Set<String> getSpaceRefsForSource(IRI sourceNanopubUri) {
        Set<String> refs = sourceNanopubsToSpaceRefs.get(sourceNanopubUri);
        return refs == null ? Collections.emptySet() : Collections.unmodifiableSet(refs);
    }

    /**
     * Removes the source-nanopub → space-refs mapping for the given source. Called
     * after an invalidation has been propagated and the affected spaces have been
     * marked dirty.
     *
     * @param sourceNanopubUri the URI of the source nanopub
     * @return the set of space refs the source had contributed to before removal
     *         (empty if it wasn't tracked)
     */
    public Set<String> removeSourceNanopub(IRI sourceNanopubUri) {
        Set<String> refs = sourceNanopubsToSpaceRefs.remove(sourceNanopubUri);
        return refs == null ? Collections.emptySet() : Collections.unmodifiableSet(refs);
    }

}
