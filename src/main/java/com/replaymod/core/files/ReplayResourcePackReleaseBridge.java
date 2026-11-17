package com.replaymod.core.files;

import com.replaymod.core.files.ReplayFoldersService.ResourcePackLease;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.server.ReloadScheduler;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ZipResourcePack;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipFile;

/**
 * Private replay ZIP domain, not a replacement for vanilla reload semantics.
 * GLOBAL protects bookkeeping ONLY: never filesystem/MC calls, lease monitors, or waits.
 * A loader operation lock spans actual adds/removes, but never waits for a reload.
 * Wrapper locks span JDK ZIP operations; GLOBAL never acquires either kind of lock.
 */
public final class ReplayResourcePackReleaseBridge {
    private ReplayResourcePackReleaseBridge() { }
    public enum CycleState { STARTING, INSTALLED, SUCCESS, FAILED, UNKNOWN }
    public enum RawCloseState { OPEN, CLOSING, SUCCESS, FAILED }
    public record Ticket(Object loader, long connectionEpoch, UUID id, long addSequence,
                         ResourcePackLease lease, Path path) { }
    public record ProviderSnapshot(long revision, List<ResourcePackProfile> profiles,
                                   List<Path> ownedPaths, boolean known, boolean noop) {
        public ProviderSnapshot { profiles = List.copyOf(profiles); ownedPaths = List.copyOf(ownedPaths); }
    }
    /** Snapshot copies are diagnostic; incomingIdentity below is the install correlation key. */
    private static final class Cycle {
        final long id;
        final WeakReference<CompletableFuture<Void>> future;
        final Set<Path> paths;
        WeakReference<List<ResourcePack>> incomingIdentity;
        List<ResourcePack> finalObjects; // Transient immutable copy, NEVER used as the incoming-list key.
        CycleState state = CycleState.STARTING;
        long installSerial;
        Cycle(long id, CompletableFuture<Void> future, List<ResourcePack> incoming, Set<Path> paths) {
            this.id = id; this.future = new WeakReference<>(future);
            incomingIdentity = new WeakReference<>(incoming); finalObjects = List.copyOf(incoming); this.paths = Set.copyOf(paths);
        }
    }
    private record Binding(Session session, Path path) { }
    private record Tombstone(Path task, UUID token) { }
    private record ProviderEvidence(List<ResourcePackProfile> profiles, Set<Path> paths, boolean known) { }
    private record Attempt(Ticket ticket, ResourcePackLease.Permit permit, LoaderState loader, Ticket previous) { }
    /** Exact failed attempt debt belongs to affected leases, never to an entire loader. */
    private record AddUncertainty(UUID id, long connectionEpoch, long failedSequence, long previousSequence) { }
    private record Submission(ResourcePackLease lease, long generation, Path path, Object loader, long connectionEpoch) { }
    private static final Object GLOBAL = new Object();
    private static final Map<ResourcePackLease, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<Path, Binding> OWNED = new HashMap<>();
    private static final Map<Path, Tombstone> TOMBSTONES = new HashMap<>();
    private static final Map<Object, LoaderState> LOADERS = new IdentityHashMap<>();
    private static final Map<Object, ClientState> CLIENTS = new IdentityHashMap<>();
    private static final Map<Ticket, Attempt> ATTEMPTS = new IdentityHashMap<>();
    private static final WeakIdentities<ResourcePack, Binding> PACKS = new WeakIdentities<>();
    private static final WeakIdentities<ResourcePackProfile, Path> PROFILES = new WeakIdentities<>();
    private static final WeakIdentities<ResourcePackProvider, ProviderEvidence> PROVIDERS = new WeakIdentities<>();
    private static final ThreadLocal<Submission> SUBMITTING = new ThreadLocal<>();
    private static long sequence;
    private static final System.Logger LOGGER = System.getLogger("ReplayMod.ResourcePackLease");

