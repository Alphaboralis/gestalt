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

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.AssetData;
import org.terasology.assets.AssetDataProducer;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.exceptions.InvalidAssetFilenameException;
import org.terasology.assets.format.AssetAlterationFileFormat;
import org.terasology.assets.format.AssetFileFormat;
import org.terasology.assets.format.FileFormat;
import org.terasology.module.Module;
import org.terasology.module.ModuleEnvironment;
import org.terasology.module.filesystem.ModuleFileSystemProvider;
import org.terasology.naming.Name;
import org.terasology.util.io.FileExtensionPathMatcher;
import org.terasology.util.io.FileScanning;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Immortius
 */
public class ModuleAssetDataProducer<U extends AssetData> implements AssetDataProducer<U> {

    public static final String ASSET_FOLDER = "assets";
    public static final String OVERRIDE_FOLDER = "overrides";
    public static final String DELTA_FOLDER = "deltas";
    public static final String REDIRECT_EXTENSION = "redirect";

    private static final Logger logger = LoggerFactory.getLogger(ModuleAssetDataProducer.class);

    private final String folderName;

    private ModuleEnvironment moduleEnvironment;

    private List<AssetFileFormat<U>> assetFormats = Lists.newArrayList();
    private List<AssetAlterationFileFormat<U>> deltaFormats = Lists.newArrayList();
    private List<AssetAlterationFileFormat<U>> supplementFormats = Lists.newArrayList();

    private Map<ResourceUrn, UnloadedAssetData<U>> unloadedAssetLookup = Maps.newHashMap();
    private Map<ResourceUrn, ResourceUrn> redirectMap = Maps.newHashMap();
    private SetMultimap<Name, Name> resolutionMap = HashMultimap.create();

    private Map<Name, WatchService> moduleWatchServices = Maps.newLinkedHashMap();
    private Map<WatchKey, PathWatcher> pathWatchers = Maps.newHashMap();
    private Map<Path, WatchKey> watchKeys = Maps.newHashMap();

