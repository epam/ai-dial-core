package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.orgunit.OrgUnit;
import com.epam.aidial.core.server.data.orgunit.OrgUnitData;
import com.epam.aidial.core.server.data.orgunit.OrgUnitList;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Org units as folders of the shared space, recorded in a unit tree next to the rules document. One tree key covers
 * the unit's folder in every content type tree.
 *
 * <p>A resource belongs to the nearest unit above it, whatever its type: the type folder is dropped and the
 * longest matching key wins, so {@code files/public/Risk/US Risk/a.pdf} and {@code applications/public/Risk/US Risk/x}
 * both belong to {@code public/Risk/US Risk/}. Two invariants keep that unambiguous: a unit's parent is a unit or the
 * shared space, and a new unit never takes the name of an existing folder or item in any type tree.
 *
 * <p>The shared space itself is not a unit: what lies under no unit stays with the DIAL admins. The customer's
 * organization is the first unit below it, such as {@code public/Acme/}.
 *
 * <p>Every unit gets a folder in the files tree when it is created. Folders exist only as prefixes of items, so a
 * unit without files gets the clients' hidden empty-folder marker, {@link #FOLDER_MARKER}; that is where Chat and the admin panel look
 * for folders.
 *
 * <p>The tree is read through the shared resource cache and decoded again only when its etag changes. An unreadable
 * tree fails the request.
 */
@Slf4j
public class OrgUnitService {

    static final String SHARED_SPACE_KEY = ResourceDescriptor.PUBLIC_LOCATION;

    private static final String SHARED_SPACE = ResourceDescriptor.PUBLIC_BUCKET;
    private static final int MAX_DEPTH = 4;

    /**
     * The type trees a unit's folder spans: every type that can be published or moved into the shared space.
     */
    private static final List<ResourceTypes> CONTENT_TYPES = List.of(ResourceTypes.FILE, ResourceTypes.CONVERSATION,
            ResourceTypes.PROMPT, ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET, ResourceTypes.SKILL, ResourceTypes.PROJECT);

    private static final String FOLDER_MARKER = ".dial_folder";

    /**
     * Names a unit can't take: {@code roles} and {@code role-assignments} are segments of the unit routes after a
     * unit's path, and {@code rate} would clash with {@code /v1/{deployment}/rate}.
     */
    private static final Set<String> RESERVED_NAMES = Set.of("roles", "role-assignments", "rate");
    private static final int MAX_DISPLAY_NAME = 128;
    private static final int LIST_PAGE = 1000;
    private static final ResourceDescriptor UNITS = ResourceDescriptorFactory.fromDecoded(
            ResourceTypes.ORG_UNITS, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, "units");

    private final ResourceService resources;
    private final RuleService ruleService;
    private final LockService locks;
    private final OrgUnitSettings settings;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    /**
     * Key is the document's etag, which changes with every write; two writes can share an update time.
     * Value is the decoded list, read-only.
     */
    private final AtomicReference<Pair<String, OrgUnitList>> cached = new AtomicReference<>();