    /** Weak identity, not equals/hashCode. Values never refer back to their weak key. */
    private static final class WeakIdentities<K, V> {
        private record Entry<K, V>(WeakReference<K> key, V value) { }
        private final List<Entry<K, V>> entries = new ArrayList<>();
        V get(K key) {
            V result = null;
            for (var iterator = entries.iterator(); iterator.hasNext();) {
                Entry<K, V> entry = iterator.next();
                K candidate = entry.key.get();
                if (candidate == null) iterator.remove();
                else if (candidate == key) result = entry.value;
            }
            return result;
        }
        void put(K key, V value) {
            entries.removeIf(entry -> entry.key.get() == null || entry.key.get() == key);
            entries.add(new Entry<>(new WeakReference<>(key), value));
        }
        void prune() { entries.removeIf(entry -> entry.key.get() == null); }
        void removeSession(Session session) {
            entries.removeIf(entry -> entry.key.get() == null
                    || entry.value instanceof Binding binding && binding.session == session);
        }
    }
    private static final class LoaderState {
        final ReentrantLock operations = new ReentrantLock();
        final Map<UUID, Ticket> owners = new HashMap<>();
        long epoch, revision;
        ProviderSnapshot provider; // null is UNKNOWN, NEVER implicit empty at startup.
    }
    private static final class ClientState {
        long serial;
        Cycle current;
        final List<Cycle> pending = new ArrayList<>();
    }
    private static final class Session {
        final ResourcePackLease lease;
        final Set<Path> paths = new HashSet<>();
        final Set<AddUncertainty> uncertainAdds = new HashSet<>();
        final Set<Object> loaders = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<OwnedZip> openWrappers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        MinecraftClient client;
        boolean requested, scheduled, sealed, sealReady, closing, finished, exposed, factorySeen, poisoned;
        int rawOperations, actualAdds;
        long exclusionAfter;
        String failure, waitReason;
        Session(ResourcePackLease lease) { this.lease = lease; }
    }
    private static Session session(ResourcePackLease lease) {
        return SESSIONS.computeIfAbsent(lease, Session::new);
    }
    private static Path key(Path path) { return path.toAbsolutePath().normalize(); }