    public ModuleAssetDataProducer(String folderName) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(folderName), "folderName must not be null or empty");
        this.folderName = folderName;
    }

    public void close() {
        shutdownWatchService();
    }

    public ModuleEnvironment getModuleEnvironment() {
        return moduleEnvironment;
    }

    public void setEnvironment(ModuleEnvironment environment) {
        Preconditions.checkNotNull(environment);
        this.moduleEnvironment = environment;

        shutdownWatchService();
        unloadedAssetLookup.clear();
        resolutionMap.clear();
        redirectMap.clear();
        scanForAssets();
        scanForOverrides();
        scanForDeltas();
        scanForRedirects();

        startWatchService();
    }

    public Set<ResourceUrn> checkForChanges() {
        Set<ResourceUrn> toReload = Sets.newLinkedHashSet();
        for (Map.Entry<Name, WatchService> entry : moduleWatchServices.entrySet()) {
            WatchKey key = entry.getValue().poll();
            while (key != null) {
                PathWatcher pathWatcher = pathWatchers.get(key);
                toReload.addAll(pathWatcher.update(key.pollEvents()));
                key.reset();
                key = entry.getValue().poll();
            }
        }
        return toReload;
    }

    @Override
    public Set<ResourceUrn> getAvailableAssetUrns() {
        return ImmutableSet.copyOf(unloadedAssetLookup.keySet());
    }

    @Override
    public Set<Name> getModulesProviding(Name resourceName) {
        return Collections.unmodifiableSet(resolutionMap.get(resourceName));
    }

    @Override
    public ResourceUrn redirect(ResourceUrn urn) {
        ResourceUrn redirectUrn = redirectMap.get(urn);
        if (redirectUrn != null) {
            return redirectUrn;
        }
        return urn;
    }

    @Override
    public Optional<U> getAssetData(ResourceUrn urn) throws IOException {
        if (urn.getFragmentName().isEmpty()) {
            UnloadedAssetData<U> source = unloadedAssetLookup.get(urn);
            if (source != null && source.isValid()) {
                return source.load();
            }
        }
        return Optional.absent();
    }

    public List<AssetFileFormat<U>> getAssetFormats() {
        return Collections.unmodifiableList(assetFormats);
    }

    public void addAssetFormat(AssetFileFormat<U> format) {
        assetFormats.add(format);
    }

    public void removeAssetFormat(AssetFileFormat<U> format) {
        assetFormats.remove(format);
    }

    public void clearAssetFormats() {
        assetFormats.clear();
    }

    public List<AssetAlterationFileFormat<U>> getDeltaFormats() {
        return Collections.unmodifiableList(deltaFormats);
    }

    public void addDeltaFormat(AssetAlterationFileFormat<U> format) {
        deltaFormats.add(format);
    }

    public void removeDeltaFormat(AssetAlterationFileFormat<U> format) {
        deltaFormats.remove(format);
    }

    public void clearDeltaFormats() {
        deltaFormats.clear();
    }

    public void addSupplementFormat(AssetAlterationFileFormat<U> format) {
        supplementFormats.add(format);
    }

    public List<AssetAlterationFileFormat<U>> getSupplementFormats() {
        return Collections.unmodifiableList(supplementFormats);
    }

    public void removeSupplementFormat(AssetAlterationFileFormat<U> format) {
        supplementFormats.remove(format);
    }

    public void clearSupplementFormats() {
        supplementFormats.clear();
    }

    private void scanForAssets() {
        for (Module module : moduleEnvironment.getModulesOrderedByDependencies()) {
            ModuleNameProvider moduleNameProvider = new FixedModuleNameProvider(module.getId());
            Path rootPath = module.getFileSystem().getPath(ModuleFileSystemProvider.ROOT, ASSET_FOLDER, folderName);
            if (Files.exists(rootPath)) {
                scanModuleForAssets(module, rootPath, moduleNameProvider);
            }
        }
    }

    private void scanForOverrides() {
        ModuleNameProvider moduleNameProvider = new PathModuleNameProvider(1);
        for (Module module : moduleEnvironment.getModulesOrderedByDependencies()) {
            Path rootPath = module.getFileSystem().getPath(ModuleFileSystemProvider.ROOT, OVERRIDE_FOLDER);
            if (Files.exists(rootPath)) {
                scanModuleForAssets(module, rootPath, moduleNameProvider);
            }
        }
    }

    private void scanModuleForAssets(final Module origin, Path rootPath, final ModuleNameProvider moduleNameProvider) {
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Name module = moduleNameProvider.getModuleName(file);
                    Optional<ResourceUrn> assetUrn = registerSource(module, file, origin.getId(), assetFormats, new RegisterAssetSourceHandler());
                    if (!assetUrn.isPresent()) {
                        registerSource(moduleNameProvider.getModuleName(file), file, origin.getId(), supplementFormats, new RegisterAssetSupplementSourceHandler());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Failed to scan for assets of '{}' in 'module://{}:{}", folderName, origin.getId(), rootPath, e);
        }
    }

    private void scanForDeltas() {
        final ModuleNameProvider moduleNameProvider = new PathModuleNameProvider(1);
        for (final Module module : moduleEnvironment.getModulesOrderedByDependencies()) {
            Path rootPath = module.getFileSystem().getPath(ModuleFileSystemProvider.ROOT, DELTA_FOLDER);
            if (Files.exists(rootPath)) {
                try {
                    Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            registerAssetDelta(moduleNameProvider.getModuleName(file), file, module.getId());
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    logger.error("Failed to scan for asset deltas of '{}' in 'module://{}:{}", folderName, module.getId(), rootPath, e);
                }
            }
        }
    }

    private void scanForRedirects() {
        Map<ResourceUrn, ResourceUrn> rawRedirects = Maps.newLinkedHashMap();
        for (Module module : moduleEnvironment.getModulesOrderedByDependencies()) {
            Path rootPath = module.getFileSystem().getPath(ModuleFileSystemProvider.ROOT, ASSET_FOLDER, folderName);
            if (Files.exists(rootPath)) {
                try {
                    for (Path file : module.findFiles(rootPath, FileScanning.acceptAll(), new FileExtensionPathMatcher(REDIRECT_EXTENSION))) {
                        Name assetName = new Name(com.google.common.io.Files.getNameWithoutExtension(file.getFileName().toString()));
                        try (BufferedReader reader = Files.newBufferedReader(file, Charsets.UTF_8)) {
                            List<String> contents = CharStreams.readLines(reader);
                            if (contents.isEmpty()) {
                                logger.error("Failed to read redirect '{}:{}' - empty", module.getId(), assetName);
                            } else if (!ResourceUrn.isValid(contents.get(0))) {
                                logger.error("Failed to read redirect '{}:{}' - '{}' is not a valid urn", module.getId(), assetName, contents.get(0));
                            } else {
                                rawRedirects.put(new ResourceUrn(module.getId(), assetName), new ResourceUrn(contents.get(0)));
                                resolutionMap.put(assetName, module.getId());
                            }
                        } catch (IOException e) {
                            logger.error("Failed to read redirect '{}:{}'", module.getId(), assetName, e);
                        }
                    }
                } catch (IOException e) {
                    logger.error("Failed to scan module '{}' for assets", module.getId(), e);
                }
            }
        }

        for (Map.Entry<ResourceUrn, ResourceUrn> entry : rawRedirects.entrySet()) {
            ResourceUrn currentTarget = entry.getKey();
            ResourceUrn redirect = entry.getValue();
            while (redirect != null) {
                currentTarget = redirect;
                redirect = rawRedirects.get(currentTarget);
            }
            redirectMap.put(entry.getKey(), currentTarget);
        }
    }

    private <V extends FileFormat> Optional<ResourceUrn> registerSource(Name module, Path target, Name providingModule,
                                                                        Collection<V> formats, RegisterSourceHandler<U, V> sourceHandler) {
        for (V format : formats) {
            if (format.getFileMatcher().matches(target)) {
                try {
                    Name assetName = format.getAssetName(target.getFileName().toString());
                    ResourceUrn urn = new ResourceUrn(module, assetName);
                    UnloadedAssetData<U> existing = unloadedAssetLookup.get(urn);
                    if (existing != null) {
                        if (sourceHandler.registerSource(existing, providingModule, format, target)) {
                            return Optional.of(urn);
                        }
                    } else {
                        UnloadedAssetData<U> source = new UnloadedAssetData<>(urn, moduleEnvironment);
                        if (sourceHandler.registerSource(source, providingModule, format, target)) {
                            unloadedAssetLookup.put(urn, source);
                            resolutionMap.put(urn.getResourceName(), urn.getModuleName());
                            return Optional.of(urn);
                        }
                    }
                    return Optional.absent();
                } catch (InvalidAssetFilenameException e) {
                    logger.warn("Invalid name for asset - {}", target.getFileName());
                }
            }
        }
        return Optional.absent();
    }

    private Optional<ResourceUrn> registerAssetDelta(Name module, Path target, Name providingModule) {
        for (AssetAlterationFileFormat<U> format : deltaFormats) {
            if (format.getFileMatcher().matches(target)) {
                try {
                    Name assetName = format.getAssetName(target.getFileName().toString());
                    ResourceUrn urn = new ResourceUrn(module, assetName);
                    UnloadedAssetData<U> unloadedAssetData = unloadedAssetLookup.get(urn);
                    if (unloadedAssetData == null) {
                        logger.warn("Discovered delta for unknown asset '{}'", urn);
                        return Optional.absent();
                    }
                    if (unloadedAssetData.addDeltaSource(providingModule, format, target)) {
                        return Optional.of(urn);
                    }
                } catch (InvalidAssetFilenameException e) {
                    logger.error("Invalid file name '{}' for asset delta for '{}'", target.getFileName(), folderName, e);
                }
            }
        }
        return Optional.absent();
    }

    private void startWatchService() {
        for (Module module : moduleEnvironment.getModulesOrderedByDependencies()) {
            try {
                final WatchService moduleWatchService = module.getFileSystem().newWatchService();
                moduleWatchServices.put(module.getId(), moduleWatchService);

                for (Path rootPath : module.getFileSystem().getRootDirectories()) {
                    PathWatcher watcher = new RootPathWatcher(rootPath, module.getId(), moduleWatchService);
                    watcher.onRegistered();
                }
            } catch (IOException e) {
                logger.warn("Failed to establish change watch service for module '{}'", module, e);
            }
        }
    }

    private void shutdownWatchService() {
        for (Map.Entry<Name, WatchService> entry : moduleWatchServices.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                logger.error("Failed to shutdown watch service for module '{}'", entry.getKey(), e);
            }
        }
        moduleWatchServices.clear();
        pathWatchers.clear();
        watchKeys.clear();
    }

    private abstract class PathWatcher {
        private Path watchedPath;
        private WatchService watchService;

        public PathWatcher(Path path, WatchService watchService) throws IOException {
            this.watchedPath = path;
            this.watchService = watchService;
            WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
            if (key.isValid()) {
                pathWatchers.put(key, this);
                watchKeys.put(path, key);
            }
        }

        public Path getWatchedPath() {
            return watchedPath;
        }

        public WatchService getWatchService() {
            return watchService;
        }

        @SuppressWarnings("unchecked")
        public final Set<ResourceUrn> update(List<WatchEvent<?>> watchEvents) {
            final Set<ResourceUrn> changedAssets = Sets.newLinkedHashSet();
            for (WatchEvent<?> event : watchEvents) {
                WatchEvent.Kind kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    logger.warn("File event overflow - lost change events");
                    continue;
                }

                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path target = watchedPath.resolve(pathEvent.context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    if (Files.isDirectory(target)) {
                        onDirectoryCreated(target, changedAssets);
                    } else {
                        onFileCreated(target, changedAssets);
                    }
                } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    if (Files.isRegularFile(target)) {
                        onFileModified(target, changedAssets);
                    }
                } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    WatchKey key = watchKeys.remove(target);
                    if (key != null) {
                        pathWatchers.remove(key);
                    } else {
                        onFileDeleted(target, changedAssets);
                    }
                }
            }
            return changedAssets;
        }

        private void onDirectoryCreated(Path target, Set<ResourceUrn> outChanged) {
            try {
                Optional<? extends PathWatcher> pathWatcher = processPath(target);
                if (pathWatcher.isPresent()) {
                    pathWatcher.get().onCreated(outChanged);
                }
            } catch (IOException e) {
                logger.error("Error registering path for change watching '{}'", getWatchedPath(), e);
            }
        }

        public final void onRegistered() {
            try (DirectoryStream<Path> contents = Files.newDirectoryStream(getWatchedPath())) {
                for (Path path : contents) {
                    if (Files.isDirectory(path)) {
                        Optional<? extends PathWatcher> pathWatcher = processPath(path);
                        if (pathWatcher.isPresent()) {
                            pathWatcher.get().onRegistered();
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Error registering path for change watching '{}'", getWatchedPath(), e);
            }
        }

        public final void onCreated(Set<ResourceUrn> outChanged) {
            try (DirectoryStream<Path> contents = Files.newDirectoryStream(getWatchedPath())) {
                for (Path path : contents) {
                    if (Files.isDirectory(path)) {
                        onDirectoryCreated(path, outChanged);
                    } else {
                        onFileCreated(path, outChanged);
                    }
                }
            } catch (IOException e) {
                logger.error("Error registering path for change watching '{}'", getWatchedPath(), e);
            }
        }

        protected abstract Optional<? extends PathWatcher> processPath(Path target) throws IOException;

        protected void onFileCreated(Path target, final Set<ResourceUrn> outChanged) {
        }

        protected void onFileModified(Path target, final Set<ResourceUrn> outChanged) {
        }

        protected void onFileDeleted(Path target, final Set<ResourceUrn> outChanged) {
        }
    }

    private class RootPathWatcher extends PathWatcher {

        private Name module;

        public RootPathWatcher(Path path, Name module, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            if (target.getNameCount() == 1) {
                switch (target.getName(0).toString()) {
                    case ASSET_FOLDER: {
                        return Optional.of(new AssetRootPathWatcher(target, module, getWatchService()));
                    }
                    case DELTA_FOLDER: {
                        return Optional.of(new DeltaRootPathWatcher(target, module, getWatchService()));
                    }
                    case OVERRIDE_FOLDER: {
                        return Optional.of(new OverrideRootPathWatcher(target, module, getWatchService()));
                    }
                }
            }
            return Optional.absent();
        }
    }

    private class AssetRootPathWatcher extends PathWatcher {

        private Name module;

        public AssetRootPathWatcher(Path path, Name module, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            if (target.getNameCount() == 2 && target.getName(1).toString().equals(folderName)) {
                return Optional.of(new AssetPathWatcher(target, module, module, getWatchService()));
            }
            return Optional.absent();
        }
    }

    private class OverrideRootPathWatcher extends PathWatcher {

        private Name module;

        public OverrideRootPathWatcher(Path path, Name module, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            if (target.getNameCount() == 2) {
                return Optional.of(new OverrideRootPathWatcher(target, module, getWatchService()));
            } else if (target.getNameCount() == 3) {
                return Optional.of(new AssetPathWatcher(target, new Name(target.getName(1).toString()), module, getWatchService()));
            }
            return Optional.absent();
        }
    }

    private class DeltaRootPathWatcher extends PathWatcher {

        private Name module;

        public DeltaRootPathWatcher(Path path, Name module, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            if (target.getNameCount() == 2) {
                return Optional.of(new DeltaRootPathWatcher(target, module, getWatchService()));
            } else if (target.getNameCount() == 3) {
                return Optional.of(new DeltaPathWatcher(target, new Name(target.getName(1).toString()), module, getWatchService()));
            }
            return Optional.absent();
        }
    }

    private class DeltaPathWatcher extends PathWatcher {

        private final Name providingModule;
        private final Name module;

        public DeltaPathWatcher(Path path, Name module, Name providingModule, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
            this.providingModule = providingModule;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            return Optional.of(new DeltaPathWatcher(target, module, providingModule, getWatchService()));
        }

        private ResourceUrn getResourceUrn(Path target) {
            for (FileFormat fileFormat : deltaFormats) {
                if (fileFormat.getFileMatcher().matches(target)) {
                    try {
                        Name assetName = fileFormat.getAssetName(target.getFileName().toString());
                        return new ResourceUrn(module, assetName);
                    } catch (InvalidAssetFilenameException e) {
                        logger.debug("Modified file does not have a valid asset name - '{}'", target.getFileName());
                    }
                }
            }
            return null;
        }

        @Override
        protected void onFileCreated(Path target, Set<ResourceUrn> outChanged) {
            Optional<ResourceUrn> urn = registerAssetDelta(module, target, providingModule);
            if (urn.isPresent() && unloadedAssetLookup.get(urn.get()).isValid()) {
                outChanged.add(urn.get());
            }
        }

        @Override
        protected void onFileModified(Path target, Set<ResourceUrn> outChanged) {
            ResourceUrn urn = getResourceUrn(target);
            if (urn != null) {
                if (unloadedAssetLookup.get(urn).isValid()) {
                    outChanged.add(urn);
                }
            }
        }

        @Override
        protected void onFileDeleted(Path target, Set<ResourceUrn> outChanged) {
            for (AssetAlterationFileFormat<U> format : deltaFormats) {
                if (format.getFileMatcher().matches(target)) {
                    try {
                        Name assetName = format.getAssetName(target.getFileName().toString());
                        ResourceUrn urn = new ResourceUrn(module, assetName);
                        UnloadedAssetData<U> existing = unloadedAssetLookup.get(urn);
                        if (existing != null) {
                            existing.removeDeltaSource(providingModule, format, target);
                            if (existing.isValid()) {
                                outChanged.add(urn);
                            }
                        }
                        return;
                    } catch (InvalidAssetFilenameException e) {
                        logger.debug("Deleted file does not have a valid file name - {}", target);
                    }
                }
            }
        }
    }

    private class AssetPathWatcher extends PathWatcher {

        private Name module;
        private Name providingModule;

        public AssetPathWatcher(Path path, Name module, Name providingModule, WatchService watchService) throws IOException {
            super(path, watchService);
            this.module = module;
            this.providingModule = providingModule;
        }

        @Override
        protected Optional<? extends PathWatcher> processPath(Path target) throws IOException {
            return Optional.of(new AssetPathWatcher(target, module, providingModule, getWatchService()));
        }

        private ResourceUrn getResourceUrn(Path target, Collection<? extends FileFormat> formats) {
            for (FileFormat fileFormat : formats) {
                if (fileFormat.getFileMatcher().matches(target)) {
                    try {
                        Name assetName = fileFormat.getAssetName(target.getFileName().toString());
                        return new ResourceUrn(module, assetName);
                    } catch (InvalidAssetFilenameException e) {
                        logger.debug("Modified file does not have a valid asset name - '{}'", target.getFileName());
                    }
                }
            }
            return null;
        }

        @Override
        protected void onFileCreated(Path target, Set<ResourceUrn> outChanged) {
            Optional<ResourceUrn> urn = registerSource(module, target, providingModule, assetFormats, new RegisterAssetSourceHandler());
            if (!urn.isPresent()) {
                urn = registerSource(module, target, providingModule, supplementFormats, new RegisterAssetSupplementSourceHandler());
            }
            if (urn.isPresent() && unloadedAssetLookup.get(urn.get()).isValid()) {
                outChanged.add(urn.get());
            }
        }

        @Override
        protected void onFileModified(Path target, Set<ResourceUrn> outChanged) {
            ResourceUrn urn = getResourceUrn(target, assetFormats);
            if (urn == null) {
                urn = getResourceUrn(target, supplementFormats);
            }
            if (urn != null && unloadedAssetLookup.get(urn).isValid()) {
                outChanged.add(urn);
            }
        }

        @Override
        protected void onFileDeleted(Path target, Set<ResourceUrn> outChanged) {
            for (AssetFileFormat<U> format : assetFormats) {
                if (format.getFileMatcher().matches(target)) {
                    try {
                        Name assetName = format.getAssetName(target.getFileName().toString());
                        ResourceUrn urn = new ResourceUrn(module, assetName);
                        UnloadedAssetData<U> existing = unloadedAssetLookup.get(urn);
                        if (existing != null) {
                            existing.removeSource(providingModule, format, target);
                            if (existing.isValid()) {
                                outChanged.add(urn);
                            }
                        }
                        return;
                    } catch (InvalidAssetFilenameException e) {
                        logger.debug("Deleted file does not have a valid file name - {}", target);
                    }
                }
            }
            for (AssetAlterationFileFormat<U> format : supplementFormats) {
                if (format.getFileMatcher().matches(target)) {
                    try {
                        Name assetName = format.getAssetName(target.getFileName().toString());
                        ResourceUrn urn = new ResourceUrn(module, assetName);
                        UnloadedAssetData<U> existing = unloadedAssetLookup.get(urn);
                        if (existing != null) {
                            existing.removeSupplementSource(providingModule, format, target);
                            if (existing.isValid()) {
                                outChanged.add(urn);
                            }
                        }
                        return;
                    } catch (InvalidAssetFilenameException e) {
                        logger.debug("Deleted file does not have a valid file name - {}", target);
                    }
                }
            }
        }
    }

    private interface RegisterSourceHandler<T extends AssetData, U extends FileFormat> {
        boolean registerSource(UnloadedAssetData<T> source, Name providingModule, U format, Path input);
    }

    public class RegisterAssetSourceHandler implements RegisterSourceHandler<U, AssetFileFormat<U>> {

        @Override
        public boolean registerSource(UnloadedAssetData<U> source, Name providingModule, AssetFileFormat<U> format, Path input) {
            return source.addSource(providingModule, format, input);
        }
    }

    public class RegisterAssetSupplementSourceHandler implements RegisterSourceHandler<U, AssetAlterationFileFormat<U>> {

        @Override
        public boolean registerSource(UnloadedAssetData<U> source, Name providingModule, AssetAlterationFileFormat<U> format, Path input) {
            return source.addSupplementSource(providingModule, format, input);
        }
    }

    private interface ModuleNameProvider {
        Name getModuleName(Path file);
    }

    private static class FixedModuleNameProvider implements ModuleNameProvider {
        private Name moduleName;

        public FixedModuleNameProvider(Name name) {
            this.moduleName = name;
        }

        @Override
        public Name getModuleName(Path file) {
            return moduleName;
        }
    }

    private static class PathModuleNameProvider implements ModuleNameProvider {
        private int nameIndex;

        public PathModuleNameProvider(int index) {
            this.nameIndex = index;
        }

        @Override
        public Name getModuleName(Path file) {
            return new Name(file.getName(nameIndex).toString());
        }
    }


}