    public OrgUnitService(ResourceService resources, RuleService ruleService, LockService locks, OrgUnitSettings settings,
                          Supplier<String> ids, LongSupplier clock) {
        this.resources = resources;
        this.ruleService = ruleService;
        this.locks = locks;
        this.settings = settings;
        this.ids = ids;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    /**
     * Whether the folder of this name, the resource's own folder for an item, is a unit's: a complex resource keeps
     * its files there. Names compare ignoring case, as on create, since folders that differ only in case are one on
     * some storages.
     */
    public boolean isUnitFolder(ResourceDescriptor resource) {
        if (!isEnabled() || !resource.isPublic()) {
            return false;
        }
        String path = resource.getDecodedUrl().substring(resource.getType().urlSegment().length() + 1);
        String key = resource.isFolder() ? path : path + ResourceDescriptor.PATH_SEPARATOR;
        return getUnitList().units().keySet().stream().anyMatch(key::equalsIgnoreCase);
    }

    /**
     * Reads the list once, so a node that cannot read it refuses to start.
     */
    public void checkOnStartup() {
        if (settings.enabled()) {
            getUnitList();
        }
    }

    /**
     * The unit list as stored now, read-only.
     */
    OrgUnitList getUnitList() {
        Pair<ResourceItemMetadata, String> resource = resources.getResourceWithMetadata(UNITS, EtagHeader.ANY);
        String key = (resource == null) ? "" : resource.getKey().getEtag();
        Pair<String, OrgUnitList> current = cached.get();

        if (current == null || !current.getKey().equals(key)) {
            OrgUnitList decoded = decode(units(parse(resource == null ? null : resource.getValue()))).readOnly();
            Pair<String, OrgUnitList> next = Pair.of(key, decoded);
            cached.compareAndSet(current, next);
            current = next;
        }

        return current.getValue();
    }

    /**
     * The key of the unit that owns the shared-space item, or {@link #SHARED_SPACE_KEY} when no unit is above it.
     */
    static String ownerOf(Map<String, OrgUnit> units, ResourceDescriptor item) {
        String key = keyOf(item.getParent());
        while (!key.equals(SHARED_SPACE_KEY)) {
            if (units.containsKey(key)) {
                return key;
            }
            key = parentKey(key);
        }
        return SHARED_SPACE_KEY;
    }

    /**
     * Refuses a move inside the shared space that would hand an item to another unit. Folders are moved item by item,
     * so this is what stops a unit's folder from being renamed or moved away from its entry. Folder moves are left to
     * the move itself, which refuses them.
     */
    public void requireSameUnit(ResourceDescriptor source, ResourceDescriptor destination) {
        if (!settings.enabled() || !source.isPublic() || !destination.isPublic() || source.isFolder() || destination.isFolder()) {
            return;
        }

        Map<String, OrgUnit> units = getUnitList().units();
        String from = ownerOf(units, source);
        String to = ownerOf(units, destination);
        if (!from.equals(to)) {
            throw new HttpException(HttpStatus.CONFLICT, "Moves between org units aren't allowed: %s belongs to %s, %s to %s. "
                    .formatted(source.getDecodedUrl(), describe(from), destination.getDecodedUrl(), describe(to))
                    + "Publish into the other unit instead.");
        }
    }

    /**
     * Reads the shared space or a unit with the units directly below it, or every unit below when {@code recursive}.
     * An admin sees every unit; anyone else sees the units whose folder the read rules let them see, and a unit they
     * can't see answers as one that doesn't exist.
     *
     * @param path {@code public}, then each unit's name
     */
    public OrgUnitData read(List<String> path, boolean recursive, ProxyContext context, boolean admin) {
        String key = keyOf(requireSharedSpace(path));
        OrgUnitList list = getUnitList();
        Predicate<String> visible = admin ? unit -> true : visibleTo(context, list);
        if (!key.equals(SHARED_SPACE_KEY) && (!list.units().containsKey(key) || !visibleWithParents(key, visible))) {
            throw new ResourceNotFoundException("No org unit: " + String.join("/", path));
        }
        return toData(key, list, recursive, visible);
    }

    /**
     * @param author the display name of who creates it
     */
    public OrgUnitData create(List<String> parentPath, @Nullable String name, @Nullable String displayName, String author) {
        String parentKey = keyOf(requireSharedSpace(parentPath));
        requireName(name);
        requireDisplayName(displayName);
        String key = parentKey + name + ResourceDescriptor.PATH_SEPARATOR;
        for (ResourceTypes type : CONTENT_TYPES) {
            ResourceDescriptorFactory.verifyPathSize(folder(type, key + FOLDER_MARKER));
        }

        OrgUnitData created = locks.underBucketLock(SHARED_SPACE_KEY, () -> createLocked(parentKey, key, name, displayName, author));
        save();
        return created;
    }

    /**
     * Runs under the shared space's bucket lock, which moves and publication approvals into the shared space also
     * take, so neither puts an item into the new unit's folder between the name check and the write. The marker is
     * written under the lock too, so no removal of the unit can come between the two.
     */
    private OrgUnitData createLocked(String parentKey, String key, String name, @Nullable String displayName, String author) {
        requireParentAndFreeName(getUnitList(), parentKey, name);
        if (depth(parentKey) >= MAX_DEPTH) {
            throw new HttpException(HttpStatus.CONFLICT, "Units can be at most %d levels deep".formatted(MAX_DEPTH));
        }
        requireClosed(key);
        requireNoFolderOrItemNamed(parentKey, name);

        OrgUnit unit = new OrgUnit(ids.get(), displayName == null || displayName.isBlank() ? name : displayName,
                author, clock.getAsLong());
        update(list -> list.units().put(key, unit));
        addFolderMarker(key, author);

        return new OrgUnitData(unit.id(), name, unit.displayName(), path(key), encodedFolder(key), unit.author(), unit.createdAt(),
                List.of());
    }

    /**
     * Changes what clients show for a unit; a blank display name falls back to the name, as on create. Only the
     * stored entry's {@code displayName} changes, so fields this node doesn't know stay as stored. The unit's name and
     * folder never change.
     */
    public OrgUnitData updateDisplayName(List<String> path, @Nullable String displayName) {
        String key = keyOf(requireSharedSpace(path));
        if (key.equals(SHARED_SPACE_KEY)) {
            throw new IllegalArgumentException("The shared space is not a unit");
        }
        if (displayName == null) {
            throw new IllegalArgumentException("displayName is the only field a unit's update takes");
        }
        requireDisplayName(displayName);

        String value = displayName.isBlank() ? lastSegment(key) : displayName;
        resources.computeResource(UNITS, body -> {
            ObjectNode document = parse(body);
            if (!(units(document).get(key) instanceof ObjectNode entry)) {
                throw new ResourceNotFoundException("No org unit: " + String.join("/", path));
            }
            entry.put("displayName", value);
            return document.toString();
        });
        save();
        return read(path, false, null, true);
    }

    /**
     * Removes an empty unit; with {@code recursive}, every unit below it too. A unit is empty when its folder holds
     * nothing but empty-folder markers in the files tree and nothing at all in any other type tree, the subtree of its
     * child units included. Its tree entries and the markers in its subtree go, and so do the read rules of its subtree
     * unless content landed in it meanwhile.
     *
     * @return the paths of the removed units, deepest first
     */
    public List<String> delete(List<String> path, boolean recursive) {
        String key = keyOf(requireSharedSpace(path));
        if (key.equals(SHARED_SPACE_KEY)) {
            throw new IllegalArgumentException("The shared space is not a unit");
        }

        List<String> removed = locks.underBucketLock(SHARED_SPACE_KEY, () -> deleteLocked(path, key, recursive));
        save();
        return removed.stream().map(OrgUnitService::path).toList();
    }

    /**
     * Runs under the shared space's bucket lock, which create, moves and publication approvals into the shared space
     * also take, so none of them puts anything into the unit between the emptiness check and the removal. A direct
     * write doesn't take that lock: if one lands meanwhile, the subtree's read rules stay, so its content stays as
     * closed as it was.
     *
     * <p>The markers go before the tree entries: until the entries are gone the unit still exists, so a removal that
     * fails part way can be repeated, and once they are gone no marker is left to keep the name taken.
     *
     * @return the keys of the removed units, deepest first
     */
    private List<String> deleteLocked(List<String> path, String key, boolean recursive) {
        List<String> removed = new ArrayList<>(unitsBelow(getUnitList(), key, path, recursive));
        List<ResourceDescriptor> markers = requireEmpty(key, removed);
        removed.add(key);

        for (ResourceDescriptor marker : markers) {
            resources.deleteResource(marker, EtagHeader.ANY);
        }
        update(list -> removed.forEach(list.units()::remove));
        if (isStillEmpty(key)) {
            removeRulesUnder(key);
        }
        return removed;
    }

    /**
     * The unit is gone by now, so a failure here only leaves the subtree's read rules, which keep its folder closed.
     */
    private void removeRulesUnder(String key) {
        try {
            ruleService.removeRulesUnder(folder(ResourceTypes.FILE, key));
        } catch (RuntimeException e) {
            log.warn("The read rules of {} stay after its removal: {}", describe(key), e.getMessage());
        }
    }

    /**
     * Whether the subtree is still empty once its markers are gone. Content that landed meanwhile, or a listing that
     * fails, keeps the subtree's read rules: the unit is gone either way, and its content stays as closed as it was.
     */
    private boolean isStillEmpty(String key) {
        try {
            List<String> content = listSubtree(key).content();
            if (content.isEmpty()) {
                return true;
            }
            log.warn("Content landed in {} while it was removed, so its read rules stay: {}", describe(key), content);
        } catch (RuntimeException e) {
            log.warn("{} couldn't be listed after its removal, so its read rules stay: {}", describe(key), e.getMessage());
        }
        return false;
    }

    /**
     * The units below the key, deepest first; refuses them unless {@code recursive}.
     */
    private static List<String> unitsBelow(OrgUnitList list, String key, List<String> path, boolean recursive) {
        if (!list.units().containsKey(key)) {
            throw new ResourceNotFoundException("No org unit: " + String.join("/", path));
        }

        List<String> below = list.units().keySet().stream()
                .filter(unit -> unit.startsWith(key) && !unit.equals(key))
                .sorted(Comparator.comparingInt(OrgUnitService::depth).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        if (!below.isEmpty() && !recursive) {
            throw new HttpException(HttpStatus.CONFLICT, "%s has units below it: %s. Remove them first, or remove all with recursive=true"
                    .formatted(describe(key), String.join(", ", below.stream().map(OrgUnitService::describe).toList())));
        }
        return below;
    }

    /**
     * Refuses a unit whose subtree holds anything but empty-folder markers in the files tree, naming up to three items.
     *
     * @return the markers to remove with it
     */
    private List<ResourceDescriptor> requireEmpty(String key, List<String> below) {
        Subtree subtree = listSubtree(key);
        List<String> content = subtree.content();
        if (!content.isEmpty()) {
            String units = below.isEmpty() ? describe(key) + " still holds" : describe(key) + " and the units below it still hold";
            String shown = String.join(", ", content.subList(0, Math.min(3, content.size()))) + (content.size() > 3 ? ", …" : "");
            throw new HttpException(HttpStatus.CONFLICT, "%s content: %s. Delete or move it first.".formatted(units, shown));
        }
        return subtree.markers();
    }

    /**
     * The subtree's empty-folder markers, in the files tree and the grouping folders of the skills and projects trees,
     * and its other items in every type tree. Stops listing once more than three items are found: enough to name them.
     */
    private Subtree listSubtree(String key) {
        List<ResourceDescriptor> markers = new ArrayList<>();
        List<String> content = new ArrayList<>();
        for (ResourceTypes type : CONTENT_TYPES) {
            if (content.size() > 3) {
                break;
            }
            String token = null;
            do {
                ResourceFolderMetadata page = resources.getFolderMetadata(folder(type, key), token, LIST_PAGE, true);
                if (page == null) {
                    break;
                }
                for (MetadataBase item : page.getItems()) {
                    if (isMarker(type, item.getName())) {
                        markers.add(((ResourceItemMetadata) item).getDescriptor());
                    } else {
                        content.add(item.getUrl());
                    }
                }
                token = content.size() > 3 ? null : page.getNextToken();
            } while (token != null);
        }
        return new Subtree(markers, content);
    }

    private record Subtree(List<ResourceDescriptor> markers, List<String> content) {
    }

    private static boolean isMarker(ResourceTypes type, String name) {
        return type == ResourceTypes.FILE ? FOLDER_MARKER.equals(name) : type.isComplex() && ComplexResourceService.FOLDER_MARKER_NAME.equals(name);
    }

    /**
     * Refuses a unit that no read rule closes: the unit's folder or a folder above it, below the shared space, must have
     * rules of its own that don't let everyone in. Rules of every level must pass, so one closing level closes
     * everything below it.
     */
    private void requireClosed(String key) {
        for (String current = key; !current.equals(SHARED_SPACE_KEY); current = parentKey(current)) {
            if (ruleService.hasOwnClosingRules(folder(ResourceTypes.FILE, current))) {
                return;
            }
        }
        throw new HttpException(HttpStatus.CONFLICT, (ruleService.hasOwnRules(folder(ResourceTypes.FILE, key))
                ? "The read rule of %s lets everyone in, and no folder above it has a read rule that closes it; a unit must not be open to everyone"
                : "Add a read rule to %s or to a unit above it before creating the unit: a unit without one is open to everyone").formatted(key));
    }

    /**
     * Refuses a name that a folder or item under the parent already has, in any letter case and any type tree, so the
     * unit never takes over content it wasn't created for. Stops at the first match.
     */
    private void requireNoFolderOrItemNamed(String parentKey, String name) {
        for (ResourceTypes type : CONTENT_TYPES) {
            String token = null;
            do {
                ResourceFolderMetadata page = resources.getFolderMetadata(folder(type, parentKey), token, LIST_PAGE, false);
                if (page == null) {
                    break;
                }
                for (MetadataBase child : page.getItems()) {
                    if (child.getName().equalsIgnoreCase(name)) {
                        String kind = child.getNodeType() == NodeType.FOLDER ? "a folder" : "an item";
                        throw new HttpException(HttpStatus.CONFLICT, "%s already has %s named %s. Choose another name."
                                .formatted(describe(parentKey), kind, child.getName()));
                    }
                }
                token = page.getNextToken();
            } while (token != null);
        }
    }

    /**
     * Applies the change to the stored JSON entry by entry: entries the change leaves alone, and fields this node
     * doesn't know, are written back as they were, so a node running an older version can't strip a newer one's data.
     * A {@code public/} entry is dropped: the shared space is never a unit. Callers {@link #save} once they have
     * released the shared space's lock.
     */
    private void update(Consumer<OrgUnitList> change) {
        resources.computeResource(UNITS, body -> {
            ObjectNode document = parse(body);
            ObjectNode raw = units(document);
            OrgUnitList list = decode(raw);
            Map<String, OrgUnit> before = new HashMap<>(list.units());
            list.units().remove(SHARED_SPACE_KEY);
            change.accept(list);
            writeBack(raw, before, list.units());
            return document.toString();
        });
    }

    /**
     * Drops the entries the change removed and rewrites the ones it replaced; every other entry stays as stored.
     */
    private static void writeBack(ObjectNode raw, Map<String, OrgUnit> before, Map<String, OrgUnit> after) {
        raw.retain(after.keySet());
        after.forEach((key, unit) -> {
            if (before.get(key) != unit) {
                raw.set(key, ProxyUtil.MAPPER.valueToTree(unit));
            }
        });
    }

    /**
     * Saves the list to the blob store now rather than at the next sync, since a change lost with Redis could restore a
     * removed unit or undo a created one. Runs outside the shared space's lock, so a slow blob store doesn't hold up moves and approvals.
     */
    private void save() {
        try {
            resources.syncNow(UNITS);
        } catch (RuntimeException e) {
            log.warn("The org unit tree reaches the blob store at the next sync instead: {}", e.getMessage());
        }
    }

    /**
     * Refuses a document that isn't a JSON object rather than reading it as empty: an empty list would hand every
     * unit's content to the shared space.
     */
    private static ObjectNode parse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return ProxyUtil.MAPPER.createObjectNode();
        }

        JsonNode node;
        try {
            node = ProxyUtil.MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The org unit list can't be read: " + e.getOriginalMessage());
        }
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("The org unit list can't be read: it is not a JSON object");
    }

