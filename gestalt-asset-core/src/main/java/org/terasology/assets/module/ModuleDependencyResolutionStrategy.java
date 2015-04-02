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

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import org.terasology.assets.ResolutionStrategy;
import org.terasology.module.ModuleEnvironment;
import org.terasology.naming.Name;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

/**
 * ModuleDependencyResolutionStrategy is a resolution strategy that takes into account the dependencies of the module that is the context of resolution.
 * Firstly, if the context is one of the possible modules, just the context is returned. Otherwise, only list of possible modules
 * are filtered down to just those that are a dependency of the module context
 *
 * @author Immortius
 */
@ThreadSafe
public class ModuleDependencyResolutionStrategy implements ResolutionStrategy {

    private final ModuleEnvironment environment;

    /**
     * @param environment The module environment providing the module dependencies.
     */
    public ModuleDependencyResolutionStrategy(ModuleEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public Set<Name> resolve(Set<Name> modules, final Name context) {
        if (modules.contains(context)) {
            return ImmutableSet.of(context);
        }

        return ImmutableSet.copyOf(Collections2.filter(modules, new Predicate<Name>() {
            @Override
            public boolean apply(Name input) {
                return environment.getDependencyNamesOf(context).contains(input);
            }
        }));
    }
}
