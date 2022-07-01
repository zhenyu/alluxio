/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.proxy.s3;

import alluxio.AlluxioURI;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.conf.PropertyKey;
import alluxio.conf.Configuration;
import alluxio.exception.AlluxioException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.DeletePOptions;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;

/**
 * A handler process multipart upload complete response.
 */
public class CompleteMultipartUploadHandler extends AbstractHandler {
  private static final Logger LOG = LoggerFactory.getLogger(CompleteMultipartUploadHandler.class);

  private final String mS3Prefix;

  private final FileSystem mFileSystem;
  private final ExecutorService mExecutor;
  private final Long mKeepAliveTime;

  /**
   * Creates a new instance of {@link CompleteMultipartUploadHandler}.
   * @param fs instance of {@link FileSystem}
   * @param baseUri the Web server's base URI used for building the handler's matching URI
   */
  public CompleteMultipartUploadHandler(final FileSystem fs, final String baseUri) {
    mFileSystem = fs;
    mExecutor = Executors.newFixedThreadPool(Configuration.getInt(
        PropertyKey.PROXY_S3_COMPLETE_MULTIPART_UPLOAD_POOL_SIZE));
    mKeepAliveTime = Configuration.getMs(
        PropertyKey.PROXY_S3_COMPLETE_MULTIPART_UPLOAD_KEEPALIVE_TIME_INTERVAL);
    mS3Prefix = baseUri + AlluxioURI.SEPARATOR + S3RestServiceHandler.SERVICE_PREFIX
        + AlluxioURI.SEPARATOR;
  }

  /**
   * Process s3 multipart upload request.
   */
  @Override
  public void handle(String s, Request request, HttpServletRequest httpServletRequest,
                     HttpServletResponse httpServletResponse) throws IOException {
    if (!s.startsWith(mS3Prefix)) {
      return;
    }
    LOG.info("Alluxio S3 API received request: {}", request);
    if (!request.getMethod().equals("POST")
        || request.getParameter("uploadId") == null) {
      return;
    } // Otherwise, handle CompleteMultipartUpload
    s = s.substring(mS3Prefix.length());
    final String bucket = s.substring(0, s.indexOf(AlluxioURI.SEPARATOR));
    final String object = s.substring(s.indexOf(AlluxioURI.SEPARATOR) + 1);
    final String uploadId = request.getParameter("uploadId");
    LOG.debug("(bucket: {}, object: {}, uploadId: {}) queuing task...",
        bucket, object, uploadId);

    httpServletResponse.setStatus(HttpServletResponse.SC_OK);
    httpServletResponse.setContentType(MediaType.APPLICATION_XML);

    Future<CompleteMultipartUploadResult> future =
        mExecutor.submit(new CompleteMultipartUploadTask(mFileSystem, bucket, object, uploadId,
            IOUtils.toString(request.getReader())));
    long sleepMs = 1000;
    while (!future.isDone()) {
      LOG.debug("(bucket: {}, object: {}, uploadId: {}) sleeping for {}ms...",
          bucket, object, uploadId, sleepMs);
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException e) {
        LOG.error(e.toString());
      }
      //periodically sends white space characters to keep the connection from timing out
      LOG.debug("(bucket: {}, object: {}, uploadId: {}) sending whitespace...",
          bucket, object, uploadId);
      httpServletResponse.getWriter().print(" ");
      httpServletResponse.getWriter().flush();
      sleepMs = Math.min(2 * sleepMs, mKeepAliveTime);
    }

