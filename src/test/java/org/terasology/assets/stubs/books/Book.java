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

package org.terasology.assets.stubs.books;

import org.terasology.assets.Asset;
import org.terasology.naming.ResourceUrn;

/**
 * @author Immortius
 */
public class Book extends Asset<BookData> {

    private String title;
    private String body;

    public Book(ResourceUrn urn, BookData data) {
        super(urn);
        reload(data);
    }

    @Override
    protected void doReload(BookData data) {
        title = data.getHeading();
        body = data.getBody();
    }

    @Override
    protected void doDispose() {
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }
}
