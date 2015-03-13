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

package org.terasology.assets;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.Sets;
import org.terasology.assets.management.AssetManager;
import org.terasology.naming.Name;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * @author Immortius
 */
public abstract class AbstractFragmentDataProducer<T extends AssetData, U extends Asset<V>, V extends AssetData> implements AssetDataProducer<T> {

    private final AssetManager assetManager;
    private final Class<U> rootAssetType;

    public AbstractFragmentDataProducer(AssetManager assetManager, Class<U> rootAssetType) {
        this.assetManager = assetManager;
        this.rootAssetType = rootAssetType;
    }

    // Fragment data producer doesn't handle main resources
    @Override
    public Set<Name> getModulesProviding(Name resourceName) {
        return Collections.emptySet();
    }

    @Override
    public ResourceUrn redirect(ResourceUrn urn) {
        return urn;
    }

    @Override
    public Optional<T> getAssetData(ResourceUrn urn) throws IOException {
        Optional<? extends U> rootAsset = assetManager.getAsset(urn.getRootUrn(), rootAssetType);
        if (rootAsset.isPresent()) {
            return getFragmentData(urn, rootAsset.get());
        }
        return Optional.absent();
    }

    @Override
    public void close() {
    }

    protected abstract Optional<T> getFragmentData(ResourceUrn urn, U rootAsset);
}
