/*
 * Copyright 2014 MovingBlocks
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

import com.google.common.collect.Lists;
import org.terasology.assets.AssetData;
import org.terasology.assets.AssetInput;
import org.terasology.naming.ResourceUrn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * @author Immortius
 */
class UnloadedAsset<T extends AssetData> {

    private final List<AssetInput> inputs = Lists.newArrayList();
    private final AssetFormat<T> format;
    private final ResourceUrn urn;
    private final List<UnloadedAssetAlteration<T>> alterations = Lists.newArrayList();

    public UnloadedAsset(ResourceUrn urn, AssetFormat<T> format) {
        this.urn = urn;
        this.format = format;
    }

    public ResourceUrn getUrn() {
        return urn;
    }

    public void addInput(Path path) {
        inputs.add(new AssetInput(path));
    }

    public void addAlteration(UnloadedAssetAlteration<T> alteration) {
        alterations.add(alteration);
    }

    public T load() throws IOException {
        T result = format.load(urn, inputs);
        for (UnloadedAssetAlteration<T> delta : alterations) {
            delta.applyTo(result);
        }
        return result;
    }

    @Override
    public String toString() {
        return urn.toString();
    }
}