    XmlMapper mapper = new XmlMapper();
    try {
      CompleteMultipartUploadResult result = future.get();
      httpServletResponse.getWriter().write(mapper.writeValueAsString(result));
    } catch (InterruptedException | ExecutionException e) {
      LOG.error(e.toString());
    }
    httpServletResponse.getWriter().flush();
    request.setHandled(true);
  }

  /**
   * Complete Multipart upload Task.
   */
  public class CompleteMultipartUploadTask implements
      Callable<CompleteMultipartUploadResult> {

    private final FileSystem mFileSystem;
    private final String mBucket;
    private final String mObject;
    private final String mUploadId;
    private final String mBody;
    private final boolean mMultipartCleanerEnabled = Configuration.getBoolean(
        PropertyKey.PROXY_S3_MULTIPART_UPLOAD_CLEANER_ENABLED);

    /**
     * Creates a new instance of {@link CompleteMultipartUploadTask}.
     *
     * @param fileSystem instance of {@link FileSystem}
     * @param bucket bucket name
     * @param object object name
     * @param uploadId multipart upload Id
     * @param body the HTTP request body
     */
    public CompleteMultipartUploadTask(FileSystem fileSystem, String bucket, String object,
                                       String uploadId, String body) {
      mFileSystem = fileSystem;
      mBucket = bucket;
      mObject = object;
      mUploadId = uploadId;
      mBody = body;
    }

    @Override
    public CompleteMultipartUploadResult call() throws S3Exception {
      try {
        String bucketPath = S3RestUtils.parsePath(AlluxioURI.SEPARATOR + mBucket);
        S3RestUtils.checkPathIsAlluxioDirectory(mFileSystem, bucketPath);
        String objectPath = bucketPath + AlluxioURI.SEPARATOR + mObject;
        AlluxioURI multipartTemporaryDir = new AlluxioURI(
            S3RestUtils.getMultipartTemporaryDirForObject(bucketPath, mObject, mUploadId));
        URIStatus metaStatus;
        try {
          metaStatus = S3RestUtils.checkStatusesForUploadId(mFileSystem, multipartTemporaryDir,
              mUploadId).get(1);
        } catch (Exception e) {
          throw new S3Exception(objectPath, S3ErrorCode.NO_SUCH_UPLOAD);
        }

        // Parse the HTTP request body to get the intended list of parts
        CompleteMultipartUploadRequest request;
        try {
          request = new XmlMapper().readerFor(CompleteMultipartUploadRequest.class)
              .readValue(mBody);
        } catch (IllegalArgumentException e) {
          if (e.getCause() instanceof S3Exception) {
            throw S3RestUtils.toObjectS3Exception((S3Exception) e.getCause(), objectPath);
          }
          throw S3RestUtils.toObjectS3Exception(e, objectPath);
        }

        // Check if the requested parts are available
        List<URIStatus> parts = mFileSystem.listStatus(multipartTemporaryDir);
        parts.sort(new S3RestUtils.URIStatusNameComparator());
        if (parts.size() != request.getParts().size()) {
          throw new S3Exception(objectPath, S3ErrorCode.INVALID_PART);
        }
        for (int i = 0; i < parts.size(); i++) {
          if (Integer.parseInt(parts.get(i).getName())
              != request.getParts().get(i).getPartNumber()) {
            throw new S3Exception(objectPath, S3ErrorCode.INVALID_PART);
          }
          if (i != parts.size() - 1 // size requirement not applicable to last part
              && parts.get(i).getBlockSizeBytes() < Configuration.getBytes(
                  PropertyKey.PROXY_S3_MULTIPART_UPLOAD_MIN_PART_SIZE)) {
            throw new S3Exception(objectPath, S3ErrorCode.ENTITY_TOO_SMALL);
          }
        }

        CreateFilePOptions.Builder optionsBuilder = CreateFilePOptions.newBuilder()
            .setRecursive(true)
            .setWriteType(S3RestUtils.getS3WriteType());
        // Copy Tagging xAttr if it exists
        if (metaStatus.getXAttr().containsKey(S3Constants.TAGGING_XATTR_KEY)) {
          optionsBuilder.putXattr(S3Constants.TAGGING_XATTR_KEY,
              ByteString.copyFrom(metaStatus.getXAttr().get(S3Constants.TAGGING_XATTR_KEY)));
        }
        // remove exist object
        AlluxioURI objectUri = new AlluxioURI(objectPath);
        try {
          S3RestUtils.deleteExistObject(mFileSystem, objectUri);
        } catch (IOException | AlluxioException e) {
          throw S3RestUtils.toObjectS3Exception(e, objectUri.getPath());
        }
        // (re)create the merged object
        LOG.debug("CompleteMultipartUploadTask (bucket: {}, object: {}, uploadId: {}) "
            + "combining {} parts...", mBucket, mObject, mUploadId, parts.size());
        FileOutStream os = mFileSystem.createFile(objectUri, optionsBuilder.build());
        MessageDigest md5 = MessageDigest.getInstance("MD5");

        try (DigestOutputStream digestOutputStream = new DigestOutputStream(os, md5)) {
          for (URIStatus part : parts) {
            try (FileInStream is = mFileSystem.openFile(new AlluxioURI(part.getPath()))) {
              ByteStreams.copy(is, digestOutputStream);
            }
          }
        }

        // Remove the temporary directory containing the uploaded parts and the
        // corresponding Alluxio S3 API metadata file
        mFileSystem.delete(multipartTemporaryDir,
            DeletePOptions.newBuilder().setRecursive(true).build());
        mFileSystem.delete(new AlluxioURI(
            S3RestUtils.getMultipartMetaFilepathForUploadId(mUploadId)),
            DeletePOptions.newBuilder().build());
        if (mMultipartCleanerEnabled) {
          MultipartUploadCleaner.cancelAbort(mFileSystem, mBucket, mObject, mUploadId);
        }
        String entityTag = Hex.encodeHexString(md5.digest());
        return new CompleteMultipartUploadResult(objectPath, mBucket, mObject, entityTag);
      } catch (FileDoesNotExistException e) {
        return new CompleteMultipartUploadResult(S3ErrorCode.Name.NO_SUCH_UPLOAD, e.getMessage());
      } catch (Exception e) {
        return new CompleteMultipartUploadResult(S3ErrorCode.Name.INTERNAL_ERROR, e.getMessage());
      }
    }
  }
}
