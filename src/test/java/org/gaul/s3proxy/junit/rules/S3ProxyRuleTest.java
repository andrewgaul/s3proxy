/*
 * Copyright 2014-2018 Andrew Gaul <andrew@gaul.org>
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

package org.gaul.s3proxy.junit.rules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import com.google.common.io.Files;

import org.assertj.core.api.Assertions;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * This is an example of how one would use the S3Proxy JUnit rule in a unit
 * test as opposed to a proper test of the S3ProxyRule class.
 */
public class S3ProxyRuleTest {

    private static final String MY_TEST_BUCKET = "my-test-bucket";

    private static final String AWS_ACCESS_KEY = "access";
    private static final String AWS_SECRET_KEY = "secret";

    public @Rule TemporaryFolder temporaryFolder = new TemporaryFolder();
    public @Rule S3ProxyRule s3Proxy = S3ProxyRule
        .builder()
        .withCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
        .ignoreUnknownHeaders()
        .build();

    private AmazonS3 s3Client;

    @Before
    public final void setUp() throws Exception {
        s3Client = AmazonS3ClientBuilder
            .standard()
            .withCredentials(
                new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials(
                        s3Proxy.getAccessKey(), s3Proxy.getSecretKey())))
            .withEndpointConfiguration(
                new EndpointConfiguration(
                    s3Proxy.getUri().toString(), Regions.US_EAST_1.getName()))
            .build();

        s3Client.createBucket(MY_TEST_BUCKET);
    }

    @Test
    public final void listBucket() {
        List<Bucket> buckets = s3Client.listBuckets();
        Assertions.assertThat(buckets.size()).isEqualTo(1);
        Assertions.assertThat(buckets.get(0).getName())
            .isEqualTo(MY_TEST_BUCKET);
    }

    @Test
    public final void uploadFile() throws Exception {
        File testFile = temporaryFolder.newFile("test.txt");
        Files.asCharSink(testFile, StandardCharsets.UTF_8).write("content");
        s3Client.putObject(MY_TEST_BUCKET, "file.txt", testFile);

        List<S3ObjectSummary> summaries = s3Client
                                            .listObjects(MY_TEST_BUCKET)
                                            .getObjectSummaries();
        Assertions.assertThat(summaries.size()).isEqualTo(1);
        Assertions.assertThat(summaries.get(0).getKey()).isEqualTo("file.txt");
        Assertions.assertThat(summaries.get(0).getSize())
            .isEqualTo(testFile.length());
    }

}