    private static ObjectNode units(ObjectNode document) {
        JsonNode units = document.get("units");
        if (units == null || units.isNull()) {
            return document.putObject("units");
        }
        if (units instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("The org unit list can't be read: units is not an object");
    }

    private static OrgUnitList decode(ObjectNode raw) {
        Map<String, OrgUnit> units = new TreeMap<>();
        raw.fields().forEachRemaining(field -> units.put(field.getKey(), decodeUnit(field.getValue())));
        return new OrgUnitList(units);
    }

    /**
     * Keeps a unit whose entry is partly unreadable, as dropping it would hand its content to the unit above.
     */
    private static OrgUnit decodeUnit(JsonNode entry) {
        JsonNode createdAt = entry.get("createdAt");
        return new OrgUnit(text(entry, "id"), text(entry, "displayName"), text(entry, "author"),
                createdAt != null && createdAt.isIntegralNumber() ? createdAt.longValue() : null);
    }

    @Nullable
    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static List<String> requireSharedSpace(List<String> path) {
        if (path.isEmpty() || !SHARED_SPACE.equals(path.get(0))) {
            throw new ResourceNotFoundException("Not the shared space: " + (path.isEmpty() ? "" : path.get(0)));
        }
        return path.subList(1, path.size());
    }

    private void requireParentAndFreeName(OrgUnitList list, String parentKey, String name) {
        if (!parentKey.equals(SHARED_SPACE_KEY) && !list.units().containsKey(parentKey)) {
            throw new ResourceNotFoundException("No org unit: " + describe(parentKey));
        }

        String sibling = siblingInOtherCase(list.units().keySet(), parentKey + name + ResourceDescriptor.PATH_SEPARATOR);
        if (sibling != null) {
            throw new HttpException(HttpStatus.CONFLICT, "%s already has a unit named %s".formatted(describe(parentKey), sibling));
        }
    }

    /**
     * The name of a unit next to the key whose name matches it ignoring case, the key itself included.
     */
    @Nullable
    private static String siblingInOtherCase(Set<String> units, String key) {
        String parent = parentKey(key);
        String name = lastSegment(key);
        for (String unit : units) {
            if (!unit.equals(SHARED_SPACE_KEY) && parentKey(unit).equals(parent) && lastSegment(unit).equalsIgnoreCase(name)) {
                return lastSegment(unit);
            }
        }
        return null;
    }

    /**
     * A folder name core accepts, which also does not start with a dot, end with a space or a dot, or take a reserved name.
     */
    private static void requireName(@Nullable String name) {
        if (name == null) {
            throw new IllegalArgumentException("A unit needs a name");
        }
        if (!ResourceDescriptorFactory.isValidFilename(name) || name.startsWith(".") || name.endsWith(" ") || name.endsWith(".")) {
            throw new IllegalArgumentException("A unit name has no control characters or any of / { } \", "
                    + "doesn't start with \".\" and doesn't end with a space or \".\"");
        }
        if (RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("%s is reserved".formatted(name));
        }
    }

    /**
     * Puts the empty-folder marker into the unit's files folder when that folder holds nothing yet. The unit is recorded
     * by then, so a failure here leaves a unit that clients show only once something is published into it.
     */
    private void addFolderMarker(String key, String author) {
        try {
            ResourceFolderMetadata first = resources.getFolderMetadata(folder(ResourceTypes.FILE, key), null, 1, false);
            if (first != null && !first.getItems().isEmpty()) {
                return;
            }
            resources.putFile(folder(ResourceTypes.FILE, key + FOLDER_MARKER), new byte[0], EtagHeader.ANY, "text/plain", author);
        } catch (RuntimeException e) {
            log.warn("The empty-folder marker of org unit {} wasn't written: {}", key, e.getMessage());
        }
    }

    private static void requireDisplayName(@Nullable String displayName) {
        if (displayName != null && (displayName.length() > MAX_DISPLAY_NAME || displayName.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("A display name has at most %d characters and no control characters".formatted(MAX_DISPLAY_NAME));
        }
    }

    /**
     * The units whose folder the read rules let the caller see, evaluated once over the list the request reads.
     */
    private Predicate<String> visibleTo(ProxyContext context, OrgUnitList list) {
        Map<ResourceDescriptor, String> keysByFolder = list.units().keySet().stream()
                .filter(key -> !key.equals(SHARED_SPACE_KEY))
                .collect(Collectors.toMap(key -> folder(ResourceTypes.FILE, key), key -> key));
        Set<String> visible = ruleService.getAllowedPublicResources(context, keysByFolder.keySet()).stream()
                .map(keysByFolder::get)
                .collect(Collectors.toSet());
        return visible::contains;
    }

    private static OrgUnitData toData(String key, OrgUnitList list, boolean recursive, Predicate<String> visible) {
        List<OrgUnitData.Summary> items = new ArrayList<>();
        collect(key, list, visible, recursive, items);

        OrgUnitData.Summary self = summary(key, list.units().get(key));
        return new OrgUnitData(self.id(), self.name(), self.displayName(), self.path(), self.folder(),
                self.author(), self.createdAt(), items);
    }

    private static void collect(String key, OrgUnitList list, Predicate<String> visible, boolean recursive, List<OrgUnitData.Summary> items) {
        for (Map.Entry<String, OrgUnit> entry : list.units().entrySet()) {
            String child = entry.getKey();
            if (child.equals(SHARED_SPACE_KEY) || !parentKey(child).equals(key) || !visible.test(child)) {
                continue;
            }

            items.add(summary(child, entry.getValue()));
            if (recursive) {
                collect(child, list, visible, true, items);
            }
        }
    }

    private static OrgUnitData.Summary summary(String key, @Nullable OrgUnit unit) {
        if (key.equals(SHARED_SPACE_KEY)) {
            return new OrgUnitData.Summary(SHARED_SPACE, SHARED_SPACE, SHARED_SPACE, path(key), encodedFolder(key), null, null);
        }
        String name = lastSegment(key);
        return new OrgUnitData.Summary(unit.id(), name, unit.displayName() == null ? name : unit.displayName(), path(key), encodedFolder(key),
                unit.author(), unit.createdAt());
    }

    private static boolean visibleWithParents(String key, Predicate<String> visible) {
        for (String current = key; !current.equals(SHARED_SPACE_KEY); current = parentKey(current)) {
            if (!visible.test(current)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(String key) {
        return key.equals(SHARED_SPACE_KEY) ? SHARED_SPACE : String.join(" › ", segments(key));
    }

    private static String path(String key) {
        List<String> parts = new ArrayList<>();
        parts.add(SHARED_SPACE);
        parts.addAll(segments(key));
        return String.join(ResourceDescriptor.PATH_SEPARATOR, parts);
    }

    /**
     * The descriptor of a key, or of a key followed by a name, in the given type tree.
     */
    private static ResourceDescriptor folder(ResourceTypes type, String key) {
        return ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION,
                key.substring(SHARED_SPACE_KEY.length()));
    }

    private static String keyOf(@Nullable ResourceDescriptor folder) {
        if (folder == null) {
            return SHARED_SPACE_KEY;
        }

        String url = folder.getDecodedUrl();
        return url.substring(folder.getType().urlSegment().length() + 1);
    }

    private static String keyOf(List<String> unitPath) {
        StringBuilder key = new StringBuilder(SHARED_SPACE_KEY);
        for (String segment : unitPath) {
            key.append(segment).append(ResourceDescriptor.PATH_SEPARATOR);
        }
        return key.toString();
    }

    /**
     * The key url-encoded, as publications take {@code targetFolder}: {@code public/Acme/Risk/US%20Risk/}.
     */
    private static String encodedFolder(String key) {
        return RuleService.ruleUrl(folder(ResourceTypes.FILE, key));
    }

    private static String parentKey(String key) {
        int end = key.lastIndexOf('/', key.length() - 2);
        return key.substring(0, end + 1);
    }

    private static int depth(String key) {
        return segments(key).size();
    }

    private static String lastSegment(String key) {
        List<String> segments = segments(key);
        return segments.get(segments.size() - 1);
    }

    private static List<String> segments(String key) {
        String rest = key.substring(SHARED_SPACE_KEY.length());
        return rest.isEmpty() ? List.of() : List.of(rest.substring(0, rest.length() - 1).split("/", -1));
    }
}
