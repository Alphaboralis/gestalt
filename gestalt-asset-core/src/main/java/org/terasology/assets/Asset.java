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

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Abstract base class common to all assets.
 * <p/>
 * An asset is a resource that is used by the game - a texture, sound, block definition and the like. These are typically
 * loaded from a module, although they can also be created at runtime. Each asset is identified by a ResourceUrn that uniquely
 * identifies it and can be used to obtain it. This urn provides a lightweight way to serialize a reference to an Asset.
 * <p/>
 * Assets are created from a specific type of asset data - this allows for implementation specific assets (e.g. OpenGL vs DirectX textures for example).
 * <p/>
 * Assets may be reloaded by providing a new batch of data, or disposed to free resources - disposed assets may no
 * longer be used until reloaded.
 *
 * @author Immortius
 */
public abstract class Asset<T extends AssetData> extends AssetOwner<T> {

    private final ResourceUrn urn;
    private final List<Asset<T>> children = Lists.newArrayList();
    private Optional<AssetOwner<T>> owner = Optional.absent();
    private boolean disposed;

    public Asset(ResourceUrn urn) {
        Preconditions.checkNotNull(urn);
        this.urn = urn;
    }

    final Optional<AssetOwner<T>> getOwner() {
        return owner;
    }

    final void setOwner(AssetOwner<T> owner) {
        this.owner = Optional.of(owner);
    }

    final void addChild(Asset<T> other) {
        children.add(other);
    }

    /**
     * @return This asset's identifying URI.
     */
    public final ResourceUrn getUrn() {
        return urn;
    }

    /**
     * Reloads this assets using the new data.
     *
     * @param data
     */
    public final void reload(T data) {
        Preconditions.checkState(!disposed);
        doReload(data);
    }

    protected abstract Asset<T> doCreateInstance(ResourceUrn instanceUrn);

    protected abstract void doReload(T data);

    /**
     * Disposes this asset, freeing resources and making it unusable
     */
    public final void dispose() {
        if (!disposed) {
            disposed = true;
            for (Asset<T> child : ImmutableList.copyOf(children)) {
                child.dispose();
            }
            doDispose();
            if (owner.isPresent()) {
                owner.get().onOwnedAssetDisposed(this);
            }
        }
    }

    protected abstract void doDispose();

    final void onOwnedAssetDisposed(Asset<T> child) {
        children.remove(child);
    }

    /**
     * @return Whether this asset has been disposed
     */
    public final boolean isDisposed() {
        return disposed;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Asset) {
            Asset other = (Asset) obj;
            return !urn.isInstance() && !other.urn.isInstance() && other.urn.equals(urn);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return urn.hashCode();
    }

    @Override
    public String toString() {
        return urn.toString();
    }


}
