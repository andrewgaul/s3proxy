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

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.HttpMethod;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.google.common.base.Throwables;
import com.google.common.io.ByteSource;

import org.assertj.core.api.Fail;

import org.jclouds.blobstore.BlobStoreContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public final class S3AwsSdkTest {
    static {
        System.setProperty(
                SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY,
                "true");
        System.setProperty(
                SDKGlobalConfiguration.ENFORCE_S3_SIGV4_SYSTEM_PROPERTY,
                "true");
        disableSslVerification();
    }

    private static final ByteSource BYTE_SOURCE = ByteSource.wrap(new byte[1]);

    private URI s3Endpoint;
    private S3Proxy s3Proxy;
    private BlobStoreContext context;
    private String containerName;
    private BasicAWSCredentials awsCreds;

    @Before
    public void setUp() throws Exception {
        TestUtils.S3ProxyLaunchInfo info = TestUtils.startS3Proxy();
        awsCreds = new BasicAWSCredentials(info.getS3Identity(),
                info.getS3Credential());
        context = info.getBlobStore().getContext();
        s3Proxy = info.getS3Proxy();
        s3Endpoint = info.getEndpoint();

        containerName = createRandomContainerName();
        info.getBlobStore().createContainerInLocation(null, containerName);
    }

    @After
    public void tearDown() throws Exception {
        if (s3Proxy != null) {
            s3Proxy.stop();
        }
        if (context != null) {
            context.getBlobStore().deleteContainer(containerName);
            context.close();
        }
    }

    @Test
    public void testAwsV2Signature() throws Exception {
        AmazonS3 client = new AmazonS3Client(awsCreds,
                new ClientConfiguration().withSignerOverride("S3SignerType"));
        client.setEndpoint(s3Endpoint.toString());
        client.putObject(containerName, "foo", BYTE_SOURCE.openStream(),
                new ObjectMetadata());
    }

    @Test
    public void testAwsV4Signature() throws Exception {
        final AmazonS3 client = new AmazonS3Client(awsCreds);
        client.setEndpoint(s3Endpoint.toString());

        try {
            client.putObject(containerName, "foo",
                    BYTE_SOURCE.openStream(), new ObjectMetadata());
            Fail.failBecauseExceptionWasNotThrown(AmazonS3Exception.class);
        } catch (AmazonS3Exception e) {
            assertThat(e.getErrorCode()).isEqualTo("InvalidArgument");
        }
    }

    // TODO: cannot test with jclouds since S3BlobRequestSigner does not
    // implement the same logic as
    // AWSS3BlobRequestSigner.signForTemporaryAccess.
    @Test
    public void testUrlSigning() throws Exception {
        AmazonS3 client = new AmazonS3Client(awsCreds,
                new ClientConfiguration().withSignerOverride("S3SignerType"));
        client.setEndpoint(s3Endpoint.toString());

        String blobName = "foo";
        client.putObject(containerName, blobName, BYTE_SOURCE.openStream(),
                new ObjectMetadata());

        Date expiration = new Date(System.currentTimeMillis() +
                TimeUnit.HOURS.toMillis(1));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                containerName, blobName);
        request.setMethod(HttpMethod.GET);
        request.setExpiration(expiration);

        URL url = client.generatePresignedUrl(request);
        try (InputStream actual = url.openStream();
                InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
    }

    // TODO: jclouds lacks support for multipart copy
    @Test
    public void testMultipartCopy() throws Exception {
        AmazonS3 client = new AmazonS3Client(awsCreds,
                new ClientConfiguration().withSignerOverride("S3SignerType"));
        client.setEndpoint(s3Endpoint.toString());

        String sourceBlobName = "testMultipartCopy-source";
        String targetBlobName = "testMultipartCopy-target";

        client.putObject(containerName, sourceBlobName,
                BYTE_SOURCE.openStream(), new ObjectMetadata());

        InitiateMultipartUploadRequest initiateRequest =
                new InitiateMultipartUploadRequest(containerName,
                        targetBlobName);
        InitiateMultipartUploadResult initResult =
                client.initiateMultipartUpload(initiateRequest);
        String uploadId = initResult.getUploadId();

        CopyPartRequest copyRequest = new CopyPartRequest()
                .withDestinationBucketName(containerName)
                .withDestinationKey(targetBlobName)
                .withSourceBucketName(containerName)
                .withSourceKey(sourceBlobName)
                .withUploadId(initResult.getUploadId())
                .withFirstByte(0L)
                .withLastByte(BYTE_SOURCE.size() - 1)
                .withPartNumber(1);
        CopyPartResult copyPartResult = client.copyPart(copyRequest);

        List<PartETag> partETags = new ArrayList<>();
        partETags.add(copyPartResult.getPartETag());
        CompleteMultipartUploadRequest completeRequest =
                new CompleteMultipartUploadRequest(
                        containerName, targetBlobName,
                        initResult.getUploadId(), partETags);

        CompleteMultipartUploadResult completeUploadResponse =
                client.completeMultipartUpload(completeRequest);

        S3Object object = client.getObject(new GetObjectRequest(containerName,
                targetBlobName));
        try (InputStream actual = object.getObjectContent();
                InputStream expected = BYTE_SOURCE.openStream()) {
            assertThat(actual).hasContentEqualTo(expected);
        }
    }

    @Ignore
    @Test
    public void testBigMultipartUpload() throws Exception {
        AmazonS3 client = new AmazonS3Client(awsCreds,
                new ClientConfiguration().withSignerOverride("S3SignerType"));
        client.setEndpoint(s3Endpoint.toString());

        String key = "multipart-upload";
        int size = 10_000_000;
        ByteSource byteSource = TestUtils.randomByteSource().slice(0, size);

        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(containerName, key);
        InitiateMultipartUploadResult initResponse = client.initiateMultipartUpload(initRequest);
        String uploadId = initResponse.getUploadId();

        UploadPartRequest uploadRequest = new UploadPartRequest()
                .withBucketName(containerName).withKey(key)
                .withUploadId(uploadId).withPartNumber(1)
                .withInputStream(byteSource.openStream())
                .withPartSize(size);

        UploadPartResult uploadPartResult = client.uploadPart(uploadRequest);
        PartETag partETag = uploadPartResult.getPartETag();
        List<PartETag> partETagList = Collections.singletonList(partETag);

        CompleteMultipartUploadRequest completeRequest = new
                CompleteMultipartUploadRequest(
                containerName,
                key,
                uploadId,
                partETagList);
        client.completeMultipartUpload(completeRequest);

        // TODO test that the results are equal

    }

    private static final class NullX509TrustManager
            implements X509TrustManager {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certs,
                String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certs,
                String authType) {
        }
    }

    private static void disableSslVerification() {
        try {
            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = new TrustManager[] {
                new NullX509TrustManager() };

            // Install the all-trusting trust manager
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(
                    sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };

            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw Throwables.propagate(e);
        }
    }

    private static String createRandomContainerName() {
        return "s3proxy-" + new Random().nextInt(Integer.MAX_VALUE);
    }
}