    /** Called once by the sender after lease creation; does not touch settings or ReplayMod. */
    public static void attach(ResourcePackLease lease, MinecraftClient client) {
        synchronized (GLOBAL) {
            Session s = session(lease);
            if (s.client != null && s.client != client) throw new IllegalStateException("Foreign lease client");
            s.client = java.util.Objects.requireNonNull(client);
        }
        lease.onProgress(() -> signal(lease));
    }
    public static void registerReadyPath(ResourcePackLease lease, Path path) throws IOException {
        Path canonical = key(path);
        try (var permit = lease.admission(lease.generation())) {
            if (!lease.isReady(canonical)) throw new IOException("Not a complete owned ZIP: " + path);
            lease.checkFile(canonical, false); // Creation receipt, not path/size/hash ownership.
            if (!permit.valid()) throw new IOException("READY registration became stale/terminal");
            synchronized (GLOBAL) {
                Session s = session(lease);
                if (s.sealed || TOMBSTONES.containsKey(canonical.getParent())) throw new IOException("Retired task path: " + path);
                Binding old = OWNED.get(canonical);
                if (old != null && old.session != s) throw new IOException("Foreign ZIP ownership: " + path);
                s.paths.add(canonical);
                OWNED.put(canonical, new Binding(s, canonical));
            }
            if (!permit.valid()) throw new IOException("READY registration became stale/terminal after binding");
        }
    }
    public static long connectionEpoch(Object loader) {
        synchronized (GLOBAL) { return LOADERS.computeIfAbsent(loader, ignored -> new LoaderState()).epoch; }
    }
    /** C2 calls inside its runnable AFTER checking its current sender/session and packet epoch. */
    public static void submitReady(ResourcePackLease lease, long generation, long connectionEpoch,
                                   ServerResourcePackLoader loader, UUID id, Path path) throws IOException {
        Path normalized = key(path);
        Session s;
        synchronized (GLOBAL) {
            s = SESSIONS.get(lease);
            Binding binding = OWNED.get(normalized);
            if (binding == null || binding.session != s || s == null || s.sealed) {
                throw new IOException("Path was not explicitly admitted READY: " + path);
            }
        }
        if (s.client == null || !s.client.isOnThread()) throw new IOException("Owned add requires attached client main thread");
        try (var permit = lease.admission(generation)) {
            if (!lease.isReady(normalized) || connectionEpoch(loader) != connectionEpoch || !permit.valid()) {
                throw new IOException("Stale replay connection/packet or incomplete path");
            }
            // IO outside GLOBAL, inside the real admission; afterward recheck both independent
            // packet and loader epochs. The actual hook still checks permit/owner/connection.
            lease.checkFile(normalized, false);
            if (!permit.valid() || connectionEpoch(loader) != connectionEpoch) {
                throw new IOException("Replay changed during owned ZIP identity check");
            }
            Submission previous = SUBMITTING.get();
            SUBMITTING.set(new Submission(lease, generation, normalized, loader, connectionEpoch));
            try { loader.addResourcePack(id, path); }
            finally { if (previous == null) SUBMITTING.remove(); else SUBMITTING.set(previous); }
        }
    }
    public static void requestRelease(ResourcePackLease lease) {
        if (lease.state() == ResourcePackLease.State.CLOSED || lease.state() == ResourcePackLease.State.RETAINED) return;
        lease.stop();
        synchronized (GLOBAL) { session(lease).requested = true; }
        lease.onProgress(() -> signal(lease));
        signal(lease);
    }
    private static void signal(ResourcePackLease lease) {
        Session s;
        MinecraftClient client;
        synchronized (GLOBAL) {
            s = SESSIONS.get(lease);
            if (s == null || !s.requested || s.finished || s.scheduled || s.sealed) return;
            s.scheduled = true;
            client = s.client;
        }
        if (client == null) {
            // No MC attachment can only reclaim a completely unexposed task.
            ForkJoinPool.commonPool().execute(() -> drive(s));
        } else {
            try { client.execute(() -> drive(s)); }
            catch (RuntimeException failure) { diagnose(s, "Cannot schedule release", failure); }
        }
    }
    private static void wake() {
        List<ResourcePackLease> leases;
        synchronized (GLOBAL) { leases = SESSIONS.values().stream().filter(s -> s.requested).map(s -> s.lease).toList(); }
        leases.forEach(ReplayResourcePackReleaseBridge::signal);
    }
    private static void diagnose(Session s, String message, Throwable cause) {
        boolean first;
        synchronized (GLOBAL) {
            first = s.failure == null;
            if (first) s.failure = message + (cause == null ? "" : ": " + cause);
        }
        if (first) {
            IOException diagnostic = new IOException(message + "; task=" + s.lease.taskDirectory() + "; token=" + s.lease.token(), cause);
            s.lease.retain(diagnostic);
            LOGGER.log(System.Logger.Level.WARNING, diagnostic.getMessage(), cause);
        }
    }

