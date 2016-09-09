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

package org.terasology.entitysystem.persistence.proto;

import com.google.common.collect.Sets;
import org.junit.Test;
import org.terasology.assets.test.VirtualModuleEnvironment;
import org.terasology.entitysystem.component.CodeGenComponentManager;
import org.terasology.entitysystem.component.ComponentManager;
import org.terasology.entitysystem.entity.EntityRef;
import org.terasology.entitysystem.persistence.protodata.ProtoDatastore;
import org.terasology.entitysystem.stubs.SampleComponent;
import org.terasology.module.ModuleEnvironment;
import org.terasology.naming.Name;
import org.terasology.valuetype.ImmutableCopy;
import org.terasology.valuetype.TypeHandler;
import org.terasology.valuetype.TypeLibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 *
 */
public class ComponentManifestPersistorTest {

    @Test
    public void persistManifest() throws Exception {
        ModuleEnvironment moduleEnvironment;
        VirtualModuleEnvironment virtualModuleEnvironment = new VirtualModuleEnvironment(getClass());
        moduleEnvironment = virtualModuleEnvironment.createEnvironment();

        TypeLibrary typeLibrary = new TypeLibrary();
        typeLibrary.addHandler(new TypeHandler<>(String.class, ImmutableCopy.create()));
        typeLibrary.addHandler(new TypeHandler<>(EntityRef.class, ImmutableCopy.create()));
        ComponentManager componentManager = new CodeGenComponentManager(typeLibrary);

        ComponentManifestPersistor persistor = new ComponentManifestPersistor(moduleEnvironment, componentManager);

        ComponentManifest manifest = new ComponentManifest(moduleEnvironment);
        ComponentMetadata metadata = new ComponentMetadata(1, new Name("Test"), new Name("Sample"), componentManager.getType(SampleComponent.class));
        manifest.addComponentMetadata(metadata);
        ProtoDatastore.ComponentManifest manifestData = persistor.serialize(manifest).build();
        ComponentManifest finalManifest = persistor.deserialize(manifestData);

        assertNotNull(finalManifest);
        assertNotNull(finalManifest.getComponentMetadata(1));
        ComponentMetadata componentMetadata = finalManifest.getComponentMetadata(1);
        assertEquals(new Name("Sample"), componentMetadata.getName());
        assertEquals(componentManager.getType(SampleComponent.class), componentMetadata.getComponentType().orElseThrow(IllegalStateException::new));

        assertEquals(Sets.newLinkedHashSet(manifest.getComponentMetadata(1).allFields()), Sets.newLinkedHashSet(componentMetadata.allFields()));

    }
}
