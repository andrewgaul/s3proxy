/*
 * Copyright 2014-2017 Andrew Gaul <andrew@gaul.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.s3proxy.requesthandlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.gaul.s3proxy.S3Exception;
import org.jclouds.blobstore.BlobStore;

abstract class AbstractS3ObjectHandler extends AbstractRequestHandler {
    AbstractS3ObjectHandler(HttpServletRequest request,
                            HttpServletResponse response,
                            BlobStore blobStore) {
        super(request, response, blobStore);
    }

    abstract void executeRequest() throws IOException, S3Exception;
}

