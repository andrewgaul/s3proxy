/*
 * Copyright 2014-2015 Andrew Gaul <andrew@gaul.org>
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

package org.gaul.s3proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import com.google.inject.Module;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.AWSResponseException;
import org.jclouds.blobstore.BlobRequestSigner;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.io.ContentMetadata;
import org.jclouds.io.ContentMetadataBuilder;
import org.jclouds.io.Payload;
import org.jclouds.io.Payloads;
import org.jclouds.io.payloads.ByteSourcePayload;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.rest.HttpClient;
import org.jclouds.s3.S3Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public final class S3ProxyTest {
    private static final ByteSource BYTE_SOURCE = ByteSource.wrap(new byte[1]);

    private URI s3Endpoint;
    private S3Proxy s3Proxy;
    private BlobStoreContext context;
    private BlobStore blobStore;
    private BlobStoreContext s3Context;
    private BlobStore s3BlobStore;
    private String containerName;

    @Before
    public void setUp() throws Exception {
        Properties s3ProxyProperties = new Properties();
        try (InputStream is = Resources.asByteSource(Resources.getResource(
                "s3proxy.conf")).openStream()) {
            s3ProxyProperties.load(is);
        }

        String provider = s3ProxyProperties.getProperty(
                Constants.PROPERTY_PROVIDER);
        String identity = s3ProxyProperties.getProperty(
                Constants.PROPERTY_IDENTITY);
        String credential = s3ProxyProperties.getProperty(
                Constants.PROPERTY_CREDENTIAL);
        String endpoint = s3ProxyProperties.getProperty(
                Constants.PROPERTY_ENDPOINT);
        String s3Identity = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_IDENTITY);
        String s3Credential = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_CREDENTIAL);
        s3Endpoint = new URI(s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_ENDPOINT));
        String secureEndpoint = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_SECURE_ENDPOINT);
        String keyStorePath = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_KEYSTORE_PATH);
        String keyStorePassword = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_KEYSTORE_PASSWORD);
        String virtualHost = s3ProxyProperties.getProperty(
                S3ProxyConstants.PROPERTY_VIRTUAL_HOST);

        ContextBuilder builder = ContextBuilder
                .newBuilder(provider)
                .credentials(identity, credential)
                .modules(ImmutableList.<Module>of(new SLF4JLoggingModule()))
                .overrides(s3ProxyProperties);
        if (!Strings.isNullOrEmpty(endpoint)) {
            builder.endpoint(endpoint);
        }
        context = builder.build(BlobStoreContext.class);
        blobStore = context.getBlobStore();
        containerName = createRandomContainerName();
        blobStore.createContainerInLocation(null, containerName);

        S3Proxy.Builder s3ProxyBuilder = S3Proxy.builder()
                .blobStore(blobStore)
                .endpoint(s3Endpoint);
        if (secureEndpoint != null) {
            s3ProxyBuilder.secureEndpoint(new URI(secureEndpoint));
        }
        if (s3Identity != null || s3Credential != null) {
            s3ProxyBuilder.awsAuthentication(s3Identity, s3Credential);
        }
        if (keyStorePath != null || keyStorePassword != null) {
            s3ProxyBuilder.keyStore(
                    Resources.getResource(keyStorePath).toString(),
                    keyStorePassword);
        }
        if (virtualHost != null) {
            s3ProxyBuilder.virtualHost(virtualHost);
        }
        s3Proxy = s3ProxyBuilder.build();
        s3Proxy.start();
        while (!s3Proxy.getState().equals(AbstractLifeCycle.STARTED)) {
            Thread.sleep(1);
        }

        // reset endpoint to handle zero port
        s3Endpoint = new URI(s3Endpoint.getScheme(), s3Endpoint.getUserInfo(),
                s3Endpoint.getHost(), s3Proxy.getPort(), s3Endpoint.getPath(),
                s3Endpoint.getQuery(), s3Endpoint.getFragment());

        Properties s3Properties = new Properties();
        s3Properties.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
        s3Context = ContextBuilder
                .newBuilder("s3")
                .credentials(s3Identity, s3Credential)
                .endpoint(s3Endpoint.toString())
                .overrides(s3Properties)
                .build(BlobStoreContext.class);
        s3BlobStore = s3Context.getBlobStore();
    }

    @After
    public void tearDown() throws Exception {
        if (s3Proxy != null) {
            s3Proxy.stop();
        }
        if (s3Context != null) {
            s3Context.close();
        }
        if (context != null) {
            context.getBlobStore().deleteContainer(containerName);
            context.close();
        }
    }

    // TODO: why does this hang for 30 seconds?
    // TODO: this test requires anonymous access
    @Ignore
    @Test
    public void testHttpClient() throws Exception {
        HttpClient httpClient = context.utils().http();
        // TODO: how to interpret this?
        URI uri = URI.create(s3Endpoint + "/" + containerName + "/blob");
        Payload payload = new ByteSourcePayload(BYTE_SOURCE);
        payload.getContentMetadata().setContentLength(BYTE_SOURCE.size());
        httpClient.put(uri, payload);
        try (InputStream actual = httpClient.get(uri);
             InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
    }

    @Test
    public void testJcloudsClient() throws Exception {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (StorageMetadata metadata : s3BlobStore.list()) {
            builder.add(metadata.getName());
        }
        assertThat(builder.build()).contains(containerName);
    }

    @Test
    public void testContainerExists() throws Exception {
        assertThat(s3BlobStore.containerExists(containerName)).isTrue();
        assertThat(s3BlobStore.containerExists(createRandomContainerName()))
                .isFalse();
    }

    @Test
    public void testContainerCreateDelete() throws Exception {
        String containerName2 = createRandomContainerName();
        assertThat(s3BlobStore.createContainerInLocation(null,
                containerName2)).isTrue();
        try {
            assertThat(s3BlobStore.createContainerInLocation(null,
                    containerName2)).isFalse();
        } finally {
            s3BlobStore.deleteContainer(containerName2);
        }
    }

    @Test
    public void testContainerDelete() throws Exception {
        assertThat(s3BlobStore.containerExists(containerName)).isTrue();
        s3BlobStore.deleteContainerIfEmpty(containerName);
        assertThat(s3BlobStore.containerExists(containerName)).isFalse();
    }

    private void putBlobAndCheckIt(String blobName) throws Exception {
        Blob blob = s3BlobStore.blobBuilder(blobName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob);

        Blob blob2 = s3BlobStore.getBlob(containerName, blobName);
        assertThat(blob2.getMetadata().getName()).isEqualTo(blobName);
        try (InputStream actual = blob2.getPayload().openStream();
             InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
    }

    @Test
    public void testBlobPutGet() throws Exception {
        putBlobAndCheckIt("blob");
        putBlobAndCheckIt("blob%");
        putBlobAndCheckIt("blob%%");
    }

    @Test
    public void testBlobEscape() throws Exception {
        assertThat(s3BlobStore.list(containerName)).isEmpty();
        putBlobAndCheckIt("blob%");
        PageSet<? extends StorageMetadata> res =
                s3BlobStore.list(containerName);
        StorageMetadata meta = res.iterator().next();
        assertThat(meta.getName()).isEqualTo("blob%");
        assertThat(res).hasSize(1);
    }

    @Test
    public void testBlobList() throws Exception {
        assertThat(s3BlobStore.list(containerName)).isEmpty();

        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        Blob blob1 = s3BlobStore.blobBuilder("blob1")
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob1);
        for (StorageMetadata metadata : s3BlobStore.list(containerName)) {
            builder.add(metadata.getName());
        }
        assertThat(builder.build()).containsOnly("blob1");

        builder = ImmutableSet.builder();
        Blob blob2 = s3BlobStore.blobBuilder("blob2")
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob2);
        for (StorageMetadata metadata : s3BlobStore.list(containerName)) {
            builder.add(metadata.getName());
        }
        assertThat(builder.build()).containsOnly("blob1", "blob2");
    }

    @Test
    public void testBlobListRecursive() throws Exception {
        assertThat(s3BlobStore.list(containerName)).isEmpty();

        Blob blob1 = s3BlobStore.blobBuilder("prefix/blob1")
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob1);

        Blob blob2 = s3BlobStore.blobBuilder("prefix/blob2")
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob2);

        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (StorageMetadata metadata : s3BlobStore.list(containerName)) {
            builder.add(metadata.getName());
        }
        assertThat(builder.build()).containsOnly("prefix");

        builder = ImmutableSet.builder();
        for (StorageMetadata metadata : s3BlobStore.list(containerName,
                new ListContainerOptions().recursive())) {
            builder.add(metadata.getName());
        }
        assertThat(builder.build()).containsOnly("prefix/blob1",
                "prefix/blob2");
    }

    @Test
    public void testBlobMetadata() throws Exception {
        String blobName = "blob";
        Blob blob1 = s3BlobStore.blobBuilder(blobName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob1);

        BlobMetadata metadata = s3BlobStore.blobMetadata(containerName,
                blobName);
        assertThat(metadata.getName()).isEqualTo(blobName);
        assertThat(metadata.getContentMetadata().getContentLength())
                .isEqualTo(BYTE_SOURCE.size());

        assertThat(s3BlobStore.blobMetadata(containerName,
                "fake-blob")).isNull();
    }

    @Test
    public void testBlobRemove() throws Exception {
        String blobName = "blob";
        Blob blob = s3BlobStore.blobBuilder(blobName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        s3BlobStore.putBlob(containerName, blob);
        assertThat(s3BlobStore.blobExists(containerName, blobName)).isTrue();

        s3BlobStore.removeBlob(containerName, blobName);
        assertThat(s3BlobStore.blobExists(containerName, blobName)).isFalse();

        s3BlobStore.removeBlob(containerName, blobName);
    }

    // TODO: this test fails since S3BlobRequestSigner does not implement the
    // same logic as AWSS3BlobRequestSigner.signForTemporaryAccess.
    @Ignore
    @Test
    public void testUrlSigning() throws Exception {
        HttpClient httpClient = s3Context.utils().http();
        BlobRequestSigner signer = s3Context.getSigner();

        String blobName = "blob";
        Blob blob = s3BlobStore.blobBuilder(blobName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .build();
        HttpRequest putRequest = signer.signPutBlob(containerName, blob, 10);
        HttpResponse putResponse = httpClient.invoke(putRequest);
        assertThat(putResponse.getStatusCode())
                .isEqualTo(HttpServletResponse.SC_OK);

        HttpRequest getRequest = signer.signGetBlob(containerName, blobName,
                10);
        HttpResponse getResponse = httpClient.invoke(getRequest);
        assertThat(getResponse.getStatusCode())
                .isEqualTo(HttpServletResponse.SC_OK);
    }

    // TODO: fails for GCS (jclouds not implemented)
    // TODO: fails for Swift (content and user metadata not set)
    @Test
    public void testMultipartUpload() throws Exception {
        String blobName = "blob";
        String contentDisposition = "attachment; filename=new.jpg";
        String contentEncoding = "gzip";
        String contentLanguage = "fr";
        String contentType = "audio/mp4";
        Map<String, String> userMetadata = ImmutableMap.of(
                "key1", "value1",
                "key2", "value2");
        BlobMetadata blobMetadata = s3BlobStore.blobBuilder(blobName)
                .payload(new byte[0])  // fake payload to add content metadata
                .contentDisposition(contentDisposition)
                .contentEncoding(contentEncoding)
                .contentLanguage(contentLanguage)
                .contentType(contentType)
                // TODO: expires
                .userMetadata(userMetadata)
                .build()
                .getMetadata();
        MultipartUpload mpu = s3BlobStore.initiateMultipartUpload(
                containerName, blobMetadata);

        ByteSource byteSource = ByteSource.wrap(
                new byte[(int) blobStore.getMinimumMultipartPartSize() + 1]);
        ByteSource byteSource1 = byteSource.slice(
                0, blobStore.getMinimumMultipartPartSize());
        ByteSource byteSource2 = byteSource.slice(
                blobStore.getMinimumMultipartPartSize(), 1);
        Payload payload1 = Payloads.newByteSourcePayload(byteSource1);
        Payload payload2 = Payloads.newByteSourcePayload(byteSource2);
        payload1.getContentMetadata().setContentLength(byteSource1.size());
        payload2.getContentMetadata().setContentLength(byteSource2.size());
        MultipartPart part1 = s3BlobStore.uploadMultipartPart(mpu, 1, payload1);
        MultipartPart part2 = s3BlobStore.uploadMultipartPart(mpu, 2, payload2);

        s3BlobStore.completeMultipartUpload(mpu, ImmutableList.of(part1,
                part2));

        Blob newBlob = s3BlobStore.getBlob(containerName, blobName);
        try (InputStream expected = newBlob.getPayload().openStream();
                InputStream actual = byteSource.openStream()) {
            assertThat(expected).hasContentEqualTo(actual);
        }
        ContentMetadata expectedContentMetadata =
                blobMetadata.getContentMetadata();
        assertThat(expectedContentMetadata.getContentDisposition()).isEqualTo(
                contentDisposition);
        assertThat(expectedContentMetadata.getContentEncoding()).isEqualTo(
                contentEncoding);
        assertThat(expectedContentMetadata.getContentLanguage()).isEqualTo(
                contentLanguage);
        assertThat(expectedContentMetadata.getContentType()).isEqualTo(
                contentType);
        // TODO: expires
        assertThat(newBlob.getMetadata().getUserMetadata()).isEqualTo(
                userMetadata);
    }

    @Test
    public void testCopyObjectPreserveMetadata() throws Exception {
        String fromName = "from-name";
        String toName = "to-name";
        String contentDisposition = "attachment; filename=old.jpg";
        String contentEncoding = "gzip";
        String contentLanguage = "en";
        String contentType = "audio/ogg";
        Date expires = new Date(1000);
        Map<String, String> userMetadata = ImmutableMap.of(
                "key1", "value1",
                "key2", "value2");
        Blob fromBlob = s3BlobStore.blobBuilder(fromName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .contentDisposition(contentDisposition)
                .contentEncoding(contentEncoding)
                .contentLanguage(contentLanguage)
                .contentType(contentType)
                .expires(expires)
                .userMetadata(userMetadata)
                .build();
        s3BlobStore.putBlob(containerName, fromBlob);

        s3BlobStore.copyBlob(containerName, fromName, containerName, toName,
                CopyOptions.NONE);

        Blob toBlob = s3BlobStore.getBlob(containerName, toName);
        try (InputStream actual = toBlob.getPayload().openStream();
                InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
        ContentMetadata contentMetadata =
                toBlob.getMetadata().getContentMetadata();
        assertThat(contentMetadata.getContentDisposition()).isEqualTo(
                contentDisposition);
        assertThat(contentMetadata.getContentEncoding()).isEqualTo(
                contentEncoding);
        assertThat(contentMetadata.getContentLanguage()).isEqualTo(
                contentLanguage);
        assertThat(contentMetadata.getContentType()).isEqualTo(
                contentType);
        // TODO: expires
        assertThat(toBlob.getMetadata().getUserMetadata()).isEqualTo(
                userMetadata);
    }

    @Test
    public void testCopyObjectReplaceMetadata() throws Exception {
        String fromName = "from-name";
        String toName = "to-name";
        Blob fromBlob = s3BlobStore.blobBuilder(fromName)
                .payload(BYTE_SOURCE)
                .contentLength(BYTE_SOURCE.size())
                .contentDisposition("attachment; filename=old.jpg")
                .contentEncoding("compress")
                .contentLanguage("en")
                .contentType("audio/ogg")
                .expires(new Date(1000))
                .userMetadata(ImmutableMap.of(
                        "key1", "value1",
                        "key2", "value2"))
                .build();
        s3BlobStore.putBlob(containerName, fromBlob);

        String contentDisposition = "attachment; filename=new.jpg";
        String contentEncoding = "gzip";
        String contentLanguage = "fr";
        String contentType = "audio/mp4";
        Date expires = new Date(2000);
        ContentMetadata contentMetadata = ContentMetadataBuilder.create()
                .contentDisposition(contentDisposition)
                .contentEncoding(contentEncoding)
                .contentLanguage(contentLanguage)
                .contentType(contentType)
                .expires(expires)
                .build();
        Map<String, String> userMetadata = ImmutableMap.of(
                "key3", "value3",
                "key4", "value4");
        s3BlobStore.copyBlob(containerName, fromName, containerName, toName,
                CopyOptions.builder()
                        .contentMetadata(contentMetadata)
                        .userMetadata(userMetadata)
                        .build());

        Blob toBlob = s3BlobStore.getBlob(containerName, toName);
        try (InputStream actual = toBlob.getPayload().openStream();
                InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
        ContentMetadata toContentMetadata =
                toBlob.getMetadata().getContentMetadata();
        assertThat(toContentMetadata.getContentDisposition()).isEqualTo(
                contentDisposition);
        assertThat(toContentMetadata.getContentEncoding()).isEqualTo(
                contentEncoding);
        assertThat(toContentMetadata.getContentLanguage()).isEqualTo(
                contentLanguage);
        assertThat(toContentMetadata.getContentType()).isEqualTo(
                contentType);
        // TODO: expires
        assertThat(toBlob.getMetadata().getUserMetadata()).isEqualTo(
                userMetadata);
    }

    @Test
    public void testUnknownParameter() throws Exception {
        final S3Client s3Client = s3Context.unwrapApi(S3Client.class);

        Throwable thrown = catchThrowable(new ThrowingCallable() {
                @Override
                public void call() throws Exception {
                    s3Client.disableBucketLogging(containerName);
                }
            });
        assertThat(thrown).isInstanceOf(AWSResponseException.class);
        ((AWSResponseException) thrown).getError().getCode().equals(
                "NotImplemented");
    }

    private static String createRandomContainerName() {
        return "s3proxy-" + new Random().nextInt(Integer.MAX_VALUE);
    }
}
