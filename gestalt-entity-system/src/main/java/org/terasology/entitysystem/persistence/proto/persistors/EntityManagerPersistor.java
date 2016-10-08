/*
 * Copyright 2015 MovingBlocks
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

package org.terasology.entitysystem.persistence.proto.persistors;

import org.terasology.entitysystem.component.ComponentManager;
import org.terasology.entitysystem.core.EntityManager;
import org.terasology.entitysystem.core.EntityRef;
import org.terasology.entitysystem.transaction.inmemory.InMemoryEntityManager;
import org.terasology.entitysystem.persistence.proto.ComponentManifest;
import org.terasology.entitysystem.persistence.proto.ProtoPersistence;
import org.terasology.entitysystem.persistence.protodata.ProtoDatastore;
import org.terasology.module.ModuleEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Serializes and Deserializes EntityManagers.
 */
public class EntityManagerPersistor {

    private final ComponentManager componentManager;
    private final ModuleEnvironment moduleEnvironment;
    private final ComponentManifestPersistor componentManifestPersistor;
    private final ProtoPersistence context;

    public EntityManagerPersistor(ModuleEnvironment moduleEnvironment, ComponentManager componentManager, ProtoPersistence context) {
        this.moduleEnvironment = moduleEnvironment;
        this.componentManager = componentManager;
        this.context = context;
        this.componentManifestPersistor = new ComponentManifestPersistor(moduleEnvironment, componentManager);
    }

    public void serialize(EntityManager entityManager, Path file) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file)) {
            serialize(entityManager).writeTo(stream);
        }
    }

    public EntityManager deserialize(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return deserialize(ProtoDatastore.Store.parseFrom(stream));
        }
    }

    public ProtoDatastore.Store serialize(EntityManager entityManager) {
        ComponentManifest componentManifest = new ComponentManifest(moduleEnvironment, componentManager);

        EntityPersistor entityPersistor = new SimpleEntityPersistor(context, componentManifest);
        ProtoDatastore.Store.Builder builder = serializeEntities(entityManager, entityPersistor);
        builder.setComponentManifest(componentManifestPersistor.serialize(componentManifest));
        builder.setNextEntityId(entityManager.getNextId());

        return builder.build();
    }

    public EntityManager deserialize(ProtoDatastore.Store entityManagerData) {
        EntityManager entityManager = new InMemoryEntityManager(componentManager, entityManagerData.getNextEntityId());

        ComponentManifest componentManifest = componentManifestPersistor.deserialize(entityManagerData.getComponentManifest());
        EntityPersistor entityPersistor = new SimpleEntityPersistor(context, componentManifest);
        deserializeEntities(entityManagerData, entityManager, entityPersistor);
        return entityManager;
    }

    private ProtoDatastore.Store.Builder serializeEntities(EntityManager entityManager, EntityPersistor entityPersistor) {
        ProtoDatastore.Store.Builder builder = ProtoDatastore.Store.newBuilder();
        Iterator<EntityRef> i = entityManager.allEntities();
        while (i.hasNext()) {
            EntityRef entity = i.next();
            entityManager.beginTransaction();
            if (entity.isPresent()) {
                builder.addEntity(entityPersistor.serialize(entity));
            }
            entityManager.rollback();
        }

        return builder;
    }

    private void deserializeEntities(ProtoDatastore.Store data, EntityManager manager, EntityPersistor entityPersistor) {
        for (ProtoDatastore.EntityData entityData : data.getEntityList()) {
            manager.beginTransaction();
            entityPersistor.deserialize(entityData, manager);
            manager.commit();
        }
    }
}