    private static void waiting(Session s, String reason) {
        boolean changed;
        synchronized (GLOBAL) { changed = !reason.equals(s.waitReason); s.waitReason = reason; }
        if (changed) LOGGER.log(System.Logger.Level.DEBUG, reason + "; task=" + s.lease.taskDirectory());
    }
    private static void drive(Session s) {
        synchronized (GLOBAL) { s.scheduled = false; }
        if (!s.lease.quiescent()) return;
        s.lease.waiting();
        if (s.client != null && !s.client.isOnThread()) { diagnose(s, "Release not on client main thread", null); return; }
        List<Ticket> owned;
        synchronized (GLOBAL) {
            if (s.sealed || s.finished || s.actualAdds != 0) return;
            owned = LOADERS.values().stream().flatMap(l -> l.owners.values().stream()).filter(t -> t.lease == s.lease).toList();
        }
        for (Ticket ticket : owned) {
            LoaderState l;
            synchronized (GLOBAL) { l = LOADERS.get(ticket.loader); }
            if (!l.operations.tryLock()) return; // addFinished wakes us; never block MC waiting on a producer.
            try {
                boolean stillOurs;
                synchronized (GLOBAL) { stillOurs = l.owners.get(ticket.id) == ticket; }
                if (!stillOurs) continue;
                try { ((ServerResourcePackLoader) ticket.loader).remove(ticket.id); }
                catch (Throwable failure) { diagnose(s, "Guarded owned UUID remove failed", failure); return; }
                // No receipt forged here: transformed removed() must have observed the real return.
                synchronized (GLOBAL) { if (l.owners.get(ticket.id) == ticket) return; }
            } finally { l.operations.unlock(); }
        }
        boolean allowed;
        synchronized (GLOBAL) {
            boolean unexposed = !s.exposed && !s.factorySeen && s.openWrappers.isEmpty() && s.rawOperations == 0;
            allowed = s.uncertainAdds.isEmpty() && s.actualAdds == 0 && (unexposed || exclusionProven(s));
            if (allowed) {
                s.sealed = true; // Atomic with every owned raw open reservation and provider/cycle observation.
            }
        }
        if (!allowed) { waiting(s, "WAIT_RELEASE: current provider/cycle or ownership proof unavailable"); return; }
        if (!s.lease.seal()) { diagnose(s, "Lease quiescence changed at SEAL", null); return; }
        synchronized (GLOBAL) { s.sealReady = true; }
        startClosing(s);
    }
    /** Caller holds GLOBAL; only immutable snapshots/counters, never MC getters. */
    private static boolean exclusionProven(Session s) {
        if (s.loaders.isEmpty()) return false; // No vacuous provider proof for an unexpected factory exposure.
        for (Object loader : s.loaders) {
            LoaderState l = LOADERS.get(loader);
            if (l == null || l.provider == null || !l.provider.known
                    || l.provider.revision <= s.exclusionAfter || intersects(l.provider.ownedPaths, s.paths)
                    || l.owners.values().stream().anyMatch(t -> t.lease == s.lease)) return false;
        }
        ClientState client = CLIENTS.get(s.client);
        if (client == null || client.current == null || client.current.state != CycleState.SUCCESS
                || client.current.installSerial != client.serial || intersects(client.current.paths, s.paths)) return false;
        for (Cycle pending : client.pending) if (intersects(pending.paths, s.paths)) return false;
        return true;
    }
    private static boolean intersects(Iterable<Path> paths, Set<Path> wanted) {
        for (Path path : paths) if (wanted.contains(path)) return true;
        return false;
    }

