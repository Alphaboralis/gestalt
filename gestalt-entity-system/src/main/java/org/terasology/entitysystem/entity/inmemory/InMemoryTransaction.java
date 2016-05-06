/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.entitysystem.entity.inmemory;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TLongIntHashMap;
import org.terasology.entitysystem.component.ComponentManager;
import org.terasology.entitysystem.entity.Component;
import org.terasology.entitysystem.entity.exception.ComponentAlreadyExistsException;
import org.terasology.entitysystem.entity.exception.ComponentDoesNotExistException;
import org.terasology.entitysystem.entity.EntityTransaction;

import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 *
 */
public class InMemoryTransaction implements EntityTransaction {

    private EntityStore entityStore;
    private ComponentManager componentManager;

    private Table<Long, Class<? extends Component>, CacheEntry> entityCache = HashBasedTable.create();

    private TLongIntMap expectedEntityRevisions = new TLongIntHashMap();

    public InMemoryTransaction(EntityStore entityStore, ComponentManager componentManager) {
        this.entityStore = entityStore;
        this.componentManager = componentManager;
    }

    @Override
    public long createEntity() {
        return entityStore.createEntityId();
    }

    @Override
    public <T extends Component> Optional<T> getComponent(long entityId, Class<T> componentType) {
        CacheEntry<T> cacheEntry = getCacheEntry(entityId, componentType);
        return Optional.ofNullable(cacheEntry.getComponent());
    }

    @Override
    public Set<Class<? extends Component>> getEntityComposition(long entityId) {
        Set<Class<? extends Component>> result = Sets.newHashSet();
        cacheEntity(entityId);
        for (Map.Entry<Class<? extends Component>, CacheEntry> entry : entityCache.row(entityId).entrySet()) {
            if (entry.getValue().getComponent() != null) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void cacheEntity(long entityId) {
        if (!expectedEntityRevisions.containsKey(entityId)) {
            expectedEntityRevisions.put(entityId, entityStore.getEntityRevision(entityId));
            for (Component component : entityStore.getComponents(entityId)) {
                Class interfaceType = componentManager.getType(component.getClass()).getInterfaceType();
                entityCache.put(entityId, interfaceType, new CacheEntry(entityId, interfaceType, component, Action.UPDATE));
            }
        }
    }

    @Override
    public <T extends Component> T addComponent(long entityId, Class<T> componentType) {
        CacheEntry<T> cacheEntry = getCacheEntry(entityId, componentType);
        if (cacheEntry.getComponent() != null) {
            throw new ComponentAlreadyExistsException("Entity " + entityId + " already has a component of type " + componentType.getSimpleName());
        }
        T newComp = componentManager.create(componentType);
        cacheEntry.setComponent(newComp);
        switch (cacheEntry.getAction()) {
            case REMOVE:
                cacheEntry.setAction(Action.UPDATE);
                break;
            default:
                cacheEntry.setAction(Action.ADD);
                break;
        }
        return newComp;
    }

    @Override
    public <T extends Component> void removeComponent(long entityId, Class<T> componentType) {
        CacheEntry<T> cacheEntry = getCacheEntry(entityId, componentType);
        if (cacheEntry.getComponent() == null) {
            throw new ComponentDoesNotExistException("Entity " + entityId + " does not have a component of type " + componentType.getSimpleName());
        }
        cacheEntry.setComponent(null);
        switch (cacheEntry.getAction()) {
            case ADD:
                cacheEntry.setAction(Action.NONE);
                break;
            default:
                cacheEntry.setAction(Action.REMOVE);
                break;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void commit() throws ConcurrentModificationException {
        try (ClosableLock ignored = entityStore.lock(expectedEntityRevisions.keySet())) {
            // Check entity revisions
            TLongIterator iterator = expectedEntityRevisions.keySet().iterator();
            while (iterator.hasNext()) {
                long entityId = iterator.next();
                if (expectedEntityRevisions.get(entityId) != entityStore.getEntityRevision(entityId)) {
                    throw new ConcurrentModificationException("Entity " + entityId + " modified outside of transaction");
                }
            }

            // Apply changes
            for (CacheEntry comp : entityCache.values()) {
                switch (comp.getAction()) {
                    case ADD:
                        if (!entityStore.add(comp.getEntityId(), comp.getComponentType(), comp.getComponent())) {
                            throw new RuntimeException("Entity state does not match expected.");
                        }
                        break;
                    case REMOVE:
                        if (entityStore.remove(comp.getEntityId(), comp.getComponentType()) == null) {
                            throw new RuntimeException("Entity state does not match expected.");
                        }
                        break;
                    case UPDATE:
                        if (!entityStore.update(comp.getEntityId(), comp.getComponentType(), comp.getComponent())) {
                            throw new RuntimeException("Entity state does not match expected.");
                        }
                        break;
                }
            }
        } finally {
            entityCache.clear();
            expectedEntityRevisions.clear();
        }
    }

    @Override
    public void rollback() {
        entityCache.clear();
        expectedEntityRevisions.clear();
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> CacheEntry<T> getCacheEntry(long entityId, Class<T> componentType) {
        CacheEntry<T> cacheEntry = entityCache.get(entityId, componentType);
        if (cacheEntry == null) {
            cacheEntity(entityId);
            cacheEntry = entityCache.get(entityId, componentType);
            if (cacheEntry == null) {
                cacheEntry = new CacheEntry<>(entityId, componentType, null, Action.NONE);
                entityCache.put(entityId, componentType, cacheEntry);
            }
        }
        return cacheEntry;
    }

    /**
     * An entry in the transaction's cache of components.
     * @param <T>
     */
    private static class CacheEntry<T extends Component> {
        private long entityId;
        private Class<T> componentType;
        private T component;
        private Action action;

        public CacheEntry(long entityId, Class<T> componentType, T component, Action action) {
            this.entityId = entityId;
            this.componentType = componentType;
            this.component = component;
            this.action = action;
        }

        public long getEntityId() {
            return entityId;
        }

        public Action getAction() {
            return action;
        }

        public Class<T> getComponentType() {
            return componentType;
        }

        public T getComponent() {
            return component;
        }

        public void setComponent(T component) {
            this.component = component;
        }

        public void setAction(Action action) {
            this.action = action;
        }
    }

    /**
     * The action to perform on an item in the transactin cache
     */
    private enum Action {
        NONE,
        ADD,
        UPDATE,
        REMOVE
    }
}
