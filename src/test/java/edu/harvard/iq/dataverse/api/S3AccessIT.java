package edu.harvard.iq.dataverse.api;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import io.restassured.RestAssured;
import static io.restassured.RestAssured.given;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.apache.commons.lang3.math.NumberUtils;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * This test requires LocalStack and Minio to be running. Developers can use our
 * docker-compose file, which has all the necessary configuration.
 */
public class S3AccessIT {

    private static final Logger logger = Logger.getLogger(S3AccessIT.class.getCanonicalName());

    static final String BUCKET_NAME = "mybucket";
    static AmazonS3 s3localstack = null;
    static AmazonS3 s3minio = null;

    @BeforeAll
    public static void setUp() {
        RestAssured.baseURI = UtilIT.getRestAssuredBaseUri();

        // At least in when spun up by our docker-compose file, the creds don't matter for LocalStack.
        String accessKeyLocalStack = "whatever";
        String secretKeyLocalStack = "not used";

        s3localstack = AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyLocalStack, secretKeyLocalStack)))
                .withEndpointConfiguration(new EndpointConfiguration("s3.localhost.localstack.cloud:4566", Regions.US_EAST_2.getName())).build();

        String accessKeyMinio = "minioadmin";
        String secretKeyMinio = "minioadmin";
        s3minio = AmazonS3ClientBuilder.standard()
                // https://stackoverflow.com/questions/72205086/amazonss3client-throws-unknownhostexception-if-attempting-to-connect-to-a-local
                .withPathStyleAccessEnabled(Boolean.TRUE)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyMinio, secretKeyMinio)))
                .withEndpointConfiguration(new EndpointConfiguration("http://localhost:9000", Regions.US_EAST_1.getName())).build();

        System.out.println("buckets on LocalStack before attempting to create " + BUCKET_NAME);
        for (Bucket bucket : s3localstack.listBuckets()) {
            System.out.println("bucket: " + bucket);
        }

        System.out.println("buckets on MinIO before attempting to create " + BUCKET_NAME);
        for (Bucket bucket : s3minio.listBuckets()) {
            System.out.println("bucket: " + bucket);
        }

        // create bucket if it doesn't exist
        // Note that we create the localstack bucket with conf/localstack/buckets.sh
        // because we haven't figured out how to create it properly in Java.
        // Perhaps it is missing ACLs.
        try {
            s3localstack.headBucket(new HeadBucketRequest(BUCKET_NAME));
        } catch (AmazonS3Exception ex) {
            s3localstack.createBucket(BUCKET_NAME);
        }

        try {
            s3minio.headBucket(new HeadBucketRequest(BUCKET_NAME));
        } catch (AmazonS3Exception ex) {
            s3minio.createBucket(BUCKET_NAME);
        }

    }

    /**
     * We're using MinIO for testing non-direct upload.
     */
    @Test
    public void testNonDirectUpload() {
        String driverId = "minio1";
        String driverLabel = "MinIO";

        Response createSuperuser = UtilIT.createRandomUser();
        String superuserApiToken = UtilIT.getApiTokenFromResponse(createSuperuser);
        String superusername = UtilIT.getUsernameFromResponse(createSuperuser);
        UtilIT.makeSuperUser(superusername);
        Response storageDrivers = listStorageDrivers(superuserApiToken);
        storageDrivers.prettyPrint();
        // TODO where is "Local/local" coming from?
        String drivers = """
{
    "status": "OK",
    "data": {
        "LocalStack": "localstack1",
        "MinIO": "minio1",
        "Local": "local",
        "Filesystem": "file1"
    }
}""";

        //create user who will make a dataverse/dataset
        Response createUser = UtilIT.createRandomUser();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.prettyPrint();
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        Response originalStorageDriver = getStorageDriver(dataverseAlias, superuserApiToken);
        originalStorageDriver.prettyPrint();
        originalStorageDriver.then().assertThat()
                .body("data.message", equalTo("undefined"))
                .statusCode(200);

        Response setStorageDriverToS3 = setStorageDriver(dataverseAlias, driverLabel, superuserApiToken);
        setStorageDriverToS3.prettyPrint();
        setStorageDriverToS3.then().assertThat()
                .statusCode(200);

        Response updatedStorageDriver = getStorageDriver(dataverseAlias, superuserApiToken);
        updatedStorageDriver.prettyPrint();
        updatedStorageDriver.then().assertThat()
                .statusCode(200);

        Response createDatasetResponse = UtilIT.createRandomDatasetViaNativeApi(dataverseAlias, apiToken);
        createDatasetResponse.prettyPrint();
        createDatasetResponse.then().assertThat().statusCode(201);
        Integer datasetId = JsonPath.from(createDatasetResponse.body().asString()).getInt("data.id");
        String datasetPid = JsonPath.from(createDatasetResponse.body().asString()).getString("data.persistentId");
        String datasetStorageIdentifier = datasetPid.substring(4);

        Response getDatasetMetadata = UtilIT.nativeGet(datasetId, apiToken);
        getDatasetMetadata.prettyPrint();
        getDatasetMetadata.then().assertThat().statusCode(200);

        //upload a tabular file via native, check storage id prefix for driverId
        String pathToFile = "scripts/search/data/tabular/1char";
        Response addFileResponse = UtilIT.uploadFileViaNative(datasetId.toString(), pathToFile, apiToken);
        addFileResponse.prettyPrint();
        addFileResponse.then().assertThat()
                .statusCode(200)
                .body("data.files[0].dataFile.storageIdentifier", startsWith(driverId + "://"));

        String fileId = JsonPath.from(addFileResponse.body().asString()).getString("data.files[0].dataFile.id");

        Response getfileMetadata = UtilIT.getFileData(fileId, apiToken);
        getfileMetadata.prettyPrint();
        getfileMetadata.then().assertThat().statusCode(200);

        String storageIdentifier = JsonPath.from(addFileResponse.body().asString()).getString("data.files[0].dataFile.storageIdentifier");
        String keyInDataverse = storageIdentifier.split(":")[2];
        Assertions.assertEquals(driverId + "://" + BUCKET_NAME + ":" + keyInDataverse, storageIdentifier);

        String keyInS3 = datasetStorageIdentifier + "/" + keyInDataverse;
        String s3Object = s3minio.getObjectAsString(BUCKET_NAME, keyInS3);
        System.out.println("s3Object: " + s3Object);

        // The file uploaded above only contains the character "a".
        assertEquals("a".trim(), s3Object.trim());

        Response deleteFile = UtilIT.deleteFileApi(Integer.parseInt(fileId), apiToken);
        deleteFile.prettyPrint();
        deleteFile.then().assertThat().statusCode(200);

        AmazonS3Exception expectedException = null;
        try {
            s3minio.getObjectAsString(BUCKET_NAME, keyInS3);
        } catch (AmazonS3Exception ex) {
            expectedException = ex;
        }
        assertNotNull(expectedException);
        // 404 because the file has been sucessfully deleted
        assertEquals(404, expectedException.getStatusCode());

    }

    /**
     * We use LocalStack to test direct upload.
     */
    @Test
    public void testDirectUpload() {
        String driverId = "localstack1";
        String driverLabel = "LocalStack";
        Response createSuperuser = UtilIT.createRandomUser();
        String superuserApiToken = UtilIT.getApiTokenFromResponse(createSuperuser);
        String superusername = UtilIT.getUsernameFromResponse(createSuperuser);
        UtilIT.makeSuperUser(superusername);
        Response storageDrivers = listStorageDrivers(superuserApiToken);
        storageDrivers.prettyPrint();
        // TODO where is "Local/local" coming from?
        String drivers = """
{
    "status": "OK",
    "data": {
        "LocalStack": "localstack1",
        "MinIO": "minio1",
        "Local": "local",
        "Filesystem": "file1"
    }
}""";

        //create user who will make a dataverse/dataset
        Response createUser = UtilIT.createRandomUser();
        String username = UtilIT.getUsernameFromResponse(createUser);
        String apiToken = UtilIT.getApiTokenFromResponse(createUser);

        Response createDataverseResponse = UtilIT.createRandomDataverse(apiToken);
        createDataverseResponse.prettyPrint();
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        Response originalStorageDriver = getStorageDriver(dataverseAlias, superuserApiToken);
        originalStorageDriver.prettyPrint();
        originalStorageDriver.then().assertThat()
                .body("data.message", equalTo("undefined"))
                .statusCode(200);

        Response setStorageDriverToS3 = setStorageDriver(dataverseAlias, driverLabel, superuserApiToken);
        setStorageDriverToS3.prettyPrint();
        setStorageDriverToS3.then().assertThat()
                .statusCode(200);

        Response updatedStorageDriver = getStorageDriver(dataverseAlias, superuserApiToken);
        updatedStorageDriver.prettyPrint();
        updatedStorageDriver.then().assertThat()
                .statusCode(200);

        Response createDatasetResponse = UtilIT.createRandomDatasetViaNativeApi(dataverseAlias, apiToken);
        createDatasetResponse.prettyPrint();
        createDatasetResponse.then().assertThat().statusCode(201);
        Integer datasetId = JsonPath.from(createDatasetResponse.body().asString()).getInt("data.id");
        String datasetPid = JsonPath.from(createDatasetResponse.body().asString()).getString("data.persistentId");
        String datasetStorageIdentifier = datasetPid.substring(4);

        Response getDatasetMetadata = UtilIT.nativeGet(datasetId, apiToken);
        getDatasetMetadata.prettyPrint();
        getDatasetMetadata.then().assertThat().statusCode(200);

//        //upload a tabular file via native, check storage id prefix for driverId
//        String pathToFile = "scripts/search/data/tabular/1char";
//        Response addFileResponse = UtilIT.uploadFileViaNative(datasetId.toString(), pathToFile, apiToken);
//        addFileResponse.prettyPrint();
//        addFileResponse.then().assertThat()
//                .statusCode(200)
//                .body("data.files[0].dataFile.storageIdentifier", startsWith(driverId + "://"));
//
//        String fileId = JsonPath.from(addFileResponse.body().asString()).getString("data.files[0].dataFile.id");
        long size = 1000000000l;
        Response getUploadUrls = getUploadUrls(datasetPid, size, apiToken);
        getUploadUrls.prettyPrint();
        getUploadUrls.then().assertThat().statusCode(200);

        String url = JsonPath.from(getUploadUrls.asString()).getString("data.url");
        String partSize = JsonPath.from(getUploadUrls.asString()).getString("data.partSize");
        String storageIdentifier = JsonPath.from(getUploadUrls.asString()).getString("data.storageIdentifier");
        System.out.println("url: " + url);
        System.out.println("partSize: " + partSize);
        System.out.println("storageIdentifier: " + storageIdentifier);

        System.out.println("uploading file via direct upload");
        String decodedUrl = null;
        try {
            decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
        }

        // change to localhost because LocalStack is running in a container locally
        String localhostUrl = decodedUrl.replace("http://localstack", "http://localhost");
        String contentsOfFile = "foobar";

        InputStream inputStream = new ByteArrayInputStream(contentsOfFile.getBytes(StandardCharsets.UTF_8));
        Response uploadFileDirect = uploadFileDirect(localhostUrl, inputStream);
        uploadFileDirect.prettyPrint();
        /*
        Direct upload to MinIO is failing with errors like this:
        <Error>
          <Code>SignatureDoesNotMatch</Code>
          <Message>The request signature we calculated does not match the signature you provided. Check your key and signing method.</Message>
          <Key>10.5072/FK2/KGFCEJ/18b8c06688c-21b8320a3ee5</Key>
          <BucketName>mybucket</BucketName>
          <Resource>/mybucket/10.5072/FK2/KGFCEJ/18b8c06688c-21b8320a3ee5</Resource>
          <RequestId>1793915CCC5BC95C</RequestId>
          <HostId>dd9025bab4ad464b049177c95eb6ebf374d3b3fd1af9251148b658df7ac2e3e8</HostId>
        </Error>
         */
        uploadFileDirect.then().assertThat().statusCode(200);

        // TODO: Use MD5 or whatever Dataverse is configured for and
        // actually calculate it.
        String jsonData = """
{
    "description": "My description.",
    "directoryLabel": "data/subdir1",
    "categories": [
      "Data"
    ],
    "restrict": "false",
    "storageIdentifier": "%s",
    "fileName": "file1.txt",
    "mimeType": "text/plain",
    "checksum": {
      "@type": "SHA-1",
      "@value": "123456"
    }
}
""".formatted(storageIdentifier);

        // "There was an error when trying to add the new file. File size must be explicitly specified when creating DataFiles with Direct Upload"
        Response addRemoteFile = UtilIT.addRemoteFile(datasetId.toString(), jsonData, apiToken);
        addRemoteFile.prettyPrint();
        addRemoteFile.then().assertThat()
                .statusCode(200);

        String fileId = JsonPath.from(addRemoteFile.asString()).getString("data.files[0].dataFile.id");
        Response getfileMetadata = UtilIT.getFileData(fileId, apiToken);
        getfileMetadata.prettyPrint();
        getfileMetadata.then().assertThat().statusCode(200);

//        String storageIdentifier = JsonPath.from(addFileResponse.body().asString()).getString("data.files[0].dataFile.storageIdentifier");
        String keyInDataverse = storageIdentifier.split(":")[2];
        Assertions.assertEquals(driverId + "://" + BUCKET_NAME + ":" + keyInDataverse, storageIdentifier);

        String keyInS3 = datasetStorageIdentifier + "/" + keyInDataverse;
        String s3Object = s3localstack.getObjectAsString(BUCKET_NAME, keyInS3);
        System.out.println("s3Object: " + s3Object);

//        assertEquals(contentsOfFile.trim(), s3Object.trim());
        assertEquals(contentsOfFile, s3Object);

        Response deleteFile = UtilIT.deleteFileApi(Integer.parseInt(fileId), apiToken);
        deleteFile.prettyPrint();
        deleteFile.then().assertThat().statusCode(200);

        AmazonS3Exception expectedException = null;
        try {
            s3localstack.getObjectAsString(BUCKET_NAME, keyInS3);
        } catch (AmazonS3Exception ex) {
            expectedException = ex;
        }
        assertNotNull(expectedException);
        // 404 because the file has been sucessfully deleted
        assertEquals(404, expectedException.getStatusCode());

    }

    //TODO: move these into UtilIT. They are here for now to avoid merge conflicts
    static Response listStorageDrivers(String apiToken) {
        return given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, apiToken)
                .get("/api/admin/dataverse/storageDrivers");
    }

    static Response getStorageDriver(String dvAlias, String apiToken) {
        return given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, apiToken)
                .get("/api/admin/dataverse/" + dvAlias + "/storageDriver");
    }

    static Response setStorageDriver(String dvAlias, String label, String apiToken) {
        return given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, apiToken)
                .body(label)
                .put("/api/admin/dataverse/" + dvAlias + "/storageDriver");
    }

    static Response getUploadUrls(String idOrPersistentIdOfDataset, long sizeInBytes, String apiToken) {
        String idInPath = idOrPersistentIdOfDataset; // Assume it's a number.
        String optionalQueryParam = ""; // If idOrPersistentId is a number we'll just put it in the path.
        if (!NumberUtils.isCreatable(idOrPersistentIdOfDataset)) {
            idInPath = ":persistentId";
            optionalQueryParam = "&persistentId=" + idOrPersistentIdOfDataset;
        }
        RequestSpecification requestSpecification = given();
        if (apiToken != null) {
            requestSpecification = given()
                    .header(UtilIT.API_TOKEN_HTTP_HEADER, apiToken);
        }
        return requestSpecification.get("/api/datasets/" + idInPath + "/uploadurls?size=" + sizeInBytes + optionalQueryParam);
    }

    static Response uploadFileDirect(String url, InputStream inputStream) {
        return given()
                .header("x-amz-tagging", "dv-state=temp")
                .body(inputStream)
                .put(url);
    }

}
