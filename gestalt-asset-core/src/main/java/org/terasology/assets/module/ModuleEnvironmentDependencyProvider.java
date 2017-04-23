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

package org.terasology.assets.module;

import org.terasology.assets.format.producer.ModuleDependencyProvider;
import org.terasology.module.ModuleEnvironment;
import org.terasology.naming.Name;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 *
 */
public class ModuleEnvironmentDependencyProvider implements ModuleDependencyProvider {

    private volatile ModuleEnvironment moduleEnvironment;

    public ModuleEnvironmentDependencyProvider() {
    }

    public ModuleEnvironmentDependencyProvider(ModuleEnvironment moduleEnvironment) {
        this.moduleEnvironment = moduleEnvironment;
    }

    public void setModuleEnvironment(@Nullable ModuleEnvironment moduleEnvironment) {
        this.moduleEnvironment = moduleEnvironment;
    }

    public ModuleEnvironment getModuleEnvironment() {
        return moduleEnvironment;
    }

    @Override
    public boolean dependencyExists(Name fromModule, Name onModule) {
        return moduleEnvironment != null && moduleEnvironment.getDependencyNamesOf(fromModule).contains(onModule);
    }

    @Override
    public List<Name> getModulesOrderedByDependency() {
        if (moduleEnvironment != null) {
            return moduleEnvironment.getModuleIdsOrderedByDependencies();
        } else {
            return Collections.emptyList();
        }
    }

}