    public static ZipResourcePack.ZipBackedFactory newZipFactory(Path path) {
        Binding binding;
        synchronized (GLOBAL) {
            binding = OWNED.get(key(path));
            if (binding != null) binding.session.factorySeen = true;
        }
        return binding == null ? new ZipResourcePack.ZipBackedFactory(path) : new TrackingFactory(path, binding);
    }
    /** Vanilla super calls, final-object identity (including overlays), no profile-ID parsing. */
    private static final class TrackingFactory extends ZipResourcePack.ZipBackedFactory {
        private final Binding binding;
        TrackingFactory(Path path, Binding binding) { super(path); this.binding = binding; }
        @Override public ResourcePack open(ResourcePackInfo info) { return remember(super.open(info)); }
        @Override public ResourcePack openWithOverlays(ResourcePackInfo info, ResourcePackProfile.Metadata metadata) {
            return remember(super.openWithOverlays(info, metadata));
        }
        private ResourcePack remember(ResourcePack pack) {
            synchronized (GLOBAL) { if (!binding.session.sealed) PACKS.put(pack, binding); }
            return pack;
        }
    }
    public static Ticket beforePathAdd(Object loader, UUID id, Path path) {
        Path normalized = key(path);
        Binding binding;
        synchronized (GLOBAL) {
            binding = OWNED.get(normalized);
            if (TOMBSTONES.containsKey(normalized.getParent())) throw new IllegalStateException("Retired owned task: " + normalized);
        }
        ResourcePackLease.Permit permit = null;
        if (binding != null) {
            Submission submitted = SUBMITTING.get();
            if (submitted == null || submitted.lease != binding.session.lease || !submitted.path.equals(normalized)) {
                throw new IllegalStateException("Owned add outside explicit replay admission");
            }
            try { permit = submitted.lease.admission(submitted.generation); }
            catch (IOException stopped) { throw new IllegalStateException("Stale/terminal actual replay add", stopped); }
        }
        return beginAdd(loader, id, normalized, binding, permit);
    }
    public static Ticket beforeUrlAdd(Object loader, UUID id) { return beginAdd(loader, id, null, null, null); }
    private static Ticket beginAdd(Object loader, UUID id, Path path, Binding binding, ResourcePackLease.Permit permit) {
        LoaderState l;
        synchronized (GLOBAL) { l = LOADERS.computeIfAbsent(loader, ignored -> new LoaderState()); }
        l.operations.lock(); // Serializes exact actual attempts and guarded own removal; no GLOBAL/lease lock held.
        if (permit != null && !permit.valid()) {
            l.operations.unlock();
            permit.close();
            throw new IllegalStateException("Actual add became terminal/stale while waiting for loader operation");
        }
        Submission submitted = SUBMITTING.get();
        synchronized (GLOBAL) {
            if (binding != null && (binding.session.sealed || submitted == null
                    || submitted.loader != loader || submitted.connectionEpoch != l.epoch)) {
                l.operations.unlock();
                // Permit release outside GLOBAL below through explicit exceptional path is necessary.
            } else {
                ResourcePackLease lease = binding == null ? null : binding.session.lease;
                Ticket ticket = new Ticket(loader, l.epoch, id, ++sequence, lease, path);
                ATTEMPTS.put(ticket, new Attempt(ticket, permit, l, l.owners.get(id)));
                if (binding != null) {
                    Session s = binding.session;
                    s.actualAdds++; s.exposed = true; s.loaders.add(loader);
                    s.exclusionAfter = Math.max(s.exclusionAfter, l.revision);
                }
                return ticket;
            }
        }
        if (permit != null) permit.close();
        throw new IllegalStateException("Retired owned ZIP add");
    }
    /** Must not mask the original manager Throwable, including foreign failed replacement. */
    public static void addFinished(Ticket ticket, Throwable failure) {
        Attempt attempt;
        List<Session> affected = new ArrayList<>(2);
        synchronized (GLOBAL) {
            attempt = ATTEMPTS.remove(ticket);
            if (attempt == null) return;
            LoaderState l = attempt.loader;
            Ticket current = l.owners.get(ticket.id);
            Ticket old = attempt.previous;
            if (failure == null) {
                // Same-loader operations are serialized. Foreign replacement drops ownership,
                // not the old session's independent provider/raw debt.
                if (current == null || current.addSequence < ticket.addSequence) l.owners.put(ticket.id, ticket);
            } else {
                // A throwing manager may have installed either entry. Neither is a known
                // current owner: never issue a remove using that stale UUID ticket. Keep
                // exact attempt debt on both affected leases, not on future submissions.
                // Reentrant manager callbacks may already have installed a newer ticket.
                // The failing outer attempt has no authority over that successful owner.
                if (current != null && current.addSequence <= ticket.addSequence) l.owners.remove(ticket.id);
                AddUncertainty debt = new AddUncertainty(ticket.id, ticket.connectionEpoch,
                        ticket.addSequence, old == null ? 0 : old.addSequence);
                if (old != null && old.lease != null) {
                    Session s = SESSIONS.get(old.lease);
                    if (s != null) { s.uncertainAdds.add(debt); affected.add(s); }
                }
                if (ticket.lease != null) {
                    Session s = session(ticket.lease);
                    s.uncertainAdds.add(debt);
                    if (!affected.contains(s)) affected.add(s);
                }
                // Same-thread reentrancy is the only overlap allowed by operations. An
                // older unfinished same-UUID attempt can also have partially exposed its
                // path; preserve that debt, but never poison a newer successful owner.
                for (Attempt inFlight : ATTEMPTS.values()) {
                    Ticket other = inFlight.ticket;
                    if (other.loader == ticket.loader && java.util.Objects.equals(other.id, ticket.id)
                            && other.addSequence < ticket.addSequence && other.lease != null) {
                        Session s = session(other.lease);
                        s.uncertainAdds.add(debt);
                        if (!affected.contains(s)) affected.add(s);
                    }
                }
            }
            if (ticket.lease != null) session(ticket.lease).actualAdds--;
        }
        try { if (attempt.permit != null) attempt.permit.close(); }
        catch (Throwable callbackFailure) { LOGGER.log(System.Logger.Level.WARNING, "Add permit release failed", callbackFailure); }
        finally { attempt.loader.operations.unlock(); }
        try {
            for (Session s : affected) diagnose(s, "Uncertain actual add; UUID=" + ticket.id
                    + "; connectionEpoch=" + ticket.connectionEpoch + "; failedSequence=" + ticket.addSequence, failure);
            wake();
        } catch (Throwable callbackFailure) {
            // Called from a manager finally: observer diagnostics must not replace its Throwable.
            if (failure != null && callbackFailure != failure) failure.addSuppressed(callbackFailure);
            else LOGGER.log(System.Logger.Level.WARNING, "Add completion wake failed", callbackFailure);
        }
    }
    public static void removed(Object loader, UUID id) {
        synchronized (GLOBAL) {
            LoaderState l = LOADERS.computeIfAbsent(loader, ignored -> new LoaderState());
            l.owners.remove(id);
        }
        wake();
    }
    public static void cleared(Object loader) {
        synchronized (GLOBAL) { LOADERS.computeIfAbsent(loader, ignored -> new LoaderState()).owners.clear(); }
        wake(); // Observation only: this bridge NEVER issues clear/removeAll itself.
    }
    public static void connectionChanged(Object loader, Object connection) {
        synchronized (GLOBAL) { LOADERS.computeIfAbsent(loader, ignored -> new LoaderState()).epoch++; }
        wake(); // Epoch changes do not erase old tickets or path debt. No connection retained.
    }
    public static void profilesCreated(Object loader, List<?> packInfos, List<ResourcePackProfile> profiles) {
        List<Path> reversed = new ArrayList<>();
        for (int i = packInfos.size() - 1; i >= 0; i--) {
            Object info = packInfos.get(i);
            if (!(info instanceof ReloadScheduler.PackInfo pack)) return;
            reversed.add(key(pack.path()));
        }
        synchronized (GLOBAL) {
            PROFILES.prune(); PACKS.prune(); PROVIDERS.prune();
            // null means metadata FAILED, not a successful empty list. Only the actual
            // later getPackProvider(empty) or forced NOOP write can establish empty evidence.
            if (profiles == null || profiles.size() != reversed.size()) return;
            for (int i = 0; i < profiles.size(); i++) PROFILES.put(profiles.get(i), reversed.get(i));
        }
    }
    public static void providerCreated(ResourcePackProvider provider, List<ResourcePackProfile> profiles) {
        List<ResourcePackProfile> frozen = List.copyOf(profiles);
        synchronized (GLOBAL) {
            Set<Path> paths = new HashSet<>();
            boolean known = true;
            for (ResourcePackProfile profile : frozen) {
                Path path = PROFILES.get(profile);
                if (path == null) known = false; else paths.add(path);
            }
            PROVIDERS.put(provider, new ProviderEvidence(frozen, Set.copyOf(paths), known));
        }
    }
    /** knownNoop comes from identity comparison with Loader's actual NOOP field at the write. */
    public static void providerInstalled(Object loader, Object context, ResourcePackProvider provider, boolean knownNoop) {
        synchronized (GLOBAL) {
            LoaderState l = LOADERS.computeIfAbsent(loader, ignored -> new LoaderState());
            ProviderEvidence evidence = PROVIDERS.get(provider);
            long revision = ++sequence;
            l.revision = revision;
            l.provider = knownNoop ? new ProviderSnapshot(revision, List.of(), List.of(), true, true)
                    : new ProviderSnapshot(revision, evidence == null ? List.of() : evidence.profiles,
                            evidence == null ? List.of() : List.copyOf(evidence.paths), evidence != null && evidence.known, false);
            // Context is deliberately not retained/read. Effective immutable provider evidence
            // was captured at creation, including metadata fallback, not mutable ctx.getPacks().
        }
        wake();
    }
    public static void cycleStarting(Object client, CompletableFuture<Void> future, List<ResourcePack> packs) {
        synchronized (GLOBAL) {
            ClientState c = CLIENTS.computeIfAbsent(client, ignored -> new ClientState());
            Set<Path> paths = new HashSet<>();
            for (ResourcePack pack : packs) { Binding binding = PACKS.get(pack); if (binding != null) paths.add(binding.path); }
            c.pending.add(new Cycle(++sequence, future, packs, paths));
        }
    }
    private static Cycle incoming(ClientState c, List<ResourcePack> packs) {
        if (c == null) return null;
        Cycle match = null;
        for (Cycle cycle : c.pending) if (cycle.incomingIdentity != null && cycle.incomingIdentity.get() == packs) {
            if (match != null) {
                for (Cycle ambiguous : c.pending) if (ambiguous.incomingIdentity != null
                        && ambiguous.incomingIdentity.get() == packs) ambiguous.finalObjects = null;
                return null; // Compact unresolved path debt, never choose an arbitrary F.
            }
            match = cycle;
        }
        return match;
    }
    public static void cycleInstalled(Object client, List<ResourcePack> packs) {
        synchronized (GLOBAL) {
            ClientState c = CLIENTS.computeIfAbsent(client, ignored -> new ClientState());
            Cycle cycle = incoming(c, packs);
            c.serial++;
            c.current = cycle;
            if (cycle != null) {
                boolean sameObjects = cycle.finalObjects != null && packs.size() == cycle.finalObjects.size();
                for (int i = 0; sameObjects && i < packs.size(); i++) sameObjects = packs.get(i) == cycle.finalObjects.get(i);
                cycle.incomingIdentity = null; cycle.finalObjects = null;
                cycle.installSerial = c.serial;
                cycle.state = sameObjects ? CycleState.INSTALLED : CycleState.UNKNOWN;
                c.pending.remove(cycle);
            }
        }
        wake();
    }
    public static void cycleInstallFailed(Object client, List<ResourcePack> packs, Throwable failure) {
        synchronized (GLOBAL) {
            ClientState c = CLIENTS.computeIfAbsent(client, ignored -> new ClientState());
            Cycle cycle = incoming(c, packs);
            c.serial++; c.current = null; // May already have replaced manager: invalidate historical success.
            if (cycle != null) {
                cycle.state = CycleState.UNKNOWN; cycle.incomingIdentity = null; cycle.finalObjects = null; c.pending.remove(cycle);
            }
        }
        wake();
    }
    public static void cycleFailed(Object client, CompletableFuture<Void> future, Throwable failure) {
        synchronized (GLOBAL) {
            ClientState c = CLIENTS.get(client);
            if (c != null) {
                if (c.current != null && c.current.future.get() == future) c.current.state = CycleState.FAILED;
                c.pending.removeIf(cycle -> cycle.future.get() == future);
            }
        }
        wake();
    }
    public static void cycleSucceeded(Object client, CompletableFuture<Void> future) {
        synchronized (GLOBAL) {
            ClientState c = CLIENTS.get(client);
            if (c != null && c.current != null && c.current.future.get() == future
                    && c.current.installSerial == c.serial && c.current.state == CycleState.INSTALLED
                    && future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) c.current.state = CycleState.SUCCESS;
        }
        wake(); // Only $20's NORMAL RETURN invokes this, never a thenRun on F.
    }

