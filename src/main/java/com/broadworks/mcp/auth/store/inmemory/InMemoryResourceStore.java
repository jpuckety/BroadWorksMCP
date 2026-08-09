package com.broadworks.mcp.auth.store.inmemory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.EncryptionService;
import com.broadworks.mcp.auth.store.ResourceStore;

/**
 * In-memory {@link ResourceStore} backed by a per-subject concurrent map.
 *
 * <p>Secret fields are stored encrypted via the injected {@link EncryptionService} for symmetry with
 * the durable backend, and decrypted on read. Non-durable and single-node only.</p>
 */
public class InMemoryResourceStore implements ResourceStore {

    /** subject -> (resourceId -> resource with encrypted password). */
    private final ConcurrentMap<String, ConcurrentMap<String, AlpacaResource>> bySubject =
            new ConcurrentHashMap<>();

    private final EncryptionService encryptionService;

    public InMemoryResourceStore(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public List<AlpacaResource> listForUser(String subject) {
        if (subject == null) {
            return List.of();
        }
        final ConcurrentMap<String, AlpacaResource> resources = bySubject.get(subject);
        if (resources == null) {
            return List.of();
        }
        return resources.values().stream().map(this::decrypt).toList();
    }

    @Override
    public Optional<AlpacaResource> get(String subject, String resourceId) {
        if (subject == null || resourceId == null) {
            return Optional.empty();
        }
        final ConcurrentMap<String, AlpacaResource> resources = bySubject.get(subject);
        if (resources == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(resources.get(resourceId)).map(this::decrypt);
    }

    @Override
    public void put(String subject, AlpacaResource resource) {
        bySubject.computeIfAbsent(subject, ignored -> new ConcurrentHashMap<>())
                .put(resource.resourceId(), encrypt(resource));
    }

    @Override
    public void delete(String subject, String resourceId) {
        if (subject == null || resourceId == null) {
            return;
        }
        final ConcurrentMap<String, AlpacaResource> resources = bySubject.get(subject);
        if (resources != null) {
            resources.remove(resourceId);
        }
    }

    private AlpacaResource encrypt(AlpacaResource resource) {
        return resource.withPassword(encryptionService.encrypt(resource.password()));
    }

    private AlpacaResource decrypt(AlpacaResource resource) {
        return resource.withPassword(encryptionService.decrypt(resource.password()));
    }
}
