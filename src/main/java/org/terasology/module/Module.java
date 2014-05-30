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
package org.terasology.module;

import org.terasology.naming.Name;
import org.terasology.naming.Version;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;

/**
 * A module is an identified and versioned set of code and/or resources that can be loaded and used at runtime. This class encapsulates information on a
 * module.
 *
 * @author Immortius
 */
public interface Module {

    /**
     * @return The locations composing the module
     */
    Collection<Path> getLocations();

    /**
     * @return The urls forming the classpath of the module
     */
    Collection<URL> getClasspaths();

    /**
     * @return The identifier for the module
     */
    Name getId();

    /**
     * @return The version of the module
     */
    Version getVersion();

    /**
     * Whether the module is included in the classpath of the application. These are not loaded dynamically and hence are not sandboxed and are always active.
     *
     * @return Whether this module is on the classpath
     */
    boolean isOnClasspath();

    /**
     * @return Whether the module may introduce code elements
     */
    boolean isCodeModule();

    /**
     * @return Metadata describing the module
     */
    ModuleMetadata getMetadata();

    /**
     * Whether the module is available in a packaged form that can be retrieved.
     *
     * @return Whether the module data is available
     */
    boolean isDataAvailable();

    /**
     * @return A stream of the module
     * @throws IOException
     */
    InputStream getData() throws IOException;

    /**
     * @return The size of the moduel
     */
    long size();
}