    /** Constructor binding includes metadata, fresh factories and factory failures. */
    public static OwnedZip bindZip(Object wrapper, File file) {
        Path path = key(file.toPath());
        synchronized (GLOBAL) {
            Binding binding = OWNED.get(path);
            if (binding != null) { binding.session.factorySeen = true; return new OwnedZip(binding.session, path); }
            if (TOMBSTONES.containsKey(path.getParent())) return new OwnedZip(null, path);
        }
        return null; // Foreign path: every vanilla behavior remains untouched.
    }
    public static final class OwnedZip {
        private final Session session;
        private final Path path;
        private ZipFile raw;
        private RawCloseState closeState = RawCloseState.SUCCESS;
        private OwnedZip(Session session, Path path) { this.session = session; this.path = path; }
        /** Wrapper serialization prevents lost raw identities; IO occurs outside GLOBAL. */
        public synchronized ZipFile open() {
            if (session == null) return null;
            synchronized (GLOBAL) {
                if (session.sealed) return null;
                if (raw != null) return raw;
                session.rawOperations++;
            }
            ZipFile opened = null;
            Throwable failure = null;
            try {
                session.lease.checkFile(path, false);
                opened = new ZipFile(path.toFile());
            } catch (Throwable thrown) { failure = thrown; }
            boolean sealed;
            synchronized (GLOBAL) {
                if (opened != null) {
                    raw = opened; closeState = RawCloseState.OPEN; session.openWrappers.add(this);
                }
                session.rawOperations--;
                if (failure != null && !(failure instanceof IOException)) session.poisoned = true;
                sealed = session.sealed;
            }
            if (failure != null) diagnose(session, "Owned ZIP open failed: " + path, failure);
            if (sealed && opened != null) { close(); opened = null; }
            if (sealed) startClosing(session);
            if (failure instanceof Error fatal) throw fatal;
            return opened;
        }
        /** FIRST raw.close outcome is sticky from ACTIVE; repeated empty close cannot wash failure. */
        public synchronized void close() {
            if (session == null || raw == null) return;
            ZipFile closing = raw;
            raw = null;
            synchronized (GLOBAL) { closeState = RawCloseState.CLOSING; session.rawOperations++; }
            Throwable failure = null;
            try { closing.close(); }
            catch (Throwable thrown) { failure = thrown; }
            synchronized (GLOBAL) {
                closeState = failure == null ? RawCloseState.SUCCESS : RawCloseState.FAILED;
                session.rawOperations--;
                session.openWrappers.remove(this);
                if (failure != null) session.poisoned = true;
            }
            if (failure != null) diagnose(session, "FIRST_CLOSE failed (never retried): " + path, failure);
            startClosing(session); // Checks sealReady under GLOBAL; no unsynchronized state read.
            // MC's close() has no checked exception; sticky poison is the permanent receipt.
            if (failure instanceof Error fatal) throw fatal;
        }
    }
    private static void startClosing(Session s) {
        synchronized (GLOBAL) {
            if (!s.sealReady || s.closing || s.finished) return;
            s.closing = true;
        }
        ForkJoinPool.commonPool().execute(() -> {
            List<OwnedZip> wrappers;
            synchronized (GLOBAL) { wrappers = List.copyOf(s.openWrappers); }
            for (OwnedZip wrapper : wrappers) {
                try { wrapper.close(); }
                catch (Throwable failure) { diagnose(s, "Retired raw close failed", failure); }
            }
            boolean clean, more;
            synchronized (GLOBAL) {
                s.closing = false;
                clean = !s.poisoned && s.rawOperations == 0 && s.openWrappers.isEmpty();
                // A raw may have registered AFTER this batch's snapshot. Drain that actual work,
                // not a timer/poll: otherwise its completion signal could be lost while closing.
                more = s.rawOperations == 0 && !s.openWrappers.isEmpty();
                if (clean) s.finished = true;
            }
            if (!clean) {
                if (more) startClosing(s);
                waiting(s, "WAIT_RELEASE: first close failed or ZIP operation still in flight");
                return;
            }
            try {
                s.lease.authorizeCleanup();
                s.lease.cleanupAfterSeal();
            } catch (IOException | RuntimeException failure) { diagnose(s, "Exact owned file cleanup failed", failure); }
            finally {
                Path task = s.lease.taskDirectory();
                UUID token = s.lease.token();
                synchronized (GLOBAL) {
                    if (task != null) TOMBSTONES.put(task, new Tombstone(task, token));
                    OWNED.entrySet().removeIf(e -> e.getValue().session == s);
                    PACKS.removeSession(s);
                    SESSIONS.remove(s.lease);
                    // Other sessions and current UUID owners are not touched.
                }
                s.lease.onProgress(() -> { });
            }
        });
    }
}
